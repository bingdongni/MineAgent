package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.InteractEntityTool;
import net.minecraft.world.entity.Entity;

/** Navigates to an entity and performs an accepted vanilla interaction. */
public class InteractEntityTask extends CompanionTask<InteractEntityTool.InteractEntityTaskRecord> {
    private enum Phase { NAVIGATE, INTERACT, DONE }
    private static final double INTERACT_RANGE = 3.0;
    private static final int MAX_INTERACT_TICKS = 40;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int interactTicks;
    private int nextAttackTick;
    private String failReason;

    public InteractEntityTask(AgentPlayer player,
                              InteractEntityTool.InteractEntityTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        interactTicks = 0;
        nextAttackTick = 0;
        failReason = null;
        target = TaskContext.serverPlayer(player).serverLevel().getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = "Target entity " + record.entityId + " not found or dead";
            phase = Phase.DONE;
            return;
        }
        nav = new PlayerNav(player, TaskContext.navCaches(player));
        nav.setListener(new PlayerNav.NavListener() {
            @Override public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.INTERACT;
            }
            @Override public void onNavigationFailed(String reason) {
                failReason = "Navigation to entity failed: " + reason;
                phase = Phase.DONE;
            }
        });
        navigateToEntity();
    }

    @Override
    protected TaskState onTick() {
        long now = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && now >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }
        if (target == null || !target.isAlive()) {
            if ("attack".equals(record.button) && interactTicks > 0) {
                cancelNav();
                return TaskState.SUCCESS;
            }
            failReason = "Target entity " + record.entityId + " is gone";
            cancelNav();
            return TaskState.FAILED;
        }
        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case INTERACT -> tickInteract();
            case DONE -> { }
        }
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return phase == Phase.DONE ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    private void tickNavigate() {
        nav.tick();
        if (distanceToTarget() <= INTERACT_RANGE) {
            nav.cancel();
            phase = Phase.INTERACT;
        }
    }

    private void tickInteract() {
        if (distanceToTarget() > INTERACT_RANGE + 2.0) {
            navigateToEntity();
            return;
        }
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

        int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_INTERACT_TICKS));
        if (interactTicks > 0) {
            interactTicks++;
            if ("attack".equals(record.button) && interactTicks <= requiredTicks
                    && interactTicks >= nextAttackTick
                    && sp.getAttackStrengthScale(0.0f) >= 0.9f) {
                lookAtTarget();
                if (!Interaction.attackEntity(sp, target)) {
                    navigateToEntity();
                    interactTicks = 0;
                    return;
                }
                nextAttackTick = interactTicks + 1;
            }
            if (interactTicks >= requiredTicks) {
                cancelNav();
                phase = Phase.DONE;
            }
            return;
        }

        if ("attack".equals(record.button) && sp.getAttackStrengthScale(0.0f) < 0.9f) return;
        boolean accepted = switch (record.button) {
            case "use" -> Interaction.interactEntity(sp, target,
                    net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction();
            case "attack" -> Interaction.attackEntity(sp, target);
            case "use_offhand" -> Interaction.interactEntity(sp, target,
                    net.minecraft.world.InteractionHand.OFF_HAND).consumesAction();
            default -> false;
        };
        if (!accepted) {
            failReason = "Entity rejected the requested interaction";
            phase = Phase.DONE;
            return;
        }
        interactTicks = 1;
        nextAttackTick = 2;
        if (interactTicks >= requiredTicks) {
            cancelNav();
            phase = Phase.DONE;
        }
    }

    private void navigateToEntity() {
        if (target == null || !target.isAlive()) return;
        var pos = target.blockPosition();
        nav.navigateNear(pos.getX(), pos.getY(), pos.getZ(), 2);
        phase = Phase.NAVIGATE;
    }

    private double distanceToTarget() {
        return target == null ? Double.MAX_VALUE
                : TaskContext.serverPlayer(player).position().distanceTo(target.position());
    }

    private void lookAtTarget() {
        if (target == null) return;
        var sp = TaskContext.serverPlayer(player);
        var direction = target.position().add(0, target.getBbHeight() * 0.5, 0)
                .subtract(sp.getEyePosition()).normalize();
        sp.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        sp.setXRot((float) Math.toDegrees(Math.asin(-direction.y)));
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override protected void onInterrupt() { cancelNav(); }
    @Override protected String successMessage() {
        return "Interacted with entity " + record.entityId + " (button=" + record.button + ")";
    }
    @Override protected String timeoutMessage() {
        return "Entity interaction timed out on entity " + record.entityId;
    }
    @Override protected String failureMessage() {
        return failReason != null ? failReason
                : "Entity interaction failed on entity " + record.entityId;
    }
}
