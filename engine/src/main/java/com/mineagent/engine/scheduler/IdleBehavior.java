package com.mineagent.engine.scheduler;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.server.level.ServerPlayer;

import java.util.Random;

/**
 * Manages the companion's idle behavior — what it does when no task
 * or instinct is driving it.
 *
 * <p>When the priority auction has no winner and no running task, the
 * companion would otherwise stand perfectly still, which looks robotic
 * and "frozen" (especially while the LLM is thinking). This class adds
 * subtle life-like motion that mimics a real player waiting:
 *
 * <ul>
 *   <li><b>Head yaw rotation</b>: Smoothly glances in different directions
 *       (within ±60° of current heading)</li>
 *   <li><b>Pitch variation</b>: Occasionally looks up or down — not just
 *       straight ahead. A real player checks their surroundings.</li>
 *   <li><b>Shorter intervals</b>: Direction changes every 2-5 seconds
 *       (not 5-10), so the companion looks more "alive"</li>
 *   <li><b>Occasional body shift</b>: Rarely takes a tiny step sideways
 *       or forward — like a player shuffling while waiting</li>
 *   <li><b>Arm animation triggers</b>: Occasionally swings arm (like
 *       stretching or adjusting), prevents total arm rigidity</li>
 * </ul>
 *
 * <p>This makes the companion look like it's casually looking around
 * while "thinking", rather than standing frozen. When a real task
 * takes over, the idle motion is simply overridden — no cleanup
 * needed.
 */
public class IdleBehavior {

    private final AgentPlayer companion;
    private final Random random = new Random();

    private int tickCounter = 0;
    private float targetYaw;
    private float targetPitch = 0;
    private int nextTurnTick = 0;

    /** How fast to turn while idle (degrees per tick ≈ 40°/sec). */
    private static final float TURN_SPEED = 2.0f;
    private static final float PITCH_SPEED = 1.5f;

    /** Minimum ticks between direction changes (2 seconds at 20 TPS). */
    private static final int MIN_TURN_INTERVAL = 40;

    /** Maximum additional random ticks before a direction change. */
    private static final int MAX_TURN_JITTER = 60;

    /** Ticks until the next idle arm swing (stretching, adjusting). */
    private int nextArmSwingTick = 200;

    /** Ticks until the next idle shuffle (tiny position shift). */
    private int nextShuffleTick = 300;

    public IdleBehavior(AgentPlayer companion) {
        this.companion = companion;
    }

    /**
     * Tick the idle behavior. Call this every server tick when no task
     * or instinct chain is controlling the companion.
     */
    @SuppressWarnings("deprecation")  // BlockState.blocksMotion() is deprecated in MC
    public void tick() {
        ServerPlayer sp = TaskContext.serverPlayer(companion);
        if (sp == null) return;

        tickCounter++;

        // Initialize target yaw on first tick
        if (nextTurnTick == 0) {
            targetYaw = sp.getYRot();
            targetPitch = 0;
            nextTurnTick = MIN_TURN_INTERVAL + random.nextInt(MAX_TURN_JITTER);
        }

        // Time to pick a new direction to look at?
        if (tickCounter >= nextTurnTick) {
            float currentYaw = sp.getYRot();
            // ±50° from current heading — a casual glance, not a spin
            targetYaw = currentYaw + (random.nextFloat() * 100f - 50f);
            // Random pitch: sometimes look up (-20°), sometimes down (30°),
            // mostly straight ahead. A real player checks up at trees, down
            // at the ground, etc.
            targetPitch = (random.nextFloat() * 50f - 20f);  // -20 to +30
            nextTurnTick = tickCounter + MIN_TURN_INTERVAL + random.nextInt(MAX_TURN_JITTER);
        }

        // Smoothly rotate toward target yaw
        float currentYaw = sp.getYRot();
        float yawDiff = wrapAngle(targetYaw - currentYaw);
        if (Math.abs(yawDiff) > 0.5f) {
            float turn = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, yawDiff));
            sp.setYRot(wrapAngle(currentYaw + turn));
        }

        // Smoothly rotate pitch (look up/down)
        float currentPitch = sp.getXRot();
        float pitchDiff = targetPitch - currentPitch;
        if (Math.abs(pitchDiff) > 0.5f) {
            float pitchTurn = Math.max(-PITCH_SPEED, Math.min(PITCH_SPEED, pitchDiff));
            sp.setXRot(currentPitch + pitchTurn);
        }

        // Clear movement inputs — don't drift away while idle
        sp.zza = 0;
        sp.xxa = 0;
        sp.setJumping(false);
        sp.setSprinting(false);

        // Occasional idle arm swing — like a player stretching or
        // adjusting their gear while waiting. Prevents total arm
        // rigidity.
        if (tickCounter >= nextArmSwingTick) {
            // Don't swing if sneaking or doing something
            if (!sp.isShiftKeyDown()) {
                sp.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            // Next swing in 10-20 seconds
            nextArmSwingTick = tickCounter + 200 + random.nextInt(200);
        }

        // Occasional idle shuffle — a tiny step to one side or forward.
        // This mimics a player shifting weight or repositioning slightly
        // while waiting. Very subtle (0.15 forward, 0.1 strafe, 1 tick).
        //
        // Safety: before shuffling, check that there is solid ground one
        // block ahead (in the facing direction). Without this, repeated
        // shuffles could slowly drift the companion off a cliff edge or
        // down a hole — the original code happily shuffled even when the
        // next block ahead was a sheer drop.
        //
        // Note: the previous else-branch that tried to "clear the tiny
        // input after a shuffle" was dead code — sp.zza/sp.xxa are reset
        // to 0 at the top of every tick (see the clear above), so by the
        // time we reach here they are always 0 and the
        // `sp.zza != 0 && sp.zza <= 0.2f` guard was always false. Removed.
        if (tickCounter >= nextShuffleTick) {
            var feetPos = sp.blockPosition();
            var frontPos = feetPos.relative(sp.getDirection());
            var groundBelow = sp.level().getBlockState(frontPos.below());
            // Only shuffle if there is solid ground to step onto ahead —
            // blocksMotion() returns true for full solid blocks, false for
            // air/water/lava/etc. This prevents walking off any edge.
            if (groundBelow.blocksMotion()) {
                float direction = random.nextFloat() * 2 - 1;  // -1 to 1
                sp.zza = 0.15f;  // tiny forward
                sp.xxa = direction * 0.1f;  // tiny strafe
            }
            // Schedule the next shuffle regardless of whether we actually
            // moved — if it was unsafe, we just skip silently and try
            // again later (no need to retry quickly).
            nextShuffleTick = tickCounter + 400 + random.nextInt(400);
        }
    }

    /**
     * Reset idle state. Call this when a task or instinct takes over
     * so the next idle period starts fresh.
     */
    public void reset() {
        tickCounter = 0;
        nextTurnTick = 0;
        nextArmSwingTick = 200;
        nextShuffleTick = 300;
    }

    /** Wrap an angle to the range (-180, 180]. */
    private static float wrapAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }
}
