package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

/**
 * A task record — the data payload emitted by a tool that needs server-side
 * execution. Each concrete task type extends this with its own fields.
 *
 * <p>The record carries:
 * <ul>
 *   <li>a toolCallId linking it back to the LLM's tool call</li>
 *   <li>a deadline (game time) after which the task is timed out</li>
 *   <li>task-specific data (coordinates, block ids, etc.)</li>
 * </ul>
 */
public abstract class TaskRecord {

    private final String toolCallId;
    private long deadlineGameTime;

    protected TaskRecord(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    /** The LLM tool call ID this task was spawned from. */
    public String toolCallId() { return toolCallId; }

    /** The game-time deadline after which this task is considered timed out. */
    public long deadline() { return deadlineGameTime; }

    /** Set the deadline. */
    public void extendDeadlineTo(long gameTime) {
        this.deadlineGameTime = gameTime;
    }

    /** Whether this task is asynchronous (runs in background, reports via events). */
    public boolean isAsync() {
        return true; // default; synchronous tasks override
    }
}
