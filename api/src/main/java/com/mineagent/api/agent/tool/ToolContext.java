package com.mineagent.api.agent.tool;

import com.mineagent.api.entity.AgentPlayer;

/**
 * Context passed to tools for task dispatch. Carries the tool call ID,
 * the companion player, and a deadline.
 */
public final class ToolContext {

    private final String toolCallId;
    private final AgentPlayer player;
    private long deadlineGameTime;

    public ToolContext(String toolCallId, AgentPlayer player) {
        this.toolCallId = toolCallId;
        this.player = player;
    }

    public String toolCallId() { return toolCallId; }
    public AgentPlayer player() { return player; }
    public long deadline() { return deadlineGameTime; }

    public ToolContext withDeadline(long deadlineGameTime) {
        this.deadlineGameTime = deadlineGameTime;
        return this;
    }

    /** Convenience factory used by TaskDispatch. */
    public static ToolContext of(String toolCallId, AgentPlayer player) {
        return new ToolContext(toolCallId, player);
    }
}
