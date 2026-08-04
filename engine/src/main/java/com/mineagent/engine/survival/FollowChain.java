package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.entity.SafeTeleport;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Follows the owner through the same path executor used by LLM tasks. */
public final class FollowChain implements TaskChain {

    private static final float PRIORITY = 2.5f;
    private static final double FOLLOW_START_DISTANCE = 8.0;
    private static final double STOP_DISTANCE = 4.0;
    private static final double RECOVERY_TELEPORT_DISTANCE = 64.0;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int FAILURE_COOLDOWN_TICKS = 100;

    private final AgentPlayer companion;
    private final CompanionBodyLog bodyLog;
    private PlayerNav nav;
    private boolean following;
    private int repathTicks;
    private int failureCooldown;
    private BlockPos lastOwnerGoal;

    public FollowChain(AgentPlayer companion) {
        this.companion = companion;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
        this.nav = createNav();
    }

    private PlayerNav createNav() {
        PlayerNav created = new PlayerNav(companion, TaskContext.navCaches(companion));
        created.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                following = false;
                TaskContext.inputDriver(companion).clear();
            }

            @Override
            public void onNavigationFailed(String reason) {
                following = false;
                failureCooldown = FAILURE_COOLDOWN_TICKS;
                TaskContext.inputDriver(companion).clear();
                bodyLog.report("couldn't find a safe route to the owner: " + reason);
            }
        });
        return created;
    }

    @Override
    public String name() { return "follow"; }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            if (MineAgentEngine.getCompanionMode(companion.companionId())
                    != MineAgentEngine.CompanionMode.FOLLOW) {
                return Float.NEGATIVE_INFINITY;
            }
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            ServerPlayer owner = ((CompanionEntity) companion).serverPlayerOwner();
            if (owner == null || !owner.isAlive()) return Float.NEGATIVE_INFINITY;
            if (sp.serverLevel() != owner.serverLevel()) return PRIORITY;

            double distance = sp.distanceTo(owner);
            if (following && distance > STOP_DISTANCE) return PRIORITY;
            if (failureCooldown > 0) {
                failureCooldown--;
                return Float.NEGATIVE_INFINITY;
            }
            return distance > FOLLOW_START_DISTANCE
                    ? PRIORITY : Float.NEGATIVE_INFINITY;
        } catch (Exception error) {
            System.err.println("[MineAgent] Follow priority error: " + error.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    public void tick(AgentPlayer companion) {
        ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
        ServerPlayer owner = ((CompanionEntity) companion).serverPlayerOwner();
        try {
            if (owner == null || !owner.isAlive()) {
                stopFollowing();
                return;
            }

            boolean differentDimension = sp.serverLevel() != owner.serverLevel();
            double distance = differentDimension
                    ? Double.POSITIVE_INFINITY : sp.distanceTo(owner);
            if (differentDimension || distance > RECOVERY_TELEPORT_DISTANCE) {
                nav.cancel();
                TaskContext.inputDriver(companion).clear();
                if (SafeTeleport.beside(sp, owner)) {
                    // The old PlayerNav owns a PathCaches bound to the source
                    // dimension. Build a fresh core after cross-level travel.
                    if (differentDimension) nav = createNav();
                    bodyLog.report(differentDimension
                            ? "joined the owner in their dimension"
                            : "caught up after falling far behind");
                    following = false;
                    lastOwnerGoal = null;
                } else {
                    bodyLog.report("couldn't find a safe place beside the owner");
                    failureCooldown = FAILURE_COOLDOWN_TICKS;
                    following = false;
                }
                return;
            }

            if (distance <= STOP_DISTANCE) {
                stopFollowing();
                sp.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        owner, net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES);
                return;
            }

            BlockPos ownerPos = owner.blockPosition();
            repathTicks++;
            boolean ownerMoved = lastOwnerGoal == null
                    || lastOwnerGoal.distManhattan(ownerPos) >= 3;
            if (!following || (repathTicks >= REPATH_INTERVAL_TICKS && ownerMoved)) {
                nav.navigateNear(ownerPos.getX(), ownerPos.getY(), ownerPos.getZ(), 3);
                lastOwnerGoal = ownerPos.immutable();
                repathTicks = 0;
                following = true;
            }
            nav.tick();
        } catch (Exception error) {
            System.err.println("[MineAgent] Follow tick error: " + error.getMessage());
            failureCooldown = FAILURE_COOLDOWN_TICKS;
            stopFollowing();
        }
    }

    private void stopFollowing() {
        nav.cancel();
        TaskContext.inputDriver(companion).clear();
        following = false;
        repathTicks = 0;
        lastOwnerGoal = null;
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        stopFollowing();
    }
}
