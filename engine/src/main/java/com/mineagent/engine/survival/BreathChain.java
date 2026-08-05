package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.task.BlockDigger;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Emergency breath controller that keeps body ownership until the eyes reach air. */
public final class BreathChain implements TaskChain {

    /** Below MLG, but above every other survival chain and all LLM tasks. */
    private static final float PRIORITY = 9.0f;
    /** Seven seconds of nominal air leaves time to route around a blocked shaft. */
    private static final int AIR_THRESHOLD = 140;
    private static final int ROUTE_REFRESH_TICKS = 10;
    private static final int MAX_ROUTE_NODES = 4_096;
    private static final int MAX_HORIZONTAL_RADIUS = 10;
    private static final int MAX_UPWARD_SEARCH = 20;
    private static final int MAX_DOWNWARD_SEARCH = 4;
    private static final int STAGNANT_TICKS_BEFORE_SWIM_ASSIST = 12;
    private static final int ESCAPE_DIAGNOSTIC_INTERVAL = 200;

    private final AgentPlayer companion;
    private final CompanionBodyLog bodyLog;

    private enum Phase { IDLE, SWIMMING, BREAKING_ROOF }
    private Phase phase = Phase.IDLE;
    private int escapeTicks;
    private int routeRefreshTicks;
    private int routeIndex;
    private int stagnantTicks;
    private double bestWaypointDistance = Double.POSITIVE_INFINITY;
    private List<BlockPos> escapeRoute = List.of();
    private int breakTicks;
    private int breakTimeout;
    private BlockPos breakTarget;

