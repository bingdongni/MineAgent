package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Interaction;
import com.mineagent.tools.InteractAtTool;
import net.minecraft.core.BlockPos;

/**
 * Executes a block interaction task — navigates adjacent to the target
 * block position and performs the specified interaction (use, attack,
 * or use_offhand).
 */
public class InteractAtTask extends CompanionTask<InteractAtTool.InteractAtTaskRecord> {

    private enum Phase { NAVIGATE, INTERACT, DONE }

    private PlayerNav nav;
    private Phase phase;
    private int interactTicks;
    private String failReason;
    private boolean breakStarted;

    /** Non-mining hold interactions are bounded by the public tool schema. */
    private static final int MAX_HOLD_TICKS = 40;
    private int breakTimeoutTicks;

    public InteractAtTask(AgentPlayer player, InteractAtTool.InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        interactTicks = 0;
        failReason = null;
        breakStarted = false;
        breakTimeoutTicks = Integer.MAX_VALUE;

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.INTERACT;
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = "Navigation to block failed: " + reason;
                phase = Phase.DONE;
            }
        });

        // Navigate adjacent to the target block
        nav.navigateToBlock(record.x, record.y, record.z);
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
            case INTERACT -> tickInteract();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (phase == Phase.DONE) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickInteract() {
        var sp = TaskContext.serverPlayer(player);
        var inputDriver = TaskContext.inputDriver(player);

        // Look at the target block
        lookAtTarget();

        // Hold specified item if needed
        if (record.itemId != null && !TaskContext.selectInventoryItem(player, record.itemId)) {
            failReason = "Required item '" + record.itemId + "' is not in inventory";
            phase = Phase.DONE;
            return;
        }

        // Block attack is completion-based, not hold-duration-based. The old
        // code reported success after one tick even when a survival-speed
        // block was still intact, and direct destroyBlock bypassed hardness.
        if ("attack".equals(record.button)) {
            BlockPos target = new BlockPos(record.x, record.y, record.z);
            if (sp.level().getBlockState(target).isAir()) {
                breakStarted = false;
                phase = Phase.DONE;
                return;
            }
            if (!breakStarted) {
                breakTimeoutTicks = BlockDigger.expectedBreakTicks(sp, target);
                if (!BlockDigger.startBreaking(sp, target)) {
                    failReason = "Target block cannot be broken";
                    phase = Phase.DONE;
                    return;
                }
                breakStarted = true;
            }
            interactTicks++;
            if (interactTicks > breakTimeoutTicks) {
                BlockDigger.abortBreaking(sp, target);
                breakStarted = false;
                failReason = "Block did not break before interaction timeout";
                phase = Phase.DONE;
            }
            return;
        }

        if (interactTicks > 0) {
            interactTicks++;
            int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_HOLD_TICKS));
            if (interactTicks >= requiredTicks) {
                inputDriver.clear();
                sp.stopUsingItem();
                phase = Phase.DONE;
            }
            return;
        }

        // Reaching the block is not evidence that the requested interaction
        // happened. Vanilla returns PASS/FAIL for air, out-of-reach targets,
        // and blocks/items that reject use; treating those results as success
        // makes the LLM continue from a world state that never existed.
        boolean accepted = switch (record.button) {
            case "use" -> Interaction.interactBlock(sp,
                    new BlockPos(record.x, record.y, record.z),
                    net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction();
            case "attack" -> throw new IllegalStateException("attack handled above");
            case "use_offhand" -> Interaction.interactBlock(sp,
                    new BlockPos(record.x, record.y, record.z),
                    net.minecraft.world.InteractionHand.OFF_HAND).consumesAction();
            default -> false;
        };
        if (!accepted) {
            failReason = "Block rejected the requested interaction";
            phase = Phase.DONE;
            return;
        }

        interactTicks++;

        // For hold interactions, keep pressing until hold_ticks reached
        int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_HOLD_TICKS));
        if (interactTicks < requiredTicks) {
            return; // keep holding
        }

        // Interaction done
        inputDriver.clear();
        sp.stopUsingItem();
        phase = Phase.DONE;
    }

    private void lookAtTarget() {
        var sp = TaskContext.serverPlayer(player);
        // Direction from companion eye to target block center
        double targetX = record.x + 0.5;
        double targetY = record.y + 0.5;
        double targetZ = record.z + 0.5;
        var eyePos = sp.getEyePosition();
        double dx = targetX - eyePos.x;
        double dy = targetY - eyePos.y;
        double dz = targetZ - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, dist));
        sp.setYRot(yaw);
        sp.setXRot(pitch);
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (breakStarted) {
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player),
                    new BlockPos(record.x, record.y, record.z));
            breakStarted = false;
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cancelNav();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override
    protected String successMessage() {
        return "Interacted at (" + record.x + ", " + record.y + ", " + record.z
                + ") with button=" + record.button;
    }

    @Override
    protected String timeoutMessage() {
        return "Interaction timed out at (" + record.x + ", " + record.y + ", " + record.z + ")";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Interaction failed at (" + record.x + ", " + record.y + ", " + record.z + ")";
    }
}
