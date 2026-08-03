package com.mineagent.engine.survival;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Rolling-window stuck detector — borrowed from numen project, refined
 * for MineAgent.
 *
 * <p><b>Problem solved</b>: The old {@link UnstuckChain} used a simple
 * {@code samePosTicks} counter that incremented every tick the companion
 * didn't move. This caused false positives:
 * <ul>
 *   <li>During LLM thinking (companion legitimately idle) → false trigger</li>
 *   <li>During tool execution pauses → false trigger</li>
 *   <li>While waiting for a path to compute → false trigger</li>
 * </ul>
 *
 * <p><b>Solution</b>: A rolling window that only counts ticks where the
 * companion was <b>actively trying to move</b> (forward/strafe/jump input
 * non-zero). If the companion is standing still with no input, those ticks
 * don't count — the companion is intentionally idle, not stuck.
 *
 * <p><b>Trigger conditions</b> (both must be true):
 * <ol>
 *   <li>At least 80% of ticks in the window had non-zero movement input</li>
 *   <li>Total displacement over the window is less than 0.75 blocks</li>
 * </ol>
 *
 * <p><b>Why 80%?</b> A real player who is stuck will spam movement keys
 * almost every tick. If they're below 80%, they're probably pausing
 * intentionally (looking around, waiting). 80% is the empirical sweet
 * spot from the numen project.
 *
 * <p><b>Why 0.75 blocks?</b> Minecraft's movement is jittery even when
 * stuck (the player vibrates slightly against the wall). 0.75 blocks
 * filters out this jitter while still catching genuine stuck cases.
 *
 * <p><b>Thread safety</b>: This class is NOT thread-safe. It must only be
 * called from the server tick thread (which is single-threaded per world).
 *
 * <p><b>Pure function design</b>: All state is encapsulated. The
 * {@link #isStuck(ServerPlayer, double, double, double)} method takes
 * raw input values and returns a boolean — no Minecraft side effects.
 * This makes it trivially unit-testable.
 */
public final class UnstuckDetector {

    /** Window size in ticks. 40 ticks = 2 seconds at 20 TPS. */
    private static final int DEFAULT_WINDOW_SIZE = 40;

    /** Minimum ratio of "trying to move" ticks to total ticks in window. */
    private static final double TRYING_RATIO_THRESHOLD = 0.80;

    /** Maximum displacement (in blocks) over the window to be considered stuck. */
    private static final double MAX_DISPLACEMENT = 0.75;

    /** Ring buffer of "trying to move" flags, one entry per tick. */
    private final int windowSize;
    private final boolean[] tryingBuffer;

    /** Ring buffer of X positions, one entry per tick. */
    private final double[] xBuffer;

    /** Ring buffer of Z positions, one entry per tick. */
    private final double[] zBuffer;

    /** Current write index in the ring buffer. */
    private int writeIndex = 0;

    /** Number of ticks recorded so far (caps at WINDOW_SIZE). */
    private int recordedTicks = 0;

    public UnstuckDetector() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public UnstuckDetector(int windowSize) {
        this.windowSize = Math.max(2, windowSize);
        this.tryingBuffer = new boolean[this.windowSize];
        this.xBuffer = new double[this.windowSize];
        this.zBuffer = new double[this.windowSize];
    }

    /**
     * Record one tick of movement data.
     *
     * <p>This is a pure function — it only updates internal state, no
     * Minecraft side effects. Call this every server tick from
     * {@link UnstuckChain#getPriority}.
     *
     * @param tryingToMove true if the companion has non-zero movement input
     *                     (forward, strafe, or jump). false if intentionally idle.
     * @param x           companion's current X position
     * @param z           companion's current Z position
     */
    public void record(boolean tryingToMove, double x, double z) {
        tryingBuffer[writeIndex] = tryingToMove;
        xBuffer[writeIndex] = x;
        zBuffer[writeIndex] = z;
        writeIndex = (writeIndex + 1) % windowSize;
        if (recordedTicks < windowSize) recordedTicks++;
    }

    /**
     * Check if the companion is currently stuck.
     *
     * <p>Returns false if the window is not yet full ({@code recordedTicks <
     * WINDOW_SIZE}). This prevents false triggers during the first 2 seconds
     * after the companion spawns.
     *
     * <p><b>Stuck condition</b> (both must be true):
     * <ol>
     *   <li>At least {@link #TRYING_RATIO_THRESHOLD} of ticks in the window
     *       had non-zero movement input</li>
     *   <li>Displacement from the oldest recorded position to the current
     *       position is less than {@link #MAX_DISPLACEMENT} blocks (horizontal
     *       XZ distance only — vertical Y is ignored to avoid false positives
     *       on stepped terrain)</li>
     * </ol>
     *
     * @return true if the companion is stuck (trying to move but not moving)
     */
    public boolean isStuck() {
        if (recordedTicks < windowSize) return false;

        // Count how many ticks in the window had movement input
        int tryingCount = 0;
        for (int i = 0; i < windowSize; i++) {
            if (tryingBuffer[i]) tryingCount++;
        }

        double tryingRatio = (double) tryingCount / windowSize;
        if (tryingRatio < TRYING_RATIO_THRESHOLD) return false;

        // Compute displacement from oldest to newest entry.
        // The oldest entry is at writeIndex (ring buffer wrap-around).
        int oldestIdx = writeIndex;  // next write will overwrite this, so it's the oldest
        int newestIdx = (writeIndex - 1 + windowSize) % windowSize;

        double dx = xBuffer[newestIdx] - xBuffer[oldestIdx];
        double dz = zBuffer[newestIdx] - zBuffer[oldestIdx];
        double displacement = Math.sqrt(dx * dx + dz * dz);

        return displacement < MAX_DISPLACEMENT;
    }

    /**
     * Reset the detector — clear all recorded data.
     *
     * <p>Call this after the companion successfully escapes (in
     * {@link UnstuckChain#reset}) to start a fresh detection window.
     */
    public void reset() {
        writeIndex = 0;
        recordedTicks = 0;
        // No need to clear the arrays — they'll be overwritten as new
        // data comes in. recordedTicks guards against reading stale data.
    }

    /**
     * Get the current "trying ratio" — fraction of the window where the
     * companion was actively trying to move.
     *
     * <p>Useful for debugging: if the detector fires with a low ratio,
     * the threshold may need adjustment.
     */
    public double currentTryingRatio() {
        if (recordedTicks == 0) return 0.0;
        int tryingCount = 0;
        int count = Math.min(recordedTicks, windowSize);
        for (int i = 0; i < count; i++) {
            if (tryingBuffer[i]) tryingCount++;
        }
        return (double) tryingCount / count;
    }

    /**
     * Get the current horizontal displacement over the window.
     *
     * <p>Useful for debugging: a high displacement with a stuck trigger
     * indicates {@link #MAX_DISPLACEMENT} needs to be raised.
     */
    public double currentDisplacement() {
        if (recordedTicks < 2) return 0.0;
        int oldestIdx = recordedTicks < windowSize ? 0 : writeIndex;
        int newestIdx = (writeIndex - 1 + windowSize) % windowSize;
        double dx = xBuffer[newestIdx] - xBuffer[oldestIdx];
        double dz = zBuffer[newestIdx] - zBuffer[oldestIdx];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
