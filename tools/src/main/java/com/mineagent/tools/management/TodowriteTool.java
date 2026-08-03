package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.Set;

/**
 * Write or update a todo list for task management. The companion
 * maintains a todo list per player that persists across tool calls.
 *
 * <p>This is a <b>sync</b> tool - replies immediately.
 */
public class TodowriteTool implements Tool {

    private static final ConcurrentHashMap<String, TodoList> TODO_LISTS = new ConcurrentHashMap<>();
    private static final int MAX_TODOS = 100;
    private static final int MAX_FIELD_LENGTH = 500;
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> STATUSES = Set.of(
            "pending", "in_progress", "completed");

    @Override
    public String name() { return "todowrite"; }

    @Override
    public String description() {
        return """
            Write or update a todo list for task management. Each item has an
            id, content description, priority (high/medium/low), and status
            (pending/in_progress/completed).
            
            Pass the full list of items each time - the list is replaced entirely.
            Use this to track multi-step plans and progress.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> todoSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "priority", Map.of("type", "string", "enum", PRIORITIES),
                        "status", Map.of("type", "string", "enum", STATUSES)),
                "required", java.util.List.of("content"),
                "additionalProperties", false);
        return Schema.object()
                .array("todos", "Complete replacement todo list", todoSchema, 0)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        // Robust parsing: LLM may pass "todos" as a string (JSON-encoded)
        // instead of an actual JSON array. Handle both cases.
        com.google.gson.JsonArray todosArray = null;
        if (args != null && args.has("todos") && !args.get("todos").isJsonNull()) {
            var todosElement = args.get("todos");
            if (todosElement.isJsonArray()) {
                todosArray = todosElement.getAsJsonArray();
            } else if (todosElement.isJsonPrimitive() && todosElement.getAsJsonPrimitive().isString()) {
                // LLM passed a JSON string — parse it
                try {
                    var parsed = com.google.gson.JsonParser.parseString(todosElement.getAsString());
                    if (parsed.isJsonArray()) {
                        todosArray = parsed.getAsJsonArray();
                    }
                } catch (Exception e) {
                    reply.accept(ToolArgs.errorJson(
                            "Failed to parse todos string: " + e.getMessage()));
                    return;
                }
            }
        }

        if (todosArray == null) {
            reply.accept("{\"error\":\"todos must be a JSON array of {id, content, priority, status} objects.\"}");
            return;
        }
        if (todosArray.size() > MAX_TODOS) {
            reply.accept(ToolArgs.errorJson("Too many todo items (max " + MAX_TODOS + ")."));
            return;
        }

        var playerId = player.companionId().toString();
        var todoList = new TodoList();

        for (var element : todosArray) {
            if (!element.isJsonObject()) {
                reply.accept(ToolArgs.errorJson("Every todo item must be an object."));
                return;
            }
            var item = element.getAsJsonObject();
            String id = ToolArgs.getString(item, "id",
                    String.valueOf(todoList.items.size() + 1));
            String content = ToolArgs.getString(item, "content");
            String priority = ToolArgs.getString(item, "priority", "medium");
            String status = ToolArgs.getString(item, "status", "pending");
            if (id.isBlank() || id.length() > MAX_FIELD_LENGTH || content == null
                    || content.isBlank() || content.length() > MAX_FIELD_LENGTH
                    || !PRIORITIES.contains(priority) || !STATUSES.contains(status)) {
                reply.accept(ToolArgs.errorJson(
                        "Each todo needs bounded id/content and valid priority/status."));
                return;
            }

            if (todoList.items.containsKey(id)) {
                // Silently overwriting duplicate IDs made the acknowledged
                // list shorter than the submitted plan and lost an item.
                reply.accept(ToolArgs.errorJson("Duplicate todo id: " + id));
                return;
            }
            todoList.items.put(id, new TodoItem(id, content, priority, status));
        }

        TODO_LISTS.put(playerId, todoList);

        // Return the current state
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        var resultItems = new com.google.gson.JsonArray();
        for (var entry : todoList.items.entrySet()) {
            var item = entry.getValue();
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("id", item.id);
            itemJson.addProperty("content", item.content);
            itemJson.addProperty("priority", item.priority);
            itemJson.addProperty("status", item.status);
            resultItems.add(itemJson);
        }
        result.add("items", resultItems);
        result.addProperty("total", todoList.items.size());
        reply.accept(result.toString());
    }

    /** Get the todo list for a player. */
    public static TodoList getTodoList(String playerId) {
        return TODO_LISTS.getOrDefault(playerId, new TodoList());
    }

    /** Release per-companion transient state during despawn. */
    public static void forget(java.util.UUID companionId) {
        if (companionId != null) TODO_LISTS.remove(companionId.toString());
    }

    /** Persistent todo list storage. */
    public static class TodoList {
        public final LinkedHashMap<String, TodoItem> items = new LinkedHashMap<>();
    }

    /** A single todo item. */
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
