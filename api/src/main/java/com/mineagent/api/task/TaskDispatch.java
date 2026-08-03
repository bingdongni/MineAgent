package com.mineagent.api.task;

import com.mineagent.api.agent.tool.ToolContext;
import com.mineagent.api.entity.AgentPlayer;

import java.util.function.Consumer;

/**
 * Dispatch helpers for launching tasks from tools.
 */
public final class TaskDispatch {

    private TaskDispatch() {}

    /**
     * Create a ToolContext from a tool call ID and the companion player.
     */
    public static ToolContext ctx(String toolCallId, AgentPlayer player) {
        return ToolContext.of(toolCallId, player);
    }

    /**
     * Dispatch an asynchronous task. The reply callback is called immediately
     * with a task_id acknowledgment; the final result arrives later as a
     * task_finished event.
     */
    public static void dispatchAsync(AgentPlayer player, TaskRecord record,
                                       Consumer<String> reply) {
        // The engine's tick dispatcher picks up the record and creates
        // a CompanionTask via CompanionTaskFactory. For now, we reply
        // with the task_id acknowledgment.
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(record, "record");
        java.util.Objects.requireNonNull(reply, "reply");
        String taskId = record.toolCallId();
        // Queue first so an acknowledged task always exists even if a custom
        // callback throws while receiving the immediate response.
        CompanionTickDispatcher.submit(player, record);
        com.google.gson.JsonObject acknowledgment = new com.google.gson.JsonObject();
        acknowledgment.addProperty("task_id", taskId);
        acknowledgment.addProperty("async", true);
        reply.accept(acknowledgment.toString());
    }
}
