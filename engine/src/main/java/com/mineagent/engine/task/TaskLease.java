package com.mineagent.engine.task;

/**
 * Task progress lease — borrowed from numen project's MoveToCompanionTask.
 *
 * <p><b>Problem solved</b>: The old {@link MoveToTask} used a single hard
 * deadline ({@code record.deadline()}). If the deadline was too short,
 * long but healthy journeys (e.g. walking 200 blocks around a lake) were
 * killed mid-route. If too long, genuinely stuck tasks ran forever.
 *
 * <p><b>Solution</b>: A three-layer lease system:
 * <ol>
 *   <li><b>Progress lease</b> ({@link #PROGRESS_LEASE_TICKS}): When the
 *       companion makes progress (position advances), the deadline is
 *       pushed forward by this amount. Healthy journeys never time out.</li>
 *   <li><b>Progress grace</b> ({@link #PROGRESS_GRACE_TICKS}): If no
 *       progress is made for this many ticks, the lease stops renewing.
 *       This catches "stuck but lease keeps renewing" cases.</li>
 *   <li><b>Check-in cap</b> ({@link #CHECK_IN_CAP_TICKS}): Hard upper
 *       limit. Even with continuous progress, the task must complete
 *       within this many ticks. Prevents infinite journeys.</li>
 * </ol>
 *
 * <p><b>Progress signal</b>: A "progress event" is any sign the task is
 * advancing — not just distance reduction. For movement tasks, this is
 * horizontal position advancement. For mining tasks, this is block break
 * progress. The caller decides what counts as progress via
 * {@link #onProgress()}.
 *
 * <p><b>Thread safety</b>: NOT thread-safe. Must be called from the
 * server tick thread.
 *
 * <p><b>Pure state design</b>: All state is encapsulated. The caller
 * drives the lifecycle: {@link #start} → {@link #onProgress} (repeated)
 * → {@link #tick} (each tick) → check {@link #isExpired}.
 */
public final class TaskLease {

    /** Lease extension when progress is made. 30 seconds at 20 TPS. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;

    /** Grace period without progress before lease stops renewing. 5 seconds. */
    private static final long PROGRESS_GRACE_TICKS = 5 * 20;

    /** Hard upper limit on task duration. 5 minutes at 20 TPS. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;

    /** Game tick when the task started. */
    private long startTick;

    /** Game tick when the current lease expires. */
    private long leaseDeadline;

    /** Hard deadline (start + CHECK_IN_CAP_TICKS). */
    private long hardDeadline;

    /** Game tick of the last progress event. */
    private long lastProgressTick;

    /** Whether the lease is still active (not in grace failure). */
    private boolean leaseActive;

    /**
     * Start the lease. Call this when the task begins.
     *
     * @param currentTick current game tick
     */
    public void start(long currentTick) {
        this.startTick = currentTick;
        this.leaseDeadline = currentTick + PROGRESS_LEASE_TICKS;
        this.hardDeadline = currentTick + CHECK_IN_CAP_TICKS;
        this.lastProgressTick = currentTick;
        this.leaseActive = true;
    }

    /**
     * Record a progress event. Extends the lease if within grace period.
     *
     * <p>Call this whenever the task makes progress:
     * <ul>
     *   <li>Movement task: companion moved &gt; 1 block since last progress</li>
     *   <li>Mining task: a block was broken</li>
     *   <li>Building task: a block was placed</li>
     * </ul>
     *
     * @param currentTick current game tick
     */
    public void onProgress(long currentTick) {
        // Measure before updating the marker. Updating first made this gap
        // permanently zero, allowing stale progress to renew an expired lease.
        long progressGap = currentTick - lastProgressTick;
        if (leaseActive && progressGap <= PROGRESS_GRACE_TICKS) {
            // Renew lease, but never exceed hard deadline
            long newDeadline = currentTick + PROGRESS_LEASE_TICKS;
            leaseDeadline = Math.min(newDeadline, hardDeadline);
        }
        this.lastProgressTick = currentTick;
    }

    /**
     * Tick the lease. Call this every server tick.
     *
     * <p>Checks:
     * <ul>
     *   <li>If grace period expired (no progress for PROGRESS_GRACE_TICKS),
     *       deactivate lease renewal</li>
     * </ul>
     *
     * @param currentTick current game tick
     */
    public void tick(long currentTick) {
        // If no progress for grace period, deactivate lease renewal
        if (currentTick - lastProgressTick > PROGRESS_GRACE_TICKS) {
            leaseActive = false;
            // leaseDeadline stays where it was — it will expire naturally
        }
    }

    /**
     * Is the task expired (should be killed)?
     *
     * <p>Returns true if:
     * <ul>
     *   <li>Current tick &gt;= lease deadline (lease expired), OR</li>
     *   <li>Current tick &gt;= hard deadline (check-in cap reached)</li>
     * </ul>
     *
     * @param currentTick current game tick
     * @return true if the task should be terminated
     */
    public boolean isExpired(long currentTick) {
        return currentTick >= leaseDeadline || currentTick >= hardDeadline;
    }

    /**
     * Get the reason for expiration (for debugging/logging).
     *
     * @param currentTick current game tick
     * @return reason string, or null if not expired
     */
    public String expirationReason(long currentTick) {
        if (currentTick >= hardDeadline) {
            return "check-in cap reached (5 minute hard limit)";
        }
        if (currentTick >= leaseDeadline) {
            if (!leaseActive) {
                return "lease expired (no progress for " + PROGRESS_GRACE_TICKS + " ticks)";
            }
            return "lease expired (progress lease ran out)";
        }
        return null;
    }

    /**
     * Get the current lease deadline (for debugging).
     */
    public long leaseDeadline() { return leaseDeadline; }

    /**
     * Get the hard deadline (for debugging).
     */
    public long hardDeadline() { return hardDeadline; }

    /**
     * Get ticks since last progress (for debugging).
     *
     * @param currentTick current game tick
     */
    public long ticksSinceProgress(long currentTick) {
        return currentTick - lastProgressTick;
    }

    /**
     * Is the lease still actively renewing?
     */
    public boolean isLeaseActive() { return leaseActive; }
}
