package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskState;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Query the bounded per-companion ledger of asynchronous body tasks. */
public class TaskStatusTool implements Tool {

    private static final ConcurrentHashMap<String, TaskInfo> TASK_INFO =
            new ConcurrentHashMap<>();
    private static final long TERMINAL_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_TRACKED_TASKS = 1024;

    @Override
    public String name() { return "task_status"; }

    @Override
    public String description() {
        return "Check an asynchronous task by the task_id returned by its action tool.";
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
        if (taskId == null || taskId.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'task_id'."));
            return;
        }

        pruneTerminalTasks();
        // Provider call IDs are conversation-local, so companion UUID must be
        // part of the key to prevent cross-companion status disclosure.
        TaskInfo info = TASK_INFO.get(key(player.companionId(), taskId));
        if (info == null) {
            reply.accept(ToolArgs.errorJson("Task '" + taskId
                    + "' not found. It may have expired or never existed."));
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("task_id", taskId);
        result.addProperty("state", info.state.name());
        result.addProperty("tool_name", info.toolName);
        if (info.message != null) result.addProperty("message", info.message);
        if (info.resultData != null) {
            try {
                result.add("result", com.google.gson.JsonParser.parseString(info.resultData));
            } catch (com.google.gson.JsonParseException malformed) {
                result.addProperty("result", info.resultData);
            }
        }
        result.addProperty("elapsed_ticks", info.elapsedTicks);
        reply.accept(result.toString());
    }

    public static void updateTaskInfo(UUID companionId, String taskId, String toolName,
                                      TaskState state, String message,
                                      String resultData, long elapsedTicks) {
        if (companionId == null || taskId == null || taskId.isBlank() || state == null) return;
        TASK_INFO.put(key(companionId, taskId),
                new TaskInfo(toolName, state, message, resultData, elapsedTicks));
        if (TASK_INFO.size() > MAX_TRACKED_TASKS) pruneTerminalTasks();
    }

    public static void removeTaskInfo(UUID companionId, String taskId) {
        if (companionId != null && taskId != null) TASK_INFO.remove(key(companionId, taskId));
    }

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
                    .min(Comparator.comparingLong(entry -> entry.getValue().updatedAtMillis));
            if (oldest.isEmpty()) break;
            TASK_INFO.remove(oldest.get().getKey(), oldest.get().getValue());
        }
    }

    public static final class TaskInfo {
        public final String toolName;
        public final TaskState state;
        public final String message;
        public final String resultData;
        public final long elapsedTicks;
        public final long updatedAtMillis;

        private TaskInfo(String toolName, TaskState state, String message,
                         String resultData, long elapsedTicks) {
            this.toolName = toolName == null ? "unknown" : toolName;
            this.state = state;
            this.message = message;
            this.resultData = resultData;
            this.elapsedTicks = Math.max(0L, elapsedTicks);
            this.updatedAtMillis = System.currentTimeMillis();
        }

        private boolean isTerminal() {
            return state == TaskState.SUCCESS || state == TaskState.FAILED
                    || state == TaskState.CANCELLED;
        }
    }
}