    public BreathChain(AgentPlayer companion) {
        this.companion = companion;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() { return "breath"; }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            if (phase != Phase.IDLE) return PRIORITY;
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            // Air supply is authoritative, but requiring the eyes to be in
            // water avoids claiming the body while air is already regenerating.
            return sp.isEyeInFluid(FluidTags.WATER)
                    && sp.getAirSupply() < AIR_THRESHOLD
                    ? PRIORITY : Float.NEGATIVE_INFINITY;
        } catch (Exception error) {
            System.err.println("[MineAgent] Breath priority error: " + error.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    public void tick(AgentPlayer companion) {
        ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
        try {
            if (phase == Phase.IDLE) {
                phase = Phase.SWIMMING;
                escapeTicks = 0;
                routeRefreshTicks = 0;
                bodyLog.report("low air detected; the breath controller took body control");
            }

            // Reaching air, rather than an arbitrary timeout, is the only
            // successful terminal condition. The old 240-tick timeout released
            // the body while it was still drowning and reset all escape work.
            if (!sp.isEyeInFluid(FluidTags.WATER)) {
                bodyLog.report("reached breathable air");
                cleanup(sp);
                return;
            }

            escapeTicks++;
            if (escapeTicks % ESCAPE_DIAGNOSTIC_INTERVAL == 0) {
                bodyLog.report("still submerged; continuing the deterministic escape instead of yielding to the LLM");
            }

            var input = ((CompanionEntity) companion).inputDriver();
            input.setForward(0.0f);
            input.setStrafe(0.0f);
            input.setSneaking(false);
            input.setSprinting(false);
            input.setJumping(true);

            if (phase == Phase.BREAKING_ROOF) {
                tickRoofBreak(sp);
                return;
            }

            if (escapeRoute.isEmpty() || --routeRefreshTicks <= 0) {
                refreshEscapeRoute(sp);
            }
            if (driveRoute(sp)) return;

            // No open-water route was found. Clear only the first block that
            // physically seals the current ascent column, through the same
            // vanilla progressive break state used by normal mining tasks.
            BlockPos roof = firstBlockingBlockAbove(sp);
            if (roof != null) {
                BlockDigger.BreakAssessment assessment =
                        BlockDigger.startBreakingDetailed(sp, roof);
                if (assessment.allowed()) {
                    breakTarget = roof;
                    breakTicks = 0;
                    breakTimeout = BlockDigger.expectedBreakTicks(sp, roof);
                    phase = Phase.BREAKING_ROOF;
                    bodyLog.report("no open route to air; clearing the reachable roof block");
                }
            }
        } catch (Exception error) {
            System.err.println("[MineAgent] Breath tick error: " + error.getMessage());
            // A transient targeting error must not release a drowning body.
            abortRoofBreak(sp);
            phase = Phase.SWIMMING;
            escapeRoute = List.of();
            routeRefreshTicks = 0;
        }
    }

    /** Follow a short collision-free water/air route toward a breathable eye cell. */
    private boolean driveRoute(ServerPlayer sp) {
        if (escapeRoute.isEmpty()) return false;
        BlockPos current = sp.blockPosition();
        while (routeIndex < escapeRoute.size()
                && current.equals(escapeRoute.get(routeIndex))) {
            routeIndex++;
            bestWaypointDistance = Double.POSITIVE_INFINITY;
            stagnantTicks = 0;
        }
        if (routeIndex >= escapeRoute.size()) {
            escapeRoute = List.of();
            return false;
        }

        BlockPos waypoint = escapeRoute.get(routeIndex);
        double tx = waypoint.getX() + 0.5;
        double ty = waypoint.getY() + 0.35;
        double tz = waypoint.getZ() + 0.5;
        double dx = tx - sp.getX();
        double dy = ty - sp.getY();
        double dz = tz - sp.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(horizontal * horizontal + dy * dy);

        if (distance + 0.04 < bestWaypointDistance) {
            bestWaypointDistance = distance;
            stagnantTicks = 0;
        } else {
            stagnantTicks++;
        }

        if (horizontal > 0.08) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            sp.setYRot(yaw);
            sp.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
            var input = ((CompanionEntity) companion).inputDriver();
            input.setForward(1.0f);
            input.setSprinting(true);
        } else {
            sp.lookAt(EntityAnchorArgument.Anchor.EYES,
                    new Vec3(tx, waypoint.getY() + 1.0, tz));
        }

        var input = ((CompanionEntity) companion).inputDriver();
        if (dy < -0.35) {
            input.setJumping(false);
            input.setSneaking(true);
        } else {
            input.setSneaking(false);
            input.setJumping(true);
        }

        if (stagnantTicks >= STAGNANT_TICKS_BEFORE_SWIM_ASSIST) {
            applyGuardedSwimAssist(sp, waypoint, dx, dy, dz, distance);
            stagnantTicks = 0;
        }
        return true;
    }

    /**
     * A real client sends continuous swim movement packets; this fake client
     * has no network input packet. If vanilla's jump flag makes no measurable
     * progress, add one bounded liquid-speed impulse along a BFS-validated open
     * edge. This cannot cross a solid cell and does not teleport the player.
     */
    private static void applyGuardedSwimAssist(ServerPlayer sp, BlockPos waypoint,
                                                double dx, double dy, double dz,
                                                double distance) {
        if (distance < 1.0e-4 || !canOccupy(sp, waypoint)) return;
        double scale = 0.075 / distance;
        Vec3 old = sp.getDeltaMovement();
        double vx = clamp(old.x + dx * scale, -0.16, 0.16);
        double vy = clamp(old.y + dy * scale, -0.12, 0.12);
        double vz = clamp(old.z + dz * scale, -0.16, 0.16);
        sp.setDeltaMovement(vx, vy, vz);
        sp.hurtMarked = true;
    }

    private void refreshEscapeRoute(ServerPlayer sp) {
        escapeRoute = findBreathableRoute(sp);
        routeIndex = escapeRoute.size() > 1 ? 1 : 0;
        routeRefreshTicks = ROUTE_REFRESH_TICKS;
        bestWaypointDistance = Double.POSITIVE_INFINITY;
        stagnantTicks = 0;
    }

    /** Bounded BFS through cells the player's two-block body can occupy. */
    private static List<BlockPos> findBreathableRoute(ServerPlayer sp) {
        BlockPos start = sp.blockPosition().immutable();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        open.add(start);
        parent.put(start, null);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_ROUTE_NODES) {
            BlockPos current = open.removeFirst();
            if (!current.equals(start) && isBreathable(sp, current)) {
                ArrayList<BlockPos> path = new ArrayList<>();
                for (BlockPos cursor = current; cursor != null; cursor = parent.get(cursor)) {
                    path.add(cursor);
                }
                Collections.reverse(path);
                return List.copyOf(path);
            }

            // Up first makes an equally short surface route win over needless
            // lateral/downward swimming, while BFS still finds nearby air pockets.
            for (Direction direction : new Direction[]{Direction.UP, Direction.NORTH,
                    Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
                BlockPos next = current.relative(direction).immutable();
                if (parent.containsKey(next) || !insideSearchBounds(start, next)
                        || !canOccupy(sp, next)) continue;
                parent.put(next, current);
                open.addLast(next);
            }
        }
        return List.of();
    }

    private static boolean insideSearchBounds(BlockPos start, BlockPos pos) {
        return Math.abs(pos.getX() - start.getX()) <= MAX_HORIZONTAL_RADIUS
                && Math.abs(pos.getZ() - start.getZ()) <= MAX_HORIZONTAL_RADIUS
                && pos.getY() <= start.getY() + MAX_UPWARD_SEARCH
                && pos.getY() >= start.getY() - MAX_DOWNWARD_SEARCH;
    }

    private static boolean canOccupy(ServerPlayer sp, BlockPos feet) {
        var level = sp.level();
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(feet.above());
        return level.isInWorldBounds(feet) && level.isInWorldBounds(feet.above())
                && BlockHelper.isPassable(feetState)
                && BlockHelper.isPassable(headState)
                && !feetState.getFluidState().is(FluidTags.LAVA)
                && !headState.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean isBreathable(ServerPlayer sp, BlockPos feet) {
        return canOccupy(sp, feet)
                && !sp.level().getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private void tickRoofBreak(ServerPlayer sp) {
        if (breakTarget == null) {
            phase = Phase.SWIMMING;
            return;
        }
        if (BlockHelper.isPassable(sp.level().getBlockState(breakTarget))) {
            breakTarget = null;
            breakTicks = 0;
            phase = Phase.SWIMMING;
            routeRefreshTicks = 0;
            bodyLog.report("cleared a route toward the surface");
            return;
        }

        // If another action or a correction packet dropped the progressive
        // destroy state, restart from fresh visibility evidence instead of
        // waiting until death on a break that is no longer advancing.
        if (sp.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                && !fakeMode.isAutomaticallyDestroying(breakTarget)) {
            BlockDigger.startBreakingDetailed(sp, breakTarget);
        }

        if (++breakTicks > breakTimeout + 40L) {
            bodyLog.report("roof break stopped progressing; searching for another air route");
            abortRoofBreak(sp);
            phase = Phase.SWIMMING;
            escapeRoute = List.of();
            routeRefreshTicks = 0;
        }
    }

    /** Return the first solid block close enough to obstruct direct ascent. */
    private static BlockPos firstBlockingBlockAbove(ServerPlayer sp) {
        int x = sp.blockPosition().getX();
        int z = sp.blockPosition().getZ();
        int fromY = (int) Math.floor(sp.getEyeY());
        for (int y = fromY; y <= fromY + 3; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (BlockHelper.isPassable(sp.level().getBlockState(pos))) continue;
            return BlockDigger.canBreak(sp, pos) ? pos : null;
        }
        return null;
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        cleanup(((CompanionEntity) companion).serverPlayer());
    }

    private void abortRoofBreak(ServerPlayer sp) {
        if (breakTarget != null) BlockDigger.abortBreaking(sp, breakTarget);
        breakTarget = null;
        breakTicks = 0;
        breakTimeout = 0;
    }

    private void cleanup(ServerPlayer sp) {
        abortRoofBreak(sp);
        ((CompanionEntity) companion).inputDriver().clear();
        phase = Phase.IDLE;
        escapeTicks = 0;
        routeRefreshTicks = 0;
        routeIndex = 0;
        stagnantTicks = 0;
        bestWaypointDistance = Double.POSITIVE_INFINITY;
        escapeRoute = List.of();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
