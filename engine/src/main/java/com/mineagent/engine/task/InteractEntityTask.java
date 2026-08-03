package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Interaction;
import com.mineagent.tools.InteractEntityTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Executes an entity interaction task — navigates within range of the
 * target entity and performs the specified interaction (use, attack,
 * or use_offhand).
 */
public class InteractEntityTask extends CompanionTask<InteractEntityTool.InteractEntityTaskRecord> {

    private enum Phase { NAVIGATE, INTERACT, DONE }

    /** Interaction range for entity interactions. */
    private static final double INTERACT_RANGE = 3.0;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int interactTicks;
    private int nextAttackTick;
    private String failReason;

    /** Max ticks to attempt interaction before giving up. */
    private static final int MAX_INTERACT_TICKS = 40;

    public InteractEntityTask(AgentPlayer player, InteractEntityTool.InteractEntityTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        interactTicks = 0;
        nextAttackTick = 0;
        failReason = null;

        // Resolve target entity
        ServerLevel level = TaskContext.serverPlayer(player).serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = "Target entity " + record.entityId + " not found or dead";
            phase = Phase.DONE;
            return;
        }

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.INTERACT;
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = "Navigation to entity failed: " + reason;
                phase = Phase.DONE;
            }
        });

        navigateToEntity();
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        // Check if entity is gone
        if (target == null || !target.isAlive()) {
            if ("attack".equals(record.button) && interactTicks > 0) {
                // The requested interaction can legitimately remove its
                // target. Reporting that confirmed kill as "entity is gone"
                // inverted a successful held attack into failure.
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
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (phase == Phase.DONE) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        nav.tick();

        // Check if we're close enough
        double dist = distanceToTarget();
        if (dist <= INTERACT_RANGE) {
            nav.cancel();
            phase = Phase.INTERACT;
        }
    }

    private void tickInteract() {
        // Check if entity moved away
        double dist = distanceToTarget();
        if (dist > INTERACT_RANGE + 2.0) {
            navigateToEntity();
            return;
        }

        var sp = TaskContext.serverPlayer(player);
        var inputDriver = TaskContext.inputDriver(player);

        // Look at the entity
        lookAtTarget();

        // Hold specified item if needed
        if (record.itemId != null && !TaskContext.selectInventoryItem(player, record.itemId)) {
            failReason = "Required item '" + record.itemId + "' is not in inventory";
            phase = Phase.DONE;
            return;
        }

        if (interactTicks > 0) {
            interactTicks++;
            int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_INTERACT_TICKS));

            if ("attack".equals(record.button) && interactTicks <= requiredTicks
                    && interactTicks >= nextAttackTick) {
                // Holding the attack button means repeated attack attempts,
                // not sleeping after a single hit. Use the player's cooled
                // attack strength as the cadence so the task behaves like a
                // held client input without issuing a zero-damage hit every
                // server tick.
                if (sp.getAttackStrengthScale(0.0f) >= 0.9f) {
                    lookAtTarget();
                    if (!Interaction.attackEntity(sp, target)) {
                        navigateToEntity();
                        interactTicks = 0;
                        return;
                    }
                    sp.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    nextAttackTick = interactTicks + 1;
                }
            }
            if (interactTicks >= requiredTicks) {
                inputDriver.clear();
                sp.stopUsingItem();
                phase = Phase.DONE;
            }
            return;
        }

        // Perform the interaction
        if ("attack".equals(record.button)
                && sp.getAttackStrengthScale(0.0f) < 0.9f) {
            return;
        }
        boolean accepted = switch (record.button) {
            case "use" -> Interaction.interactEntity(
                    sp, target, net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction();
            case "attack" -> Interaction.attackEntity(sp, target);
            case "use_offhand" -> Interaction.interactEntity(
                    sp, target, net.minecraft.world.InteractionHand.OFF_HAND).consumesAction();
            default -> false;
        };
        if (!accepted) {
            // Reaching the entity is not evidence that the interaction ran.
            // Report vanilla PASS/FAIL instead of returning a false success.
            failReason = "Entity rejected the requested interaction";
            phase = Phase.DONE;
            return;
        }
        if ("attack".equals(record.button)) {
            sp.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }

        interactTicks++;
        nextAttackTick = interactTicks + 1;

        // For hold interactions, keep pressing until hold_ticks reached
        int requiredTicks = Math.max(1, Math.min(record.holdTicks, MAX_INTERACT_TICKS));
        if (interactTicks < requiredTicks) {
            return;
        }

        // Interaction done
        inputDriver.clear();
        sp.stopUsingItem();
        phase = Phase.DONE;
    }

    private void navigateToEntity() {
        if (target == null || !target.isAlive()) return;
        nav.navigateNear(
                target.blockPosition().getX(),
                target.blockPosition().getY(),
                target.blockPosition().getZ(),
                2 // arrive within 2 blocks
        );
        phase = Phase.NAVIGATE;
    }

    private double distanceToTarget() {
        if (target == null) return Double.MAX_VALUE;
        return TaskContext.serverPlayer(player).position().distanceTo(target.position());
    }

    private void lookAtTarget() {
        if (target == null) return;
        var sp = TaskContext.serverPlayer(player);
        var dir = target.position().add(0, target.getBbHeight() * 0.5, 0)
                .subtract(sp.getEyePosition()).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.asin(-dir.y));
        sp.setYRot(yaw);
        sp.setXRot(pitch);
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cancelNav();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override
    protected String successMessage() {
        return "Interacted with entity " + record.entityId + " (button=" + record.button + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "Entity interaction timed out on entity " + record.entityId;
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Entity interaction failed on entity " + record.entityId;
    }
}
