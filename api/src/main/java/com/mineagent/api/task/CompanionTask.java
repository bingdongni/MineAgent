package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

/**
 * A running task — the server-side execution engine for a {@link TaskRecord}.
 * Each TaskRecord type is paired with a CompanionTask via
 * {@link CompanionTaskFactory}.
 *
 * <p>Lifecycle: {@code onStart → onTick* → (onSuccess|onFail|onCancel)}.
 */
public abstract class CompanionTask<R extends TaskRecord> {

    protected final AgentPlayer player;
    protected final R record;

    protected CompanionTask(AgentPlayer player, R record) {
        this.player = player;
        this.record = record;
    }

    /** Called once when the task starts. Override in subclasses. */
    protected abstract void onStart();

    /**
     * Recreate volatile low-level control after a survival interruption.
     * Stateful tasks override this to preserve verified progress while
     * discarding stale paths and interactions. The default is suitable for
     * idempotent tasks whose start method derives everything from world state.
     */
    protected void onResume() { onStart(); }

    /** Called every server tick while the task is running. Override in subclasses. */
    protected abstract TaskState onTick();

    /** Called when the task is interrupted by a higher-priority chain. Override in subclasses. */
    protected abstract void onInterrupt();

    /** Start the task. Called by the scheduler. */
    public final void start() { onStart(); }

    /** Resume after a scheduler pause. Called by the scheduler. */
    public final void resume() { onResume(); }

    /** Tick the task. Called by the scheduler each server tick. */
    public final TaskState tick() { return onTick(); }

    /** Interrupt the task. Called by the scheduler when preempted. */
    public final void interrupt() { onInterrupt(); }

    /**
     * Return executor-grounded progress for status, planning and recovery.
     * Tasks with meaningful phases or counts override this method; the generic
     * fallback still prevents an absent snapshot from breaking scheduling.
     */
    public TaskSnapshot snapshot() {
        return TaskSnapshot.running("running", getClass().getSimpleName() + " is running");
    }

    /** Called on successful completion. Override to provide result data. */
    protected String successMessage() { return "done"; }

    /** Called on timeout. Override to provide diagnostic info. */
    protected String timeoutMessage() { return "timed out"; }

    /** Called on cancellation. */
    protected String cancelledMessage() { return "cancelled"; }

    /** Called on failure. */
    protected String failureMessage() { return "failed"; }

    /**
     * Expose exactly one task-specific terminal message to the scheduler,
     * which owns the transition and publishes it to every observer.
     */
    public final String completionMessage(TaskState state, boolean timedOut) {
        if (state == TaskState.SUCCESS) return successMessage();
        if (state == TaskState.CANCELLED) return cancelledMessage();
        if (timedOut) return timeoutMessage();
        return failureMessage();
    }

    public AgentPlayer player() { return player; }
    public R record() { return record; }
}
