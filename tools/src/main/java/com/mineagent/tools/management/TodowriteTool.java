package com.mineagent.tools.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Maintains a bounded, validated planning list for each companion. */
public class TodowriteTool implements Tool {
    private static final ConcurrentHashMap<String, TodoList> TODO_LISTS = new ConcurrentHashMap<>();
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");
    private static final int MAX_ITEMS = 64;
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_CONTENT_LENGTH = 512;

    @Override public String name() { return "todowrite"; }
    @Override public String description() {
        return "Replace the companion's complete todo list with validated planning items.";
    }
    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("todos", "JSON array of {id, content, priority, status} objects")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        JsonArray todos = ToolArgs.getArray(args, "todos");
        if (todos == null) {
            reply.accept(ToolArgs.errorJson("'todos' must be a JSON array."));
            return;
        }
        if (todos.size() > MAX_ITEMS) {
            reply.accept(ToolArgs.errorJson("'todos' may contain at most " + MAX_ITEMS + " items."));
            return;
        }

        TodoList replacement = new TodoList();
        for (int index = 0; index < todos.size(); index++) {
            if (!todos.get(index).isJsonObject()) {
                reply.accept(ToolArgs.errorJson("Todo item " + index + " must be an object."));
                return;
            }
            JsonObject value = todos.get(index).getAsJsonObject();
            String id = ToolArgs.getString(value, "id");
            String content = ToolArgs.getString(value, "content");
            String priority = ToolArgs.getString(value, "priority", "medium");
            String status = ToolArgs.getString(value, "status", "pending");
            if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
                reply.accept(ToolArgs.errorJson("Todo item " + index + " has an invalid id."));
                return;
            }
            if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
                reply.accept(ToolArgs.errorJson("Todo item '" + id + "' has invalid content."));
                return;
            }
            if (!PRIORITIES.contains(priority)) {
                reply.accept(ToolArgs.errorJson("Todo item '" + id + "' has invalid priority."));
                return;
            }
            if (!STATUSES.contains(status)) {
                reply.accept(ToolArgs.errorJson("Todo item '" + id + "' has invalid status."));
                return;
            }
            if (replacement.items.putIfAbsent(id,
                    new TodoItem(id, content, priority, status)) != null) {
                reply.accept(ToolArgs.errorJson("Duplicate todo id: " + id));
                return;
            }
        }

        TODO_LISTS.put(player.companionId().toString(), replacement);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        JsonArray items = new JsonArray();
        for (TodoItem item : replacement.items.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", item.id);
            value.addProperty("content", item.content);
            value.addProperty("priority", item.priority);
            value.addProperty("status", item.status);
            items.add(value);
        }
        result.add("items", items);
        result.addProperty("total", items.size());
        reply.accept(result.toString());
    }

    public static TodoList getTodoList(String playerId) {
        return TODO_LISTS.getOrDefault(playerId, new TodoList());
    }

    public static void forget(java.util.UUID companionId) {
        if (companionId != null) TODO_LISTS.remove(companionId.toString());
    }

    public static class TodoList {
        public final LinkedHashMap<String, TodoItem> items = new LinkedHashMap<>();
    }

    public static class TodoItem {
        public final String id;
        public final String content;
        public final String priority;
        public final String status;

        public TodoItem(String id, String content, String priority, String status) {
            this.id = id;
            this.content = content;
            this.priority = priority;
            this.status = status;
        }
    }
}
