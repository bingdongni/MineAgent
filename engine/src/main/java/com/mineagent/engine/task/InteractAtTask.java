package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.InteractAtTool;
import net.minecraft.core.BlockPos;

/** Navigates to and verifies a block use or progressive block attack. */
public class InteractAtTask extends CompanionTask<InteractAtTool.InteractAtTaskRecord> {
    private enum Phase { NAVIGATE, INTERACT, DONE }

    private static final int MAX_HOLD_TICKS = 40;
    private PlayerNav nav;
    private Phase phase;
    private int interactTicks;
    private int breakTimeoutTicks;
    private String failReason;
    private boolean breakStarted;

    public InteractAtTask(AgentPlayer player, InteractAtTool.InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        interactTicks = 0;
        breakTimeoutTicks = Integer.MAX_VALUE;
        failReason = null;
        breakStarted = false;
        nav = new PlayerNav(player, TaskContext.navCaches(player));
        nav.setListener(new PlayerNav.NavListener() {
            @Override public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.INTERACT;
            }
            @Override public void onNavigationFailed(String reason) {
                failReason = "Navigation to block failed: " + reason;
                phase = Phase.DONE;
            }
        });
        nav.navigateToBlock(record.x, record.y, record.z);
    }

    @Override
    protected TaskState onTick() {
        long now = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && now >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }
        switch (phase) {
            case NAVIGATE -> nav.tick();
            case INTERACT -> tickInteract();
            case DONE -> { }
        }
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return phase == Phase.DONE ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    private void tickInteract() {
        var sp = TaskContext.serverPlayer(player);
        lookAtTarget();
        var hand = "use_offhand".equals(record.button)
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
        if (record.itemId != null
                && !TaskContext.selectInventoryItemForHand(player, record.itemId, hand)) {
            failReason = "Required item '" + record.itemId + "' is not in inventory";
            phase = Phase.DONE;
            return;
        }

        BlockPos target = new BlockPos(record.x, record.y, record.z);
        if ("attack".equals(record.button)) {
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
            if (++interactTicks > breakTimeoutTicks) {
                BlockDigger.abortBreaking(sp, target);
                breakStarted = false;
                failReason = "Block did not break before interaction timeout";
                phase = Phase.DONE;
            }
            return;
        }

        int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_HOLD_TICKS));
        if (interactTicks == 0) {
            boolean accepted = switch (record.button) {
                case "use" -> Interaction.interactBlock(sp, target,
                        net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction();
                case "use_offhand" -> Interaction.interactBlock(sp, target,
                        net.minecraft.world.InteractionHand.OFF_HAND).consumesAction();
                default -> false;
            };
            if (!accepted) {
                failReason = "Block rejected the requested interaction";
                phase = Phase.DONE;
                return;
            }
        }

        if (++interactTicks >= requiredTicks) {
            TaskContext.inputDriver(player).clear();
            // Releasing charged items invokes their vanilla releaseUsing
            // behavior; stopUsingItem merely cancels and could never fire a
            // bow/trident after a requested hold duration.
            if (sp.isUsingItem()) sp.releaseUsingItem();
            phase = Phase.DONE;
        }
    }

    private void lookAtTarget() {
        var sp = TaskContext.serverPlayer(player);
        var eye = sp.getEyePosition();
        double dx = record.x + 0.5 - eye.x;
        double dy = record.y + 0.5 - eye.y;
        double dz = record.z + 0.5 - eye.z;
        sp.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        sp.setXRot((float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        if (breakStarted) {
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player),
                    new BlockPos(record.x, record.y, record.z));
            breakStarted = false;
        }
        TaskContext.inputDriver(player).clear();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override protected void onInterrupt() { cancelNav(); }
    @Override protected String successMessage() {
        return "Interacted at (" + record.x + ", " + record.y + ", " + record.z
                + ") with button=" + record.button;
    }
    @Override protected String timeoutMessage() {
        return "Interaction timed out at (" + record.x + ", " + record.y + ", " + record.z + ")";
    }
    @Override protected String failureMessage() {
        return failReason != null ? failReason : "Interaction failed at ("
                + record.x + ", " + record.y + ", " + record.z + ")";
    }
}
