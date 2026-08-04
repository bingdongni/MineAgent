package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Placement;
import com.mineagent.tools.block.BuildTool;
import com.mineagent.engine.util.McCompat;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * Executes a build task — places or clears blocks at specified positions.
 *
 * <p>For "place" mode: navigates adjacent to each position and places
 * the specified block from inventory.
 * For "clear" mode: navigates adjacent to each position and breaks
 * the block at that position.
 */
public class BuildTask extends CompanionTask<BuildTool.BuildTaskRecord>
        implements IntentAwareTask {

    private enum Phase { NAVIGATE, ACT, DONE }

    private PlayerNav nav;
    private Phase phase;
    private int currentIndex;
    private int completedCount;
    private int actTicks;
    private BlockPos activeBreakTarget;
    private String failReason;
    private String lastPlaceFailure;
    private boolean[] verifiedPositions;

    /** Placement should resolve promptly; breaking uses a hardness-aware limit. */
    private static final int MAX_PLACE_TICKS = 60;
    private int actTimeoutTicks;

    public BuildTask(AgentPlayer player, BuildTool.BuildTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        verifiedPositions = new boolean[record.positions.length];
        refreshVerifiedPositions();
        actTicks = 0;
        actTimeoutTicks = MAX_PLACE_TICKS;
        activeBreakTarget = null;
        failReason = null;
        lastPlaceFailure = null;

        setupNavigation();

        navigateToCurrent();
    }

    @Override
    protected void onResume() {
        // Re-evaluate every requested cell from the authoritative world. A
        // survival interruption may occur immediately after vanilla places or
        // breaks a block but before the next task tick observes it.
        refreshVerifiedPositions();
        actTicks = 0;
        actTimeoutTicks = MAX_PLACE_TICKS;
        activeBreakTarget = null;
        failReason = null;
        lastPlaceFailure = null;
        setupNavigation();
        navigateToCurrent();
    }

    private void setupNavigation() {
        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) {
                    // PathExecutor normally clears on its final movement. Do
                    // it again at the task boundary so an already-satisfied
                    // empty path cannot inherit input from previous body work.
                    TaskContext.inputDriver(player).clear();
                    phase = Phase.ACT;
                }
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = "Navigation failed: " + reason;
                phase = Phase.DONE;
            }
        });
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case NAVIGATE -> nav.tick();
            case ACT -> tickAct();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (completedCount >= record.positions.length) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickAct() {
        if (currentIndex >= record.positions.length) {
            phase = Phase.DONE;
            return;
        }

        int[] pos = record.positions[currentIndex];
        BlockPos target = new BlockPos(pos[0], pos[1], pos[2]);
        var sp = TaskContext.serverPlayer(player);
        var level = sp.serverLevel();

        // Reaching a path goal is a snapshot, not a permanent guarantee. A
        // collision, knockback or residual velocity can move the body before
        // the action tick. Re-navigate instead of repeating an impossible
        // out-of-position click until the placement timeout expires.
        if (!nav.isAtGoal()) {
            if (activeBreakTarget != null) {
                BlockDigger.abortBreaking(sp, activeBreakTarget);
                activeBreakTarget = null;
            }
            navigateToCurrent();
            return;
        }

        if ("place".equals(record.mode)) {
            // Check if block is already there (air or replaceable)
            var state = level.getBlockState(target);
            if (!state.isAir() && !state.canBeReplaced()) {
                if (McCompat.isBlock(state, record.blockType)) {
                    // Idempotent retry: the requested block already exists.
                    advancePosition();
                } else {
                    failReason = "Position already contains a different block at ("
                            + pos[0] + "," + pos[1] + "," + pos[2] + ")";
                    phase = Phase.DONE;
                }
                return;
            }

            // Check if we have the block in inventory
            var inventory = sp.getInventory();
            int foundSlot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                // Inventory exposes armor as slots 36-39. Never swap equipped
                // gear into the hotbar merely because a command-added armor
                // stack happens to match a placeable block ID. Offhand 40 is
                // carried inventory and is safe to swap normally.
                if (i >= 36 && i <= 39) continue;
                var stack = inventory.getItem(i);
                if (!stack.isEmpty() && McCompat.isItem(stack, record.blockType)) {
                    foundSlot = i;
                    break;
                }
            }

            if (foundSlot == -1) {
                failReason = "No '" + record.blockType + "' in inventory for position "
                        + currentIndex + " (" + pos[0] + "," + pos[1] + "," + pos[2] + ")";
                phase = Phase.DONE;
                return;
            }

            // Ensure the block is in the selected hotbar slot
            if (foundSlot < 9) {
                // Already in hotbar — just switch to it
                player.holdInHand(foundSlot);
            } else {
                // Swap from main inventory into the current hotbar slot
                int currentSlot = inventory.selected;
                var temp = inventory.getItem(currentSlot);
                inventory.setItem(currentSlot, inventory.getItem(foundSlot));
                inventory.setItem(foundSlot, temp);
                TaskContext.syncInventory(sp);
            }

            // Place the block using right-click interaction
            // Target the requested position explicitly. A view-based click can
            // place on a neighbor while this state machine checks target.
            Placement.Attempt attempt = Placement.placeHeldBlock(
                    sp, target, InteractionHand.MAIN_HAND);
            lastPlaceFailure = attempt.reason();
            actTicks++;

            // Check if block was placed
            var newState = level.getBlockState(target);
            if (McCompat.isBlock(newState, record.blockType)) {
                // useItemOn mutates the held stack inside vanilla. Fake players
                // lack a real client packet loop, so explicitly publish the
                // consumed block count after a confirmed placement.
                TaskContext.syncInventory(sp);
                advancePosition();
            } else if (actTicks > MAX_PLACE_TICKS) {
                failReason = "Failed to place block at (" + pos[0] + ","
                        + pos[1] + "," + pos[2] + "): " + lastPlaceFailure
                        + "; player=" + sp.blockPosition();
                System.err.println("[MineAgent] " + failReason
                        + "; requested=" + record.blockType
                        + "; held=" + sp.getMainHandItem());
                phase = Phase.DONE;
            }

        } else { // "clear"
            var state = level.getBlockState(target);
            if (state.isAir()) {
                activeBreakTarget = null;
                advancePosition();
                return;
            }

            // Start once, then allow vanilla gameMode.tick() to advance the
            // break. Direct destroyBlock() made every clear instantaneous and
            // ignored the configured survival mining behavior.
            if (activeBreakTarget == null) {
                actTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
                if (!BlockDigger.startBreaking(sp, target)) {
                    failReason = "Target block cannot be cleared at ("
                            + pos[0] + "," + pos[1] + "," + pos[2] + ")";
                    phase = Phase.DONE;
                    return;
                }
                activeBreakTarget = target;
            }
            actTicks++;
            if (level.getBlockState(target).isAir()) {
                activeBreakTarget = null;
                advancePosition();
            } else if (actTicks > actTimeoutTicks) {
                BlockDigger.abortBreaking(sp, target);
                activeBreakTarget = null;
                failReason = "Failed to clear block at (" + pos[0] + "," + pos[1] + "," + pos[2] + ")";
                phase = Phase.DONE;
            }
        }
    }

    private void advancePosition() {
        if (currentIndex < verifiedPositions.length && !verifiedPositions[currentIndex]) {
            verifiedPositions[currentIndex] = true;
            completedCount++;
        }
        currentIndex++;
        while (currentIndex < verifiedPositions.length && verifiedPositions[currentIndex]) {
            currentIndex++;
        }
        actTicks = 0;
        actTimeoutTicks = MAX_PLACE_TICKS;
        lastPlaceFailure = null;

        if (currentIndex >= record.positions.length) {
            phase = Phase.DONE;
            return;
        }

        navigateToCurrent();
    }

    private void navigateToCurrent() {
        if (currentIndex >= record.positions.length) {
            phase = Phase.DONE;
            return;
        }
        int[] pos = record.positions[currentIndex];
        if ("place".equals(record.mode)) {
            nav.navigateForPlacement(pos[0], pos[1], pos[2]);
        } else {
            nav.navigateToBlock(pos[0], pos[1], pos[2]);
        }
        phase = Phase.NAVIGATE;
    }

    private void refreshVerifiedPositions() {
        if (verifiedPositions == null
                || verifiedPositions.length != record.positions.length) {
            verifiedPositions = new boolean[record.positions.length];
        }
        var level = TaskContext.serverPlayer(player).serverLevel();
        completedCount = 0;
        currentIndex = record.positions.length;
        for (int index = 0; index < record.positions.length; index++) {
            int[] raw = record.positions[index];
            BlockPos pos = new BlockPos(raw[0], raw[1], raw[2]);
            var state = level.getBlockState(pos);
            boolean verified = "place".equals(record.mode)
                    ? McCompat.isBlock(state, record.blockType) : state.isAir();
            verifiedPositions[index] = verified;
            if (verified) completedCount++;
            else if (currentIndex == record.positions.length) currentIndex = index;
        }
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (activeBreakTarget != null) {
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player), activeBreakTarget);
            activeBreakTarget = null;
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cancelNav();
    }

    @Override
    public TaskSnapshot snapshot() {
        int[] target = currentIndex >= 0 && currentIndex < record.positions.length
                ? record.positions[currentIndex] : null;
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        long version = ((long) completedCount << 32)
                ^ ((long) Math.max(0, currentIndex) << 4)
                ^ (phase == null ? 0L : phase.ordinal());
        return TaskSnapshot.progress(stage,
                record.mode + " " + record.blockType,
                completedCount, record.positions.length,
                target == null ? null : target[0],
                target == null ? null : target[1],
                target == null ? null : target[2],
                phase == Phase.DONE ? failReason : null,
                activeBreakTarget != null ? "vanilla_break_target=" + activeBreakTarget
                        : lastPlaceFailure,
                version);
    }

    @Override
    public IntentContract intentContract() {
        int currentY = TaskContext.serverPlayer(player).blockPosition().getY();
        int highestY = currentY;
        for (int[] position : record.positions) highestY = Math.max(highestY, position[1]);
        IntentContract.TerrainPolicy policy = new IntentContract.TerrainPolicy(
                false, true, true, false, 32, 0,
                Math.max(4, highestY - currentY + 4),
                IntentContract.CleanupMode.CONTEXTUAL);
        return new IntentContract(record.mode + " requested build cells",
                "Every requested cell is verified against the desired world state",
                null, null, null, policy, java.util.List.of(
                new IntentContract.Constraint("build_output",
                        IntentContract.ConstraintKind.HARD,
                        "Requested build cells are permanent output; only navigation supports are temporary",
                        "building"),
                new IntentContract.Constraint("protect_surroundings",
                        IntentContract.ConstraintKind.HARD,
                        "Navigation must not clear unrelated terrain to reach a build cell",
                        "navigation")));
    }

    @Override
    protected String successMessage() {
        return record.mode + " " + completedCount + " blocks of " + record.blockType;
    }

    @Override
    protected String timeoutMessage() {
        return "Build timed out after " + completedCount + "/" + record.positions.length
                + " positions (" + record.mode + ")";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Build failed after " + completedCount + "/" + record.positions.length + " positions";
    }
}
