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

    /** Called every server tick while the task is running. Override in subclasses. */
    protected abstract TaskState onTick();

    /** Called when the task is interrupted by a higher-priority chain. Override in subclasses. */
    protected abstract void onInterrupt();

    /** Start the task. Called by the scheduler. */
    public final void start() { onStart(); }

    /** Tick the task. Called by the scheduler each server tick. */
    public final TaskState tick() { return onTick(); }

    /** Interrupt the task. Called by the scheduler when preempted. */
    public final void interrupt() { onInterrupt(); }

    /** Called on successful completion. Override to provide result data. */
    protected String successMessage() { return "done"; }

    /** Called on timeout. Override to provide diagnostic info. */
    protected String timeoutMessage() { return "timed out"; }

    /** Called on cancellation. */
    protected String cancelledMessage() { return "cancelled"; }

    /** Called on failure. */
    protected String failureMessage() { return "failed"; }

    /**
     * Return the terminal message for this task.
     *
     * <p>The scheduler owns the terminal transition. Exposing the protected
     * task-specific messages through one final method lets it publish exactly
     * one consistent result to the task ledger and AgentLoop.
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
