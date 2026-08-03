package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.behavior.HumanLikeNoise;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.task.TaskContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Follow the owner player in FOLLOW mode.
 *
 * <p>When the companion is in FOLLOW mode and the owner moves far away,
 * this chain actively moves the companion toward the owner — just like
 * a real player following a friend. The companion walks (not teleports)
 * toward the owner, using human-like smooth turning.
 *
 * <p><b>Priority</b>: 2.5 — above UnstuckChain (2.0), below LLM_PREEMPT_THRESHOLD (7.0).
 * This means:
 * <ul>
 *   <li>When no LLM task is running and no emergency is active, the
 *       companion follows the owner.</li>
 *   <li>When the LLM dispatches a task (move_to, mine, etc.), that task
 *       takes over and the companion stops following.</li>
 *   <li>Emergencies (suffocation, falling, hunger) always preempt.</li>
 *   <li>At 2.5, FollowChain wins against UnstuckChain (2.0) so the
 *       companion prioritizes catching up to the owner over脱困.</li>
 * </ul>
 *
 * <p><b>Distance thresholds</b>:
 * <ul>
 *   <li>Distance &gt; 12 blocks: active follow (sprint if &gt; 20)</li>
 *   <li>Distance 6-12 blocks: walk toward owner</li>
 *   <li>Distance &lt; 6 blocks: stop, just look at owner</li>
 *   <li>Distance &gt; 64 blocks: too far — teleport (configurable)</li>
 * </ul>
 *
 * <p><b>Human-like movement</b>: Uses {@link HumanLikeNoise} for smooth
 * turning (not instant snap), speed scaling based on alignment, and
 * sprinting for long distances. The companion looks like a real player
 * catching up to a friend, not a robot tracking a target.
 */
public final class FollowChain implements TaskChain {

    private static final float PRIORITY = 2.5f;

    /** Distance thresholds (in blocks). */
    private static final double FOLLOW_START_DIST = 8.0;   // start walking
    private static final double SPRINT_DIST = 20.0;         // start sprinting
    private static final double STOP_DIST = 4.0;           // close enough, stop
    private static final double TELEPORT_DIST = 64.0;       // too far, teleport

    /** Turn speed (degrees per tick) for smooth following. */
    private static final float TURN_SPEED = 12.0f;

    private final CompanionBodyLog bodyLog;
    private final PlayerNav nav;
    private int retargetTicks;
    private BlockPos lastNavTarget;

