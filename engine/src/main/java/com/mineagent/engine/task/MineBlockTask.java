package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.tools.block.AutoMineTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds reachable matching blocks and mines them through vanilla progress. */
public class MineBlockTask extends CompanionTask<AutoMineTool.MineBlockTaskRecord>
        implements IntentAwareTask {

    /** Independent guard in case navigation fails to publish a terminal event. */
    private static final long NAVIGATION_STALL_TICKS = 8L * 20L;
    private static final int MAX_CONSECUTIVE_TARGET_FAILURES = 12;

    private enum Phase { SCAN, NAVIGATE, DIG, DONE }

    private PlayerNav nav;
    private Phase phase;
    private int minedCount;
    private List<BlockPos> targetBlocks;
    private int currentBlockIndex;
    private int digTicks;
    private int digTimeoutTicks;
    private BlockPos activeBreakTarget;
    private String failReason;
    private String lastNavigationFailure;
    private final Set<BlockPos> unreachableBlocks = new HashSet<>();
    private BlockScanner.ScanSession scanSession;
    private long lastProgressTick;
    private long progressRevision;
    private long lastExecutorProgressVersion;
    private Vec3 lastProgressPosition;
    private int consecutiveTargetFailures;

    public MineBlockTask(AgentPlayer player, AutoMineTool.MineBlockTaskRecord record) {
        super(player, record);
    }

    @Override
    public void onStart() {
        phase = Phase.SCAN;
        minedCount = 0;
        currentBlockIndex = 0;
        digTicks = 0;
        digTimeoutTicks = Integer.MAX_VALUE;
        activeBreakTarget = null;
        failReason = null;
        lastNavigationFailure = null;
        unreachableBlocks.clear();
        long now = TaskContext.serverPlayer(player).level().getGameTime();
        lastProgressTick = now;
        progressRevision = 1L;
        lastExecutorProgressVersion = Long.MIN_VALUE;
        lastProgressPosition = TaskContext.serverPlayer(player).position();
        consecutiveTargetFailures = 0;

        setupNavigation();
        beginScan();
    }

    @Override
    protected void onResume() {
        // Preserve executor-verified minedCount and the unreachable set, but
        // discard the interrupted scan/path/break operation. The new scan is
        // grounded in the current world and cannot resume stale coordinates.
        digTicks = 0;
        digTimeoutTicks = Integer.MAX_VALUE;
        activeBreakTarget = null;
        failReason = null;
        lastNavigationFailure = null;
        markProgress(TaskContext.serverPlayer(player).level().getGameTime());
        setupNavigation();
        beginScan();
    }

    private void setupNavigation() {
        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) {
                    phase = Phase.DIG;
                    markProgress(TaskContext.serverPlayer(player).level().getGameTime());
                }
            }

            @Override
            public void onNavigationFailed(String reason) {
                lastNavigationFailure = "Navigation failed: " + reason;
                if (recordTargetFailure(currentTarget(), lastNavigationFailure)) return;
                beginScan();
            }
        });
    }

    private void beginScan() {
        var sp = TaskContext.serverPlayer(player);
        var pos = sp.blockPosition();
        // A fresh target search owns a fresh break timer. Carrying digTicks
        // across a navigation failure made the next valid block inherit the
        // previous target's elapsed time and fail prematurely.
        digTicks = 0;
        digTimeoutTicks = Integer.MAX_VALUE;
        activeBreakTarget = null;
        scanSession = BlockScanner.begin(sp.serverLevel(), pos.getX(), pos.getY(), pos.getZ(),
                record.radius, record.blockType);
        targetBlocks = null;
        currentBlockIndex = 0;
        phase = Phase.SCAN;
        lastExecutorProgressVersion = Long.MIN_VALUE;
        markProgress(sp.level().getGameTime());
    }

    private void finishScan() {
        targetBlocks = scanSession.results().stream()
                .filter(block -> !unreachableBlocks.contains(block))
                .toList();
        scanSession = null;
        if (targetBlocks.isEmpty()) {
            if (minedCount > 0) {
                failReason = "Only mined " + minedCount + "/" + record.count
                        + " blocks of '" + record.blockType
                        + "' before no reachable targets remained";
            } else {
                failReason = "No reachable blocks of type '" + record.blockType
                        + "' found within radius " + record.radius
                        + (lastNavigationFailure == null ? ""
                        : "; last_navigation_failure=" + lastNavigationFailure);
            }
            phase = Phase.DONE;
            markProgress(TaskContext.serverPlayer(player).level().getGameTime());
            return;
        }
        currentBlockIndex = 0;
    }

    @Override
    public TaskState onTick() {
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> tickScan();
            case NAVIGATE -> nav.tick();
            case DIG -> tickDig();
            case DONE -> { }
        }

        observeNavigationProgress(gameTime);
        if (phase == Phase.NAVIGATE
                && gameTime - lastProgressTick > NAVIGATION_STALL_TICKS) {
            lastNavigationFailure = "Navigation produced no physical or path progress for "
                    + (gameTime - lastProgressTick) + " ticks at player="
                    + TaskContext.serverPlayer(player).blockPosition().toShortString();
            nav.cancel();
            if (!recordTargetFailure(currentTarget(), lastNavigationFailure)) beginScan();
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (minedCount >= record.count) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickScan() {
        if (scanSession == null) beginScan();
        // Bound the radius scan so a large request cannot monopolize a tick.
        scanSession.scan(4096);
        if (!scanSession.isComplete()) return;
        finishScan();
        if (phase != Phase.DONE) navigateToNextBlock();
    }

    private void tickDig() {
        if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
            return;
        }

        BlockPos target = targetBlocks.get(currentBlockIndex);
        var sp = TaskContext.serverPlayer(player);
        var level = sp.serverLevel();

        if (level.getBlockState(target).isAir()) {
            // Only count disappearance after this task owned the break target.
            if (target.equals(activeBreakTarget)) minedCount++;
            activeBreakTarget = null;
            invalidateWorldAsset(target);
            if (minedCount > 0) consecutiveTargetFailures = 0;
            markProgress(level.getGameTime());
            advanceTarget();
            return;
        }

        if (activeBreakTarget == null) {
            int durability = BlockDigger.toolDurability(sp);
            if (durability != Integer.MAX_VALUE && durability <= 1) {
                failReason = "No safe tool available (held durability: " + durability + ")";
                phase = Phase.DONE;
                return;
            }
            BlockDigger.BreakAssessment assessment =
                    BlockDigger.startBreakingDetailed(sp, target);
            if (!assessment.allowed()) {
                if (!recordTargetFailure(target,
                        "Could not start vanilla break at " + target.toShortString()
                                + "; " + assessment.diagnostic())) advanceTarget();
                return;
            }
            digTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
            activeBreakTarget = target;
            markProgress(level.getGameTime());
        }

        digTicks++;
        if (level.getBlockState(target).isAir()) {
            minedCount++;
            consecutiveTargetFailures = 0;
            activeBreakTarget = null;
            invalidateWorldAsset(target);
            markProgress(level.getGameTime());
            advanceTarget();
        } else if (digTicks > digTimeoutTicks) {
            BlockDigger.abortBreaking(sp, target);
            activeBreakTarget = null;
            if (!recordTargetFailure(target,
                    "Vanilla break timed out at " + target.toShortString()
                            + " after " + digTicks + " ticks")) advanceTarget();
        }
    }

    /** Stop boundedly when local evidence says this goal is not executable. */
    private boolean recordTargetFailure(BlockPos target, String reason) {
        if (target != null) unreachableBlocks.add(target.immutable());
        consecutiveTargetFailures++;
        if (consecutiveTargetFailures < MAX_CONSECUTIVE_TARGET_FAILURES) return false;
        failReason = "Mining stopped after " + consecutiveTargetFailures
                + " consecutive unreachable targets; last_failure=" + reason;
        phase = Phase.DONE;
        markProgress(TaskContext.serverPlayer(player).level().getGameTime());
        return true;
    }

    private void advanceTarget() {
        currentBlockIndex++;
        digTicks = 0;
        markProgress(TaskContext.serverPlayer(player).level().getGameTime());
        if (minedCount >= record.count) {
            cancelNav();
        } else if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
        } else {
            navigateToNextBlock();
        }
    }

    private void navigateToNextBlock() {
        if (targetBlocks == null || currentBlockIndex >= targetBlocks.size()) {
            beginScan();
            return;
        }
        BlockPos target = targetBlocks.get(currentBlockIndex);
        nav.navigateToBlock(target.getX(), target.getY(), target.getZ());
        phase = Phase.NAVIGATE;
        lastExecutorProgressVersion = Long.MIN_VALUE;
        markProgress(TaskContext.serverPlayer(player).level().getGameTime());
    }

    /**
     * Treat only executor evidence as progress. Phase labels alone previously
     * kept a task looking healthy while the body remained at one coordinate.
     */
    private void observeNavigationProgress(long gameTime) {
        if (phase != Phase.NAVIGATE || nav == null) return;
        var sp = TaskContext.serverPlayer(player);
        Vec3 position = sp.position();
        if (lastProgressPosition == null
                || position.distanceToSqr(lastProgressPosition) >= 0.04) {
            lastProgressPosition = position;
            markProgress(gameTime);
        }
        var executor = nav.core().executor();
        if (executor != null && executor.progressVersion() != lastExecutorProgressVersion) {
            lastExecutorProgressVersion = executor.progressVersion();
            markProgress(gameTime);
        }
    }

    private void markProgress(long gameTime) {
        lastProgressTick = gameTime;
        progressRevision++;
    }

    private void invalidateWorldAsset(BlockPos target) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null || target == null) return;
        var sp = TaskContext.serverPlayer(player);
        loop.worldAssetIndex().invalidatePosition(new WorldAssetIndex.Position(
                sp.level().dimension().location().toString(), target.getX(),
                target.getY(), target.getZ()));
    }

    private BlockPos currentTarget() {
        return targetBlocks != null
                && currentBlockIndex >= 0 && currentBlockIndex < targetBlocks.size()
                ? targetBlocks.get(currentBlockIndex) : null;
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (activeBreakTarget != null) {
            var sp = TaskContext.serverPlayer(player);
            // A block can finish between task ticks because vanilla advances
            // ServerPlayerGameMode independently. Preserve that verified
            // completion before clearing the progressive break owner.
            if (sp.level().getBlockState(activeBreakTarget).isAir()) {
                minedCount++;
            } else {
                BlockDigger.abortBreaking(sp, activeBreakTarget);
            }
            activeBreakTarget = null;
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    public void onInterrupt() { cancelNav(); }

    @Override
    public TaskSnapshot snapshot() {
        BlockPos target = currentTarget();
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        var sp = TaskContext.serverPlayer(player);
        long stagnantTicks = Math.max(0L, sp.level().getGameTime() - lastProgressTick);
        StringBuilder evidence = new StringBuilder("player=")
                .append(sp.blockPosition().toShortString())
                .append(" stagnant_ticks=").append(stagnantTicks);
        if (nav != null) {
            evidence.append(" nav_state=").append(nav.state());
            var executor = nav.core().executor();
            if (executor != null) {
                evidence.append(" movement=").append(executor.currentMovementIndex())
                        .append('/').append(executor.path().length())
                        .append(" movement_ticks=").append(executor.ticksOnCurrentMovement())
                        .append(" movement_stagnant_ticks=")
                        .append(executor.ticksWithoutProgress());
            }
            if (nav.core().lastFailureDetail() != null) {
                evidence.append(" last_path_failure=")
                        .append(nav.core().lastFailureDetail());
            }
        }
        if (activeBreakTarget != null) {
            evidence.append(" vanilla_break_target=").append(activeBreakTarget)
                    .append(" break_ticks=").append(digTicks)
                    .append('/').append(digTimeoutTicks);
        }
        return TaskSnapshot.progress(stage,
                phase == Phase.DONE ? "Mining finished" : "Mining " + record.blockType,
                minedCount, record.count,
                target == null ? null : target.getX(),
                target == null ? null : target.getY(),
                target == null ? null : target.getZ(),
                phase == Phase.DONE ? failReason : null,
                evidence.toString(), progressRevision);
    }

    @Override
    public IntentContract intentContract() {
        String id = record.blockType == null ? "" : record.blockType.toLowerCase(
                java.util.Locale.ROOT);
        boolean treeMaterial = id.contains("_log") || id.contains("_wood")
                || id.endsWith(":log") || id.endsWith(":wood");
        IntentContract.TerrainPolicy policy = new IntentContract.TerrainPolicy(
                true, !treeMaterial, !treeMaterial, false,
                treeMaterial ? 0 : 8, 12, treeMaterial ? 3 : 12,
                IntentContract.CleanupMode.CONTEXTUAL);
        return new IntentContract("Mine " + record.count + " blocks of " + record.blockType,
                "The requested blocks disappear through vanilla breaking and are counted once",
                null, null, null, policy, java.util.List.of(
                new IntentContract.Constraint("target_material",
                        IntentContract.ConstraintKind.HARD,
                        "Only requested blocks count as task output; other breaks are navigation obstacles",
                        "mining"),
                new IntentContract.Constraint("tree_topology",
                        treeMaterial ? IntentContract.ConstraintKind.HARD
                                : IntentContract.ConstraintKind.PREFERENCE,
                        treeMaterial ? "Do not pillar or bridge merely to chop this tree"
                                : "Minimize non-target terrain changes",
                        "navigation")));
    }

    @Override
    protected String successMessage() {
        return "Mined " + minedCount + " blocks of " + record.blockType;
    }

    @Override
    protected String timeoutMessage() {
        return "Mining timed out after mining " + minedCount + "/" + record.count
                + " blocks of " + record.blockType;
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Mining failed after mining " + minedCount + "/" + record.count
                + " blocks of " + record.blockType;
    }
}
