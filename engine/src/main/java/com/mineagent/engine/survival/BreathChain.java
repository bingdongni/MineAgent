package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.BlockDigger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Auto surface for air — when the companion's air supply drops critically low,
 * swim upward to the surface. Handles breaking ice blocks that may be above.
 *
 * <p>Priority: 6 (second only to MLG)
 */
public final class BreathChain implements TaskChain {

    // Raised to 8.0 so it can preempt LLM tasks (suffocation is life-threatening).
    // See PriorityAuction.LLM_PREEMPT_THRESHOLD (7.0) — must exceed it.
    private static final float PRIORITY = SurvivalDecisions.BREATH;
    private static final int AIR_THRESHOLD = 60;  // Three seconds at 20 TPS

    private final CompanionBodyLog bodyLog;
    private final AgentPlayer companion;

    private enum Phase { IDLE, SWIMMING_UP, BREAKING_ICE, SURFACED }
    private Phase phase = Phase.IDLE;
    private int swimTicks = 0;
    private BlockPos activeBreakTarget;
    private int breakTicks;
    private int breakTimeoutTicks;

    public BreathChain(AgentPlayer companion) {
        this.companion = companion;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() {
        return "breath";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            // If already in the swimming phase, keep going
            if (phase == Phase.SWIMMING_UP || phase == Phase.BREAKING_ICE
                    || phase == Phase.SURFACED) {
                // SURFACED is an active recovery phase, not an idle marker.
                // Dropping the bid here made PriorityAuction interrupt this
                // chain on the next tick, so the intended 40-tick air refill
                // window below was unreachable and reset immediately.
                return PRIORITY;
            }

            int air = companion.airSupply();
            if (air < AIR_THRESHOLD && companion.isInWater()) {
                return PRIORITY;
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Breath getPriority error: " + e.getMessage());
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(AgentPlayer companion) {
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();

            switch (phase) {
                case IDLE -> {
                    bodyLog.report("running low on air, swimming to the surface");
                    phase = Phase.SWIMMING_UP;
                    swimTicks = 0;
                }
                case SWIMMING_UP -> {
                    swimTicks++;

                    // Move upward
                    input.setForward(0.0f);
                    input.setJumping(true);

                    // Check if there's ice above us at head level
                    BlockPos headPos = findIceAbove(sp);
                    if (headPos != null) {
                        phase = Phase.BREAKING_ICE;
                        breakTicks = 0;
                        breakTimeoutTicks = BlockDigger.expectedBreakTicks(sp, headPos);
                        break;
                    }

                    // Check if we've surfaced (head is in air or above water)
                    if (isAtSurface(sp)) {
                        phase = Phase.SURFACED;
                        swimTicks = 0;
                        bodyLog.report("reached the surface and can breathe again");
                    }

                    // Safety: max 200 ticks (10 seconds) swimming up
                    if (swimTicks > 200) {
                        bodyLog.report("couldn't reach the surface in time");
                        reset();
                    }
                }
                case BREAKING_ICE -> {
                    input.setJumping(true);
                    BlockPos headPos = activeBreakTarget != null
                            ? activeBreakTarget : findIceAbove(sp);
                    BlockState headBlock = headPos != null
                            ? sp.level().getBlockState(headPos) : null;

                    // Check if ice was broken (now air or water)
                    if (headPos == null || headBlock.isAir()
                            || !isIce(headBlock)) {
                        activeBreakTarget = null;
                        bodyLog.report("broke through ice to reach air");
                        phase = Phase.SWIMMING_UP;
                        swimTicks = 0;
                        break;
                    }

                    // START once and let ServerPlayerGameMode.tick() advance
                    // real hardness. Reissuing START every tick can reset or
                    // conflict with the server's destroy state machine.
                    if (activeBreakTarget == null || !activeBreakTarget.equals(headPos)) {
                        abortActiveBreak(sp);
                        if (BlockDigger.startBreaking(sp, headPos)) {
                            activeBreakTarget = headPos;
                        }
                    }

                    // Safety timeout
                    breakTicks++;
                    if (breakTicks > breakTimeoutTicks) {
                        abortActiveBreak(sp);
                        phase = Phase.SWIMMING_UP;
                        breakTicks = 0;
                    }
                }
                case SURFACED -> {
                    swimTicks++;
                    input.clear();

                    // Stay at surface for a few ticks to refill air
                    if (swimTicks > 40 || companion.airSupply() >= 280) {
                        reset();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Breath tick error: " + e.getMessage());
            reset();
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        reset();
    }

    private void reset() {
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            abortActiveBreak(sp);
            inputDriver(companion).clear();
        } catch (Exception ignored) {
        }
        phase = Phase.IDLE;
        swimTicks = 0;
        breakTicks = 0;
        breakTimeoutTicks = Integer.MAX_VALUE;
    }

    private void abortActiveBreak(ServerPlayer sp) {
        if (activeBreakTarget != null) {
            BlockDigger.abortBreaking(sp, activeBreakTarget);
            activeBreakTarget = null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }

    /** Check if the companion's head is above water / in air. */
    private static boolean isAtSurface(ServerPlayer player) {
        // If no longer in water at eye level, we've surfaced
        return !player.isUnderWater() || player.getAirSupply() >= 280;
    }

    private static BlockPos findIceAbove(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos candidate = feet.above(dy);
            if (isIce(player.level().getBlockState(candidate))) return candidate;
        }
        return null;
    }

    private static boolean isIce(BlockState state) {
        return state != null && (state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE));
    }
}
