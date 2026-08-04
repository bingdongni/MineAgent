package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.BlockDigger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Auto escape when stuck — detects when the companion has been at the same
 * position for too long while trying to move, and attempts to escape like a
 * real human player would.
 *
 * <p><b>Design philosophy:</b> A real player never teleports out of being
 * stuck. They look around, break the block in their way, place a block to
 * step up, or back up and try a different direction. This chain replicates
 * that behavior. There is NO teleport — not upward, not to the owner.
 * If all human-like strategies fail, the chain simply gives up and lets the
 * LLM's higher-level reasoning take over (it will see "I'm stuck" in the
 * body log and can decide to place blocks, dig, or pick a different goal).
 *
 * <p>Escape phases (progressive, like a real player):
 * <ol>
 *   <li><b>WIGGLE</b> — jump + strafe left/right, try to unstick from
 *       minor obstacles (cobweb, fence corner, etc.). 30 ticks.</li>
 *   <li><b>BREAK</b> — break blocks blocking the head/feet/front, then
 *       walk through. 40 ticks.</li>
 *   <li><b>BACK_AWAY</b> — back up 3 blocks and try a different direction.
 *       20 ticks.</li>
 *   <li><b>GIVE_UP</b> — stop trying, report to LLM, let it decide.</li>
 * </ol>
 *
 * <p>Priority: 2 (lowest instinct, above LLM's 0)
 */
public final class UnstuckChain implements TaskChain {

    private static final float PRIORITY = 2.0f;

    /**
     * Cooldown (in ticks) applied after a normal trigger. Prevents
     * re-triggering for 30 seconds — this covers the case where the
     * companion was waiting for an LLM response (not actually stuck)
     * and samePosTicks accumulated during the LLM task. Without this,
     * the moment the LLM task finishes the chain would fire and start
     * an unnecessary escape sequence. (30s at 20 TPS = 600 ticks)
     */
    private static final long TRIGGER_COOLDOWN_TICKS = 600;

    /**
     * Cooldown (in ticks) applied after GIVE_UP. After a failed escape,
     * samePosTicks may still be high (the companion didn't actually move
     * during the escape attempts), so without a long cooldown this chain
     * would re-fire immediately and loop forever: trigger → escape →
     * GIVE_UP → trigger → ... The 60-second cooldown gives the LLM time
     * to read the "I'm stuck" body log message and react (place blocks,
     * dig, or pick a different goal). (60s at 20 TPS = 1200 ticks)
     */
    private static final long GIVE_UP_COOLDOWN_TICKS = 1200;

    private final SurvivalConfig config;
    private final CompanionBodyLog bodyLog;
    private final AgentPlayer companion;

    /**
     * Rolling-window stuck detector (borrowed from numen).
     *
     * <p>Replaces the old {@code samePosTicks} counter. The detector only
     * counts ticks where the companion was actively trying to move (non-zero
     * input), so LLM thinking pauses and idle periods don't trigger false
     * positives.
     */
    private final UnstuckDetector stuckDetector;

    private enum Phase { IDLE, WIGGLE, BREAK, BACK_AWAY, GIVE_UP }
    private Phase phase = Phase.IDLE;
    private int stuckTicks = 0;
    private int actionTicks = 0;
    private Vec3 lastPosition = null;
    /** The direction we were trying to move when stuck (for BACK_AWAY). */
    private Direction stuckFacing = Direction.NORTH;
    private float escapeYaw;
    private BlockPos activeBreakTarget;
    private int activeBreakTimeout;
    private int activeBreakTicks;

    /**
     * Last recorded movement input state — used to feed the stuck detector.
     * Updated in getPriority() each tick.
     */
    private boolean lastTryingToMove = false;

    /**
     * Monotonic tick counter, incremented once per {@link #getPriority}
     * call (which happens exactly once per server tick per chain).
     * Used as the time base for the cooldown mechanism.
     */
    private long currentTick = 0;

    /**
     * The tick up to which this chain is in cooldown. While
     * {@code currentTick < cooldownUntil}, {@link #getPriority} returns
     * {@link Float#NEGATIVE_INFINITY} (dormant). This unifies both the
     * post-trigger cooldown and the post-GIVE_UP cooldown.
     *
     * <p>Important: the cooldown check is placed AFTER the active-phase
     * check, so an in-progress escape sequence (WIGGLE/BREAK/BACK_AWAY/
     * GIVE_UP) is never cut off by the cooldown.
     */
    private long cooldownUntil = 0;

    public UnstuckChain(AgentPlayer companion, SurvivalConfig config) {
        this.companion = companion;
        this.config = config;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
        this.stuckDetector = new UnstuckDetector(config.stuckTimeTicks());
    }

    @Override
    public String name() {
        return "unstuck";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            // If actively escaping, maintain priority. This check MUST come
            // before the cooldown check below, otherwise an in-progress
            // escape sequence (WIGGLE/BREAK/BACK_AWAY/GIVE_UP) would be
            // cut off the moment the post-trigger cooldown starts.
            if (phase == Phase.WIGGLE || phase == Phase.BREAK
                    || phase == Phase.BACK_AWAY || phase == Phase.GIVE_UP) {
                return PRIORITY;
            }

            currentTick++;

            // Cooldown after a recent trigger or after GIVE_UP. This is the
            // fix for problems 1 & 2:
            //  - Problem 1: while an LLM task was running, getPriority() kept
            //    being called every tick and accumulated samePosTicks (the
            //    companion wasn't moving because it was waiting for the LLM
            //    response). The instant the LLM task finished, samePosTicks
            //    was already > threshold and the chain fired a false escape.
            //    The post-trigger cooldown prevents re-firing for 30s.
            //  - Problem 2: after GIVE_UP, samePosTicks is still high (the
            //    companion didn't move during the escape), so without a
            //    long cooldown the chain would loop forever. The GIVE_UP
            //    phase sets a 60s cooldown (see tick()).
            if (currentTick < cooldownUntil) {
                return Float.NEGATIVE_INFINITY;
            }

            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            Vec3 currentPos = sp.position();

            // Detect "trying to move" by checking the player's movement input
            // fields directly. These are the same fields that
            // CompanionInputDriver sets (via access widener on fabric;
            // reflection on neoforge where no access widener is configured).
            // - zza = forward/backward input (W/S)
            // - xxa = strafe left/right input (A/D)
            // - jumping = jump key pressed (accessed via reflection to keep
            //   this class portable across the neoforge module, which does not
            //   apply the mineagent.accesswidener)
            // If any are non-zero, the companion is actively trying to move.
            // If all are zero, it's intentionally idle (LLM thinking, waiting,
            // etc.) and shouldn't count as stuck.
            boolean tryingToMove = sp.zza != 0.0f
                    || sp.xxa != 0.0f
                    || isJumping(sp);
            lastTryingToMove = tryingToMove;

            // Feed the rolling-window stuck detector.
            // The detector only counts ticks where tryingToMove == true,
            // so LLM thinking pauses and idle periods don't trigger false
            // positives. This replaces the old samePosTicks counter.
            stuckDetector.record(tryingToMove, currentPos.x, currentPos.z);

            lastPosition = currentPos;

            // Trigger if the detector reports stuck
            if (stuckDetector.isStuck()) {
                // Enter cooldown so we don't immediately re-trigger after
                // the escape sequence ends (even on a successful escape,
                // a 30s quiet period prevents oscillation).
                cooldownUntil = currentTick + TRIGGER_COOLDOWN_TICKS;
                return PRIORITY;
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Unstuck getPriority error: " + e.getMessage());
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
                    // Record which direction we're facing when stuck —
                    // we'll try breaking that way first, then back away.
                    stuckFacing = sp.getDirection();
                    bodyLog.report("stuck in place, trying to wiggle free");
                    phase = Phase.WIGGLE;
                    actionTicks = 0;
                }
                case WIGGLE -> {
                    actionTicks++;

                    // Human-like wiggle: try jumping + moving in different
                    // directions to unstick from minor obstacles.
                    // Phase 1 (ticks 0-10): jump + forward
                    // Phase 2 (ticks 10-20): jump + strafe left
                    // Phase 3 (ticks 20-30): jump + strafe right
                    input.setJumping(true);
                    if (actionTicks <= 10) {
                        input.setForward(1.0f);
                    } else if (actionTicks <= 20) {
                        input.setForward(0.5f);
                        input.setStrafe(1.0f);  // strafe left
                    } else {
                        input.setForward(0.5f);
                        input.setStrafe(-1.0f); // strafe right
                    }

                    // Check if we've moved HORIZONTALLY (not vertically).
                    // Using 3D distance was a bug on cliff terrain: jumping
                    // up a 1-block step moves Y by 1.0, falsely satisfying
                    // the escape condition and causing the companion to climb
                    // to the sky. Only horizontal progress counts as "free".
                    Vec3 pos = sp.position();
                    if (lastPosition != null) {
                        double dx = pos.x - lastPosition.x;
                        double dz = pos.z - lastPosition.z;
                        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                        if (horizontalDist > 1.0) {
                            bodyLog.report("wiggle free");
                            reset();
                            return;
                        }
                    }

                    // After 30 ticks of wiggling, try breaking blocks
                    if (actionTicks > 30) {
                        phase = Phase.BREAK;
                        actionTicks = 0;
                        bodyLog.report("wiggle didn't work, breaking blocks in the way");
                    }
                }
                case BREAK -> {
                    actionTicks++;

                    if (activeBreakTarget != null) {
                        activeBreakTicks++;
                        if (!isBlocking(sp.level().getBlockState(activeBreakTarget))) {
                            activeBreakTarget = null;
                            activeBreakTicks = 0;
                            activeBreakTimeout = 0;
                        } else if (activeBreakTicks > activeBreakTimeout) {
                            abortActiveBreak(sp);
                        }
                    }

                    // Break blocks that are blocking us, like a real player
                    // would when stuck in terrain. Try head, front, then feet.
                    BlockPos headPos = sp.blockPosition().above();
                    BlockState headState = sp.level().getBlockState(headPos);

                    BlockPos frontPos = sp.blockPosition().relative(stuckFacing);
                    BlockState frontState = sp.level().getBlockState(frontPos);

                    BlockPos feetPos = sp.blockPosition();
                    BlockState feetState = sp.level().getBlockState(feetPos);

                    // Determine which block to break (priority: head > front > feet)
                    if (isBlocking(headState)) {
                        // Look at the head block and break it
                        sp.setXRot(-90);  // look straight up
                        startOrContinueBreak(sp, headPos);
                    } else if (isBlocking(frontState)) {
                        // Look at the front block and break it
                        sp.setXRot(0);    // look straight ahead
                        startOrContinueBreak(sp, frontPos);
                    } else if (isBlocking(feetState)) {
                        // Break the block at feet level (cobweb, etc.)
                        sp.setXRot(90);   // look straight down
                        startOrContinueBreak(sp, feetPos);
                    }

                    // Try jumping while breaking (real players do this)
                    input.setJumping(true);
                    input.setForward(0.5f);

                    // Check if free — HORIZONTAL distance only
                    Vec3 pos = sp.position();
                    if (lastPosition != null) {
                        double dx = pos.x - lastPosition.x;
                        double dz = pos.z - lastPosition.z;
                        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                        if (horizontalDist > 1.0) {
                            bodyLog.report("broke free");
                            reset();
                            return;
                        }
                    }

                    // After 40 ticks of breaking, try backing away
                    if (actionTicks > Math.max(80, activeBreakTimeout)) {
                        abortActiveBreak(sp);
                        phase = Phase.BACK_AWAY;
                        actionTicks = 0;
                        escapeYaw = stuckFacing.getOpposite().toYRot();
                        bodyLog.report("can't break free, backing up to try another way");
                    }
                }
                case BACK_AWAY -> {
                    actionTicks++;

                    // Back away from the stuck direction — like a player
                    // backing out of a dead end to try a different path.
                    // Turn around and walk backward.
                    sp.setYRot(escapeYaw);
                    input.setForward(0.8f);
                    input.setJumping(false);

                    // Check if we've moved HORIZONTALLY
                    Vec3 pos = sp.position();
                    if (lastPosition != null) {
                        double dx = pos.x - lastPosition.x;
                        double dz = pos.z - lastPosition.z;
                        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                        if (horizontalDist > 2.0) {
                            bodyLog.report("backed out, trying a new direction");
                            reset();
                            return;
                        }
                    }

                    // After 20 ticks of backing away, give up and let the
                    // LLM decide what to do next (it will see the body log
                    // and can choose to place blocks, dig, or change goals).
                    if (actionTicks > 20) {
                        phase = Phase.GIVE_UP;
                        actionTicks = 0;
                    }
                }
                case GIVE_UP -> {
                    // Stop all input — let the LLM's higher-level reasoning
                    // take over. It will see the body log messages and can
                    // decide to place blocks to step up, dig through, or
                    // pick a completely different goal.
                    input.clear();
                    bodyLog.report("stuck and can't escape with basic moves — "
                            + "need to place blocks or dig, letting me decide");
                    // Extended cooldown after giving up. samePosTicks is reset
                    // by reset() below, but lastPosition is kept — and since
                    // the companion didn't actually move during the escape,
                    // samePosTicks would immediately re-accumulate past the
                    // threshold and re-trigger this chain, looping forever.
                    // The 60-second cooldown gives the LLM a real chance to
                    // read the body log and react (problem 2 fix).
                    cooldownUntil = currentTick + GIVE_UP_COOLDOWN_TICKS;
                    reset();
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Unstuck tick error: " + e.getMessage());
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
            ((CompanionEntity) companion).inputDriver().clear();
        } catch (Exception ignored) {
            // Cleanup can race entity teardown during despawn.
        }
        phase = Phase.IDLE;
        stuckTicks = 0;
        actionTicks = 0;
        stuckDetector.reset();
        // Keep lastPosition for next detection cycle
    }

    private void startOrContinueBreak(ServerPlayer sp, BlockPos target) {
        if (target == null || !isBlocking(sp.level().getBlockState(target))) return;
        if (target.equals(activeBreakTarget)) return;
        abortActiveBreak(sp);
        int expected = BlockDigger.expectedBreakTicks(sp, target);
        if (expected == Integer.MAX_VALUE) return;
        // Unstuck is a short recovery policy, not an authorization to spend
        // minutes mining a hard valuable block. Higher-level planning can
        // choose a deliberate route if this bounded attempt fails.
        activeBreakTimeout = Math.min(240, Math.max(40, expected));
        if (BlockDigger.startBreaking(sp, target)) {
            activeBreakTarget = target.immutable();
            activeBreakTicks = 0;
        }
    }

    private void abortActiveBreak(ServerPlayer sp) {
        if (activeBreakTarget != null) {
            BlockDigger.abortBreaking(sp, activeBreakTarget);
        }
        activeBreakTarget = null;
        activeBreakTicks = 0;
        activeBreakTimeout = 0;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }

    /** Check if a block state is blocking (solid or cobweb). */
    @SuppressWarnings("deprecation")
    private static boolean isBlocking(BlockState state) {
        if (state == null) return false;
        if (state.isAir()) return false;
        // Solid blocks and cobwebs are blocking
        return state.blocksMotion() || state.is(net.minecraft.tags.BlockTags.WOOL)
                || state.is(net.minecraft.world.level.block.Blocks.COBWEB);
    }

    /**
     * Read {@code LivingEntity.jumping} via reflection.
     *
     * <p>The {@code jumping} field is {@code protected} in vanilla and is
     * exposed via the mineagent access widener on the Fabric module. The
     * NeoForge module, however, does not apply an access widener, so a
     * direct field read would fail to compile there. Reflection keeps this
     * class portable across both modules without requiring two source
     * trees. This mirrors the approach already used for
     * {@code Player.tabListName} in MineAgentEngine.
     *
     * <p>Cached the {@link Field} handle once on first use; the cost per
     * tick is a single {@code getBoolean} on a {@code Field} with
     * {@code setAccessible(true)} already applied, which is negligible.
     */
    private static java.lang.reflect.Field JUMPING_FIELD_CACHE;

    private static boolean isJumping(ServerPlayer sp) {
        try {
            java.lang.reflect.Field f = JUMPING_FIELD_CACHE;
            if (f == null) {
                // Walk the class hierarchy: jumping is declared on
                // LivingEntity (the superclass of Player → ServerPlayer),
                // not on ServerPlayer itself.
                Class<?> cls = sp.getClass();
                while (cls != null && cls != Object.class) {
                    try {
                        f = cls.getDeclaredField("jumping");
                        break;
                    } catch (NoSuchFieldException ignored) {
                        cls = cls.getSuperclass();
                    }
                }
                if (f == null) {
                    // Fallback: no jumping field found — assume not jumping
                    return false;
                }
                f.setAccessible(true);
                JUMPING_FIELD_CACHE = f;
            }
            return f.getBoolean(sp);
        } catch (Throwable t) {
            // Reflection failures should never crash the unstuck detector
            return false;
        }
    }
}
