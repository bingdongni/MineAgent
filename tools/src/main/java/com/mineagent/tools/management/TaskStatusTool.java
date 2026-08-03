package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTickDispatcher;
import com.mineagent.api.task.TaskRecord;
import com.mineagent.api.task.TaskState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Check the status of a running or recently completed task.
 *
 * <p>This is a <b>sync</b> tool - replies immediately.
 */
public class TaskStatusTool implements Tool {

    private static final ConcurrentHashMap<String, TaskInfo> TASK_INFO = new ConcurrentHashMap<>();
    private static final long TERMINAL_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_TRACKED_TASKS = 1024;

    @Override
    public String name() { return "task_status"; }

    @Override
    public String description() {
        return """
            Check the status of a running or recently completed task.
            Provide the task_id returned by an async tool call.
            Returns the current state (PENDING/RUNNING/SUCCESS/FAILED/CANCELLED),
            progress message, and result data if completed.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("task_id", "The task ID returned by an async tool call")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String taskId = ToolArgs.getString(args, "task_id");
        if (taskId == null) {
            reply.accept("{\"error\":\"Missing required parameter 'task_id'.\"}");
            return;
        }

        pruneTerminalTasks();
        // Tool-call IDs are only unique inside one provider conversation.
        // Scope the ledger so one companion cannot observe another's task.
        var info = TASK_INFO.get(key(player.companionId(), taskId));
        if (info == null) {
            reply.accept(ToolArgs.errorJson("Task '" + taskId
                    + "' not found. It may have expired or never existed."));
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("state", info.state.name());
        result.addProperty("tool_name", info.toolName);
        if (info.message != null) {
            result.addProperty("message", info.message);
        }
        if (info.resultData != null) {
            try {
                result.add("result", com.google.gson.JsonParser.parseString(info.resultData));
            } catch (com.google.gson.JsonParseException malformedResult) {
                // A task's diagnostic string must not corrupt the complete
                // task_status JSON response.
                result.addProperty("result", info.resultData);
            }
        }
        result.addProperty("elapsed_ticks", info.elapsedTicks);
        reply.accept(result.toString());
    }

    /** Register/update task info. */
    public static void updateTaskInfo(UUID companionId, String taskId, String toolName, TaskState state,
                                       String message, String resultData, long elapsedTicks) {
        if (companionId == null || taskId == null || taskId.isBlank()) return;
        TASK_INFO.put(key(companionId, taskId),
                new TaskInfo(toolName, state, message, resultData, elapsedTicks));
        if (TASK_INFO.size() > MAX_TRACKED_TASKS) pruneTerminalTasks();
    }

    /** Remove task info. */
    public static void removeTaskInfo(UUID companionId, String taskId) {
        if (companionId != null && taskId != null) {
            TASK_INFO.remove(key(companionId, taskId));
        }
    }

    /** Get task info by ID. */
    public static TaskInfo getTaskInfo(UUID companionId, String taskId) {
        return companionId == null || taskId == null
                ? null : TASK_INFO.get(key(companionId, taskId));
    }

    private static String key(UUID companionId, String taskId) {
        return companionId + "\u0000" + taskId;
    }

    private static void pruneTerminalTasks() {
        long cutoff = System.currentTimeMillis() - TERMINAL_TTL_MS;
        TASK_INFO.entrySet().removeIf(entry -> entry.getValue().isTerminal()
                && entry.getValue().updatedAtMillis < cutoff);
        while (TASK_INFO.size() > MAX_TRACKED_TASKS) {
            var oldest = TASK_INFO.entrySet().stream()
                    .filter(entry -> entry.getValue().isTerminal())
                    .min(java.util.Comparator.comparingLong(
                            entry -> entry.getValue().updatedAtMillis));
            if (oldest.isEmpty()) break;
            TASK_INFO.remove(oldest.get().getKey(), oldest.get().getValue());
        }
    }

    /** Stored task info. */
    public static class TaskInfo {
        public final String toolName;
        public volatile TaskState state;
        public volatile String message;
        public volatile String resultData;
        public volatile long elapsedTicks;
        public final long updatedAtMillis;

        public TaskInfo(String toolName, TaskState state, String message,
                         String resultData, long elapsedTicks) {
            this.toolName = toolName;
            this.state = state;
            this.message = message;
            this.resultData = resultData;
            this.elapsedTicks = elapsedTicks;
            this.updatedAtMillis = System.currentTimeMillis();
        }

        private boolean isTerminal() {
            return state == TaskState.SUCCESS || state == TaskState.FAILED
                    || state == TaskState.CANCELLED;
        }
    }
}
