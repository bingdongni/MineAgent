package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Interaction;
import com.mineagent.tools.combat.MeleeAttackTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Executes a melee attack task — tracks the target entity, navigates
 * within melee range (3 blocks), and strikes repeatedly until the
 * target dies or the task is cancelled.
 */
public class MeleeAttackTask extends CompanionTask<MeleeAttackTool.MeleeAttackTaskRecord> {

    private enum Phase { NAVIGATE, ATTACK, DONE }

    /** Maximum melee attack range in blocks. */
    private static final double MELEE_RANGE = 3.0;
    /** Ticks between attack attempts (attack cooldown). */
    private static final int ATTACK_COOLDOWN = 20;
    /** Minimum companion health to keep fighting. */
    private static final float MIN_HEALTH = 5.0f;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int attackCooldown;
    private int attackHoldRemaining;
    private String failReason;

    public MeleeAttackTask(AgentPlayer player, MeleeAttackTool.MeleeAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        attackCooldown = 0;
        attackHoldRemaining = 0;

        // Resolve target entity
        ServerLevel level = TaskContext.serverPlayer(player).serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = "Target entity " + record.entityId + " not found or dead";
            phase = Phase.DONE;
            return;
        }
        if (target == TaskContext.serverPlayer(player) || !target.isAttackable()) {
            failReason = "Target entity " + record.entityId + " cannot be attacked";
            phase = Phase.DONE;
            return;
        }

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                // Reached target area, switch to attack
                if (phase == Phase.NAVIGATE) phase = Phase.ATTACK;
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = "Navigation to target failed: " + reason;
                phase = Phase.DONE;
            }
        });

        navigateToTarget();
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        // Check companion health — retreat if dying
        if (player.health() < MIN_HEALTH && player.isAlive()) {
            failReason = "Companion health critical (" + String.format("%.1f", player.health()) + "), retreating";
            phase = Phase.DONE;
        }

        // Check if companion died
        if (!player.isAlive()) {
            failReason = "Companion died";
            phase = Phase.DONE;
        }

        // Check if target is gone
        if (target == null || !target.isAlive()) {
            // Target died — success
            cancelNav();
            return TaskState.SUCCESS;
        }

        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case ATTACK -> tickAttack();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        // Re-navigate to target's current position periodically
        nav.tick();

        // Check if we're already in range
        double dist = distanceToTarget();
        if (dist <= MELEE_RANGE) {
            nav.cancel();
            phase = Phase.ATTACK;
        }
    }

    private void tickAttack() {
        // Check distance — if target moved away, re-navigate
        double dist = distanceToTarget();
        if (dist > MELEE_RANGE + 1.0) {
            navigateToTarget();
            phase = Phase.NAVIGATE;
            return;
        }

        // A non-zero hold_ticks models keeping the attack input pressed after
        // the initial strike. The parameter used to be parsed and persisted
        // but never read by this task, making the public tool contract inert.
        if (attackHoldRemaining > 0) {
            lookAtTarget();
            var sp = TaskContext.serverPlayer(player);
            // A held mouse button does not send a full attack every tick.
            // Doing so resets vanilla's attack ticker continuously, leaving
            // every strike after the first at negligible damage.
            if (sp.getAttackStrengthScale(0.0f) >= 0.9f) {
                if (!Interaction.attackEntity(sp, target)) {
                    navigateToTarget();
                    attackHoldRemaining = 0;
                    return;
                }
                sp.swing(InteractionHand.MAIN_HAND);
            }
            attackHoldRemaining--;
            if (attackHoldRemaining == 0) attackCooldown = ATTACK_COOLDOWN;
            return;
        }

        // Wait for attack cooldown
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }

        // Look at target
        lookAtTarget();

        // Attack the resolved entity directly. A generic view ray can hit an
        // intervening block or miss the entity bounding box even after aiming,
        // which made the state machine report attacks that never occurred.
        var sp = TaskContext.serverPlayer(player);
        if (sp.getAttackStrengthScale(0.0f) < 0.9f) return;
        if (!sp.hasLineOfSight(target)) {
            // Calling ServerPlayer.attack directly bypasses the packet
            // handler's visibility checks. Without this guard the companion
            // can damage an entity through a wall from an otherwise valid
            // three-block center distance.
            navigateToTarget();
            return;
        }
        if (Interaction.attackEntity(sp, target)) {
            sp.swing(InteractionHand.MAIN_HAND);
            attackHoldRemaining = record.holdTicks;
            if (attackHoldRemaining == 0) attackCooldown = ATTACK_COOLDOWN;
        } else {
            navigateToTarget();
        }
    }

    private void navigateToTarget() {
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
        var companionPos = TaskContext.serverPlayer(player).position();
        var targetPos = target.position();
        return companionPos.distanceTo(targetPos);
    }

    private void lookAtTarget() {
        if (target == null) return;
        var sp = TaskContext.serverPlayer(player);
        var dir = target.position().subtract(sp.getEyePosition()).normalize();
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
    }

    @Override
    protected String successMessage() {
        return "Melee attack completed on entity " + record.entityId;
    }

    @Override
    protected String timeoutMessage() {
        return "Melee attack timed out on entity " + record.entityId;
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Melee attack failed on entity " + record.entityId;
    }
}
