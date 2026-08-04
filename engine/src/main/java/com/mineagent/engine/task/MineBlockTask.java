package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.block.AutoMineTool;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds reachable matching blocks and mines them through vanilla progress. */
public class MineBlockTask extends CompanionTask<AutoMineTool.MineBlockTaskRecord>
        implements IntentAwareTask {

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
    private final Set<BlockPos> unreachableBlocks = new HashSet<>();
    private BlockScanner.ScanSession scanSession;

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
        unreachableBlocks.clear();

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
        setupNavigation();
        beginScan();
    }

    private void setupNavigation() {
        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.DIG;
            }

            @Override
            public void onNavigationFailed(String reason) {
                if (targetBlocks != null && currentBlockIndex < targetBlocks.size()) {
                    unreachableBlocks.add(targetBlocks.get(currentBlockIndex));
                }
                failReason = "Navigation failed: " + reason;
                beginScan();
            }
        });
    }

    private void beginScan() {
        var sp = TaskContext.serverPlayer(player);
        var pos = sp.blockPosition();
        scanSession = BlockScanner.begin(sp.serverLevel(), pos.getX(), pos.getY(), pos.getZ(),
                record.radius, record.blockType);
        targetBlocks = null;
        currentBlockIndex = 0;
        phase = Phase.SCAN;
    }

    private void finishScan() {
        targetBlocks = scanSession.results().stream()
                .filter(block -> !unreachableBlocks.contains(block))
                .toList();
        scanSession = null;
        if (targetBlocks.isEmpty()) {
            if (minedCount > 0) {
                failReason = null;
            } else if (failReason == null) {
                failReason = "No reachable blocks of type '" + record.blockType
                        + "' found within radius " + record.radius;
            }
            phase = Phase.DONE;
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

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (minedCount >= record.count) return TaskState.SUCCESS;
        if (phase == Phase.DONE && minedCount > 0) return TaskState.SUCCESS;
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
            advanceTarget();
            return;
        }

        if (activeBreakTarget == null) {
            digTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
            int durability = BlockDigger.toolDurability(sp);
            if (durability != Integer.MAX_VALUE && durability <= 1) {
                failReason = "No safe tool available (held durability: " + durability + ")";
                phase = Phase.DONE;
                return;
            }
            if (!BlockDigger.startBreaking(sp, target)) {
                unreachableBlocks.add(target);
                advanceTarget();
                return;
            }
            activeBreakTarget = target;
        }

        digTicks++;
        if (level.getBlockState(target).isAir()) {
            minedCount++;
            activeBreakTarget = null;
            advanceTarget();
        } else if (digTicks > digTimeoutTicks) {
            BlockDigger.abortBreaking(sp, target);
            activeBreakTarget = null;
            unreachableBlocks.add(target);
            advanceTarget();
        }
    }

    private void advanceTarget() {
        currentBlockIndex++;
        digTicks = 0;
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
        BlockPos target = targetBlocks != null
                && currentBlockIndex >= 0 && currentBlockIndex < targetBlocks.size()
                ? targetBlocks.get(currentBlockIndex) : null;
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        long version = ((long) minedCount << 32)
                ^ ((long) Math.max(0, currentBlockIndex) << 4)
                ^ (phase == null ? 0L : phase.ordinal());
        return TaskSnapshot.progress(stage,
                phase == Phase.DONE ? "Mining finished" : "Mining " + record.blockType,
                minedCount, record.count,
                target == null ? null : target.getX(),
                target == null ? null : target.getY(),
                target == null ? null : target.getZ(),
                phase == Phase.DONE ? failReason : null,
                activeBreakTarget == null ? null
                        : "vanilla_break_target=" + activeBreakTarget,
                version);
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
