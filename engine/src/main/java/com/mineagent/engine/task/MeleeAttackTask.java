package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.combat.MeleeAttackTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import java.util.Locale;

/**
 * Executes a melee attack task — tracks the target entity, navigates
 * within melee range (3 blocks), and strikes repeatedly until the
 * target dies or the task is cancelled.
 */
public class MeleeAttackTask extends CompanionTask<MeleeAttackTool.MeleeAttackTaskRecord>
        implements IntentAwareTask {

    private enum Phase { NAVIGATE, ATTACK, DONE }

    /** Maximum melee attack range in blocks. */
    private static final double MELEE_RANGE = 3.0;
    /** Minimum companion health to keep fighting. */
    private static final float MIN_HEALTH = 5.0f;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int aimTicks;
    private int repathTicks;
    private net.minecraft.core.BlockPos lastNavTarget;
    private String failReason;
    private int strikesLanded;

    public MeleeAttackTask(AgentPlayer player, MeleeAttackTool.MeleeAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        aimTicks = 0;
        repathTicks = 0;
        lastNavTarget = null;
        failReason = null;
        strikesLanded = 0;

        // Resolve target entity
        ServerLevel level = TaskContext.serverPlayer(player).serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = "Target entity " + record.entityId + " not found or dead";
            phase = Phase.DONE;
            return;
        }
        if (target.level() != TaskContext.serverPlayer(player).level()) {
            failReason = "Target moved to another dimension";
            phase = Phase.DONE;
        }

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());
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
    protected void onResume() {
        int verifiedStrikes = strikesLanded;
        onStart();
        strikesLanded = verifiedStrikes;
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
            failReason = "Companion health critical ("
                    + String.format(Locale.ROOT, "%.1f", player.health()) + "), retreating";
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
        repathTicks++;

        // Check if we're already in range
        double dist = distanceToTarget();
        if (dist <= MELEE_RANGE) {
            nav.cancel();
            TaskContext.inputDriver(player).clear();
            phase = Phase.ATTACK;
            aimTicks = 0;
        } else if (repathTicks >= 20
                && (lastNavTarget == null
                    || lastNavTarget.distManhattan(target.blockPosition()) >= 2)) {
            navigateToTarget();
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

        // Look at target
        lookAtTarget();

        if (!TaskContext.serverPlayer(player).hasLineOfSight(target)) {
            navigateToTarget();
            return;
        }

        // hold_ticks now has explicit semantics: extra stable aim time before
        // each swing. Java melee has no continuous "held attack" packet.
        if (aimTicks++ < record.holdTicks) return;

        var sp = TaskContext.serverPlayer(player);
        if (sp.getAttackStrengthScale(0.5f) >= 0.9f) {
            if (!Interaction.attackEntity(sp, target)) {
                navigateToTarget();
                return;
            }
            strikesLanded++;
            aimTicks = 0;
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
        lastNavTarget = target.blockPosition().immutable();
        repathTicks = 0;
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
    public TaskSnapshot snapshot() {
        var pos = target == null ? null : target.blockPosition();
        String stage = phase == null ? "initializing"
                : phase.name().toLowerCase(java.util.Locale.ROOT);
        return TaskSnapshot.progress(stage,
                "Melee combat with entity " + record.entityId,
                strikesLanded, -1,
                pos == null ? null : pos.getX(), pos == null ? null : pos.getY(),
                pos == null ? null : pos.getZ(),
                phase == Phase.DONE ? failReason : null,
                target == null ? null : "target_alive=" + target.isAlive(),
                ((long) strikesLanded << 3) ^ (phase == null ? 0L : phase.ordinal()));
    }

    @Override
    public IntentContract intentContract() {
        var policy = new IntentContract.TerrainPolicy(false, false, false, true,
                0, 0, 4, IntentContract.CleanupMode.CONTEXTUAL);
        return new IntentContract("Defeat entity " + record.entityId,
                "The target is no longer alive", null, null, null, policy,
                java.util.List.of(new IntentContract.Constraint("combat_terrain",
                        IntentContract.ConstraintKind.HARD,
                        "Do not alter terrain while pursuing a combat target",
                        "navigation")));
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
