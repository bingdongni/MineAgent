package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.task.BlockDigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Surfaces when air is low and progressively clears a reachable roof. */
public final class BreathChain implements TaskChain {

    private static final float PRIORITY = 8.0f;
    private static final int AIR_THRESHOLD = 80;
    private static final int MAX_ESCAPE_TICKS = 240;

    private final AgentPlayer companion;
    private final CompanionBodyLog bodyLog;

    private enum Phase { IDLE, SWIMMING_UP, BREAKING_ROOF }
    private Phase phase = Phase.IDLE;
    private int escapeTicks;
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
            return sp.isUnderWater() && companion.airSupply() < AIR_THRESHOLD
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
                phase = Phase.SWIMMING_UP;
                escapeTicks = 0;
                bodyLog.report("running low on air, swimming upward");
            }

            if (!sp.isUnderWater() || companion.airSupply() >= 280) {
                bodyLog.report("reached breathable air");
                cleanup(sp);
                return;
            }
            if (++escapeTicks > MAX_ESCAPE_TICKS) {
                bodyLog.report("couldn't reach breathable air before the escape timeout");
                cleanup(sp);
                return;
            }

            var input = ((CompanionEntity) companion).inputDriver();
            input.setForward(0.0f);
            input.setStrafe(0.0f);
            input.setSprinting(false);
            input.setJumping(true);

            if (phase == Phase.BREAKING_ROOF) {
                tickRoofBreak(sp);
                return;
            }

            BlockPos roof = firstBlockingBlockAbove(sp);
            if (roof != null && BlockDigger.canBreak(sp, roof)) {
                breakTimeout = BlockDigger.expectedBreakTicks(sp, roof);
                if (BlockDigger.startBreaking(sp, roof)) {
                    breakTarget = roof;
                    breakTicks = 0;
                    phase = Phase.BREAKING_ROOF;
                    bodyLog.report("clearing the block above to reach air");
                }
            }
        } catch (Exception error) {
            System.err.println("[MineAgent] Breath tick error: " + error.getMessage());
            cleanup(sp);
        }
    }

    private void tickRoofBreak(ServerPlayer sp) {
        if (breakTarget == null) {
            phase = Phase.SWIMMING_UP;
            return;
        }
        if (BlockHelper.isPassable(sp.level().getBlockState(breakTarget))) {
            breakTarget = null;
            breakTicks = 0;
            phase = Phase.SWIMMING_UP;
            bodyLog.report("cleared a route toward the surface");
            return;
        }
        if (++breakTicks > breakTimeout) {
            BlockDigger.abortBreaking(sp, breakTarget);
            bodyLog.report("the roof block could not be broken in time");
            cleanup(sp);
        }
    }

    /** Return the first non-passable block close enough to obstruct ascent. */
    private static BlockPos firstBlockingBlockAbove(ServerPlayer sp) {
        int x = sp.blockPosition().getX();
        int z = sp.blockPosition().getZ();
        int fromY = (int) Math.floor(sp.getEyeY());
        for (int y = fromY; y <= fromY + 3; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = sp.level().getBlockState(pos);
            if (BlockHelper.isPassable(state)) continue;
            // Do not target a distant ceiling before the body can see/reach
            // it; the swim input will move the player closer on later ticks.
            return BlockDigger.canBreak(sp, pos) ? pos : null;
        }
        return null;
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        cleanup(((CompanionEntity) companion).serverPlayer());
    }

    private void cleanup(ServerPlayer sp) {
        if (breakTarget != null) BlockDigger.abortBreaking(sp, breakTarget);
        ((CompanionEntity) companion).inputDriver().clear();
        phase = Phase.IDLE;
        escapeTicks = 0;
        breakTicks = 0;
        breakTimeout = 0;
        breakTarget = null;
    }
}