    public FollowChain(AgentPlayer companion) {
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
        PlayerNav createdNav;
        try {
            createdNav = new PlayerNav(companion, TaskContext.navCaches(companion));
        } catch (RuntimeException unavailable) {
            // Preserve a direct-input fallback during incomplete lifecycle
            // construction; normal spawned companions always have caches.
            createdNav = null;
        }
        this.nav = createdNav;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            // Only active in FOLLOW mode
            var mode = MineAgentEngine.getCompanionMode(companion.companionId());
            if (mode != MineAgentEngine.CompanionMode.FOLLOW) {
                return Float.NEGATIVE_INFINITY;
            }

            // Check distance to owner
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            ServerPlayer owner = ((CompanionEntity) companion).serverPlayerOwner();
            if (owner == null || !owner.isAlive()) {
                return Float.NEGATIVE_INFINITY;
            }

            // Must be in the same dimension
            if (!sp.level().dimension().equals(owner.level().dimension())) {
                // Different dimension — can't follow normally.
                // Return high priority so onTick teleports.
                return PRIORITY;
            }

            double dist = sp.position().distanceTo(owner.position());

            // Too far — teleport to owner (only in FOLLOW mode)
            if (dist > TELEPORT_DIST) {
                return SurvivalDecisions.followPriority(dist, true);
            }

            // Far enough to follow
            if (dist > FOLLOW_START_DIST
                    || (nav != null && nav.isNavigating() && dist > STOP_DIST)) {
                // Normal follow must stay below Unstuck, otherwise repeatedly
                // walking into a wall prevents the escape chain from winning.
                // Keep an already-running path alive down to STOP_DIST; the
                // old >8-only bid interrupted its own navigator before the
                // documented four-block following distance was ever reached.
                return Math.min(1.5f, SurvivalDecisions.followPriority(dist, true));
            }

            // Close enough — don't follow
            return Float.NEGATIVE_INFINITY;
        } catch (Exception e) {
            System.err.println("[MineAgent] Follow getPriority error: " + e.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    public void tick(AgentPlayer companion) {
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            ServerPlayer owner = ((CompanionEntity) companion).serverPlayerOwner();

            if (owner == null) {
                if (nav != null) nav.cancel();
                input.clear();
                return;
            }

            // Different dimension — teleport
            if (!sp.level().dimension().equals(owner.level().dimension())) {
                if (nav != null) nav.cancel();
                input.clear();
                teleportNearOwner(sp, owner);
                return;
            }

            Vec3 spPos = sp.position();
            Vec3 ownerPos = owner.position();
            double dist = spPos.distanceTo(ownerPos);

            // Too far — teleport to owner
            if (dist > TELEPORT_DIST) {
                if (nav != null) nav.cancel();
                input.clear();
                teleportNearOwner(sp, owner);
                bodyLog.report("teleported to owner (was too far away)");
                return;
            }

            // Close enough — stop and look at owner
            if (dist <= STOP_DIST) {
                if (nav != null) nav.cancel();
                input.clear();
                retargetTicks = 0;
                lastNavTarget = null;
                // Smoothly turn to face the owner
                turnToward(sp, ownerPos, 0.5f);
                return;
            }

            if (nav != null) {
                // Re-target periodically so moving owners are followed through
                // the executor's real jump, clearing, bridge and pillar moves.
                // Do not reset SEARCH/EXECUTE every 20 ticks when the owner has
                // not actually moved; that discarded long sliced A* searches
                // and could keep the companion permanently in SEARCH.
                BlockPos ownerBlock = owner.blockPosition();
                retargetTicks++;
                boolean targetMoved = lastNavTarget == null
                        || lastNavTarget.distManhattan(ownerBlock) >= 3;
                if (!nav.isNavigating() || (retargetTicks >= 20 && targetMoved)) {
                    nav.navigateNear(ownerBlock.getX(), ownerBlock.getY(),
                            ownerBlock.getZ(), (int) STOP_DIST);
                    lastNavTarget = ownerBlock.immutable();
                    retargetTicks = 0;
                }
                nav.tick();
                return;
            }

            // Walk/sprint toward owner
            double dx = ownerPos.x - spPos.x;
            double dz = ownerPos.z - spPos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            // Smooth turning toward owner using HumanLikeNoise
            turnToward(sp, ownerPos, TURN_SPEED);

            // Move forward — speed based on distance
            boolean shouldSprint = dist > SPRINT_DIST;
            float speed = shouldSprint ? 1.0f : 0.8f;

            // Only apply forward movement if roughly facing the target
            float yawDiff = Math.abs(wrapAngle(
                    targetYaw(spPos, ownerPos) - sp.getYRot()));
            if (yawDiff < 60.0f) {
                input.setForward(speed);
                input.setSprinting(shouldSprint);
                input.setJumping(false);
            } else {
                // Too far off-axis — slow down to turn
                input.setForward(0.2f);
                input.setSprinting(false);
            }

            // Jump if there's a 1-block-high obstacle in the way, like a
            // real player hopping over a small ledge/slab.
            //
            // Why we check the block ABOVE the obstacle instead of a Y range:
            //   sp.blockPosition().relative(horizontalDirection) always keeps
            //   the same Y (relative() only changes X/Z for horizontal dirs).
            //   So the previous condition `frontPos.getY() <= feet.getY() + 1`
            //   was ALWAYS true (frontPos.getY() == feet.getY()) — dead code
            //   that would also try to jump 2-block-high walls (impossible).
            //
            // Correct jump conditions:
            //   1. front block (feet level, in front) is solid — there IS an
            //      obstacle to jump over.
            //   2. block above the obstacle is clear — the obstacle is exactly
            //      1 block high (a 2-block wall is NOT jumpable, so don't try;
            //      trying would just bounce in place and stall progress).
            //   3. headroom: the block 2 above the companion's feet is clear —
            //      otherwise jumping would slam the companion's head into the
            //      ceiling (e.g. under a 2-high tunnel), making things worse.
            if (horizontalDist > 1.0) {
                var feetPos = sp.blockPosition();
                var frontPos = feetPos.relative(sp.getDirection());
                var frontState = sp.level().getBlockState(frontPos);
                var frontAboveState = sp.level().getBlockState(frontPos.above());
                var headAboveState = sp.level().getBlockState(feetPos.above(2));
                if (isBlocking(frontState)
                        && !isBlocking(frontAboveState)
                        && !isBlocking(headAboveState)) {
                    input.setJumping(true);
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Follow tick error: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        try {
            if (nav != null) nav.cancel();
            InputDriver input = inputDriver(companion);
            input.clear();
            retargetTicks = 0;
            lastNavTarget = null;
        } catch (Exception ignored) {}
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }

    /** Smoothly turn the player toward a target position. */
    private static void turnToward(ServerPlayer sp, Vec3 target, float maxTurn) {
        Vec3 pos = sp.position();
        float targetYaw = targetYaw(pos, target);
        float currentYaw = sp.getYRot();
        float diff = wrapAngle(targetYaw - currentYaw);
        // Use HumanLikeNoise for smooth, natural turning
        float turn = HumanLikeNoise.adaptiveTurn(diff, maxTurn);
        sp.setYRot(wrapAngle(currentYaw + turn));
        sp.setXRot(0);  // look straight ahead while walking
    }

    /** Compute the yaw angle to face the target from a position. */
    private static float targetYaw(Vec3 from, Vec3 target) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Wrap an angle to the range [-180, 180]. */
    private static float wrapAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    /** Check if a block state is solid (blocking movement). */
    @SuppressWarnings("deprecation")
    private static boolean isBlocking(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return false;
        if (state.isAir()) return false;
        return state.blocksMotion();
    }

    /**
     * Teleport beside the owner into a two-block-tall, supported cell.
     * Teleporting to the owner's exact coordinates overlaps both player
     * hitboxes and can immediately cause collision pushing or suffocation.
     */
    private static void teleportNearOwner(ServerPlayer companion, ServerPlayer owner) {
        // Never fall back to the owner's exact coordinates: overlapping
        // player hitboxes can push the companion into a wall. If no valid
        // loaded cell exists, leave it in place and retry on a later bid.
        com.mineagent.engine.entity.SafeTeleport.beside(companion, owner);
    }
}
