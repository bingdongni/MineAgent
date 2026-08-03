package com.mineagent.api.task;

/**
 * The lifecycle state of a running task.
 */
public enum TaskState {
    /** Task has been created but not started yet. */
    PENDING,
    /** Task is currently executing. */
    RUNNING,
    /** Task completed successfully. */
    SUCCESS,
    /** Task failed. */
    FAILED,
    /** Task was cancelled by the user or model. */
    CANCELLED
}
