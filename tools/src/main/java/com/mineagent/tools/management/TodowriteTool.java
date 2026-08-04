package com.mineagent.tools.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.planning.PlanGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Updates the verifier-backed plan graph for a companion.
 *
 * <p>This used to write an isolated static map that no scheduler or prompt
 * consumed. The tool now writes the same plan state used by task admission,
 * progress reporting, persistence and deliberation.
 */
public final class TodowriteTool implements Tool {
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> STATUSES = Set.of(
            "pending", "in_progress", "completed", "blocked", "invalidated");
    private static final int MAX_ITEMS = 64;
    private static final int MAX_CONSTRAINTS = 32;
    private static final int MAX_TEXT_LENGTH = 512;

    @Override public String name() { return "todowrite"; }

    @Override public String description() {
        return "Replace the current long-horizon plan. Completed steps are accepted only "
                + "when executor evidence exists. Include owner constraints when they matter.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> todoItem = objectSchema(Map.of(
                "id", Map.of("type", "string", "description", "Stable step ID"),
                "content", Map.of("type", "string", "description", "Concrete subgoal"),
                "success_criterion", Map.of("type", List.of("string", "null"),
                        "description", "Observable completion condition"),
                "priority", Map.of("type", "string", "enum", List.of("high", "medium", "low")),
                "status", Map.of("type", "string", "enum",
                        List.of("pending", "in_progress", "completed", "blocked", "invalidated"))
        ), List.of("id", "content", "priority", "status"));
        Map<String, Object> constraintItem = objectSchema(Map.of(
                "id", Map.of("type", "string"),
                "kind", Map.of("type", "string", "enum", List.of("hard", "preference")),
                "description", Map.of("type", "string"),
                "scope", Map.of("type", List.of("string", "null"))
        ), List.of("id", "kind", "description"));
        return Schema.object()
                .optionalString("goal", "The owner's current top-level goal")
                .array("todos", "Complete ordered plan; replaces prior nodes", todoItem, 0)
                .optionalArray("constraints", "Hard constraints and owner preferences; omitted preserves existing constraints",
                        constraintItem, 0)
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

        List<PlanGraph.DraftNode> drafts = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < todos.size(); index++) {
            if (!todos.get(index).isJsonObject()) {
                reply.accept(ToolArgs.errorJson("Todo item " + index + " must be an object."));
                return;
            }
            JsonObject value = todos.get(index).getAsJsonObject();
            String id = ToolArgs.getString(value, "id");
            String content = ToolArgs.getString(value, "content");
            String criterion = ToolArgs.getString(value, "success_criterion",
                    "A body task provides observable success evidence");
            String priority = ToolArgs.getString(value, "priority", "medium");
            String status = ToolArgs.getString(value, "status", "pending");
            if (!validText(id, 64) || !validText(content, MAX_TEXT_LENGTH)
                    || !validText(criterion, MAX_TEXT_LENGTH)) {
                reply.accept(ToolArgs.errorJson("Todo item " + index + " contains invalid text."));
                return;
            }
            if (!ids.add(id)) {
                reply.accept(ToolArgs.errorJson("Duplicate todo id: " + id));
                return;
            }
            if (!PRIORITIES.contains(priority) || !STATUSES.contains(status)) {
                reply.accept(ToolArgs.errorJson("Todo item '" + id
                        + "' has invalid priority or status."));
                return;
            }
            drafts.add(new PlanGraph.DraftNode(id, content, criterion, priority,
                    parseStatus(status)));
        }

        List<IntentContract.Constraint> constraints = null;
        if (args.has("constraints") && !args.get("constraints").isJsonNull()) {
            JsonArray values = ToolArgs.getArray(args, "constraints");
            if (values == null || values.size() > MAX_CONSTRAINTS) {
                reply.accept(ToolArgs.errorJson("'constraints' must contain at most "
                        + MAX_CONSTRAINTS + " objects."));
                return;
            }
            constraints = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                if (!values.get(index).isJsonObject()) {
                    reply.accept(ToolArgs.errorJson("Constraint " + index + " must be an object."));
                    return;
                }
                JsonObject value = values.get(index).getAsJsonObject();
                String id = ToolArgs.getString(value, "id");
                String kind = ToolArgs.getString(value, "kind");
                String description = ToolArgs.getString(value, "description");
                String scope = ToolArgs.getString(value, "scope", "plan");
                if (!validText(id, 64) || !validText(description, MAX_TEXT_LENGTH)
                        || !("hard".equals(kind) || "preference".equals(kind))) {
                    reply.accept(ToolArgs.errorJson("Constraint " + index + " is invalid."));
                    return;
                }
                constraints.add(new IntentContract.Constraint(id,
                        "hard".equals(kind) ? IntentContract.ConstraintKind.HARD
                                : IntentContract.ConstraintKind.PREFERENCE,
                        description, scope));
            }
        }

        var state = MineAgentEngine.getCompanion(player.companionId());
        if (state.isEmpty()) {
            reply.accept(ToolArgs.errorJson("Companion is no longer active."));
            return;
        }
        String goal = ToolArgs.getString(args, "goal", null);
        PlanGraph.UpdateResult update = state.get().loop.planGraph()
                .replacePlan(goal, drafts, constraints);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("revision", update.revision());
        result.addProperty("verified_percent", state.get().loop.planGraph().progressPercent());
        JsonArray warnings = new JsonArray();
        update.warnings().forEach(warnings::add);
        result.add("warnings", warnings);
        result.addProperty("plan", state.get().loop.planGraph().summarizeForPrompt());
        reply.accept(result.toString());
    }

    /** Kept for lifecycle call compatibility; plan ownership is now AgentLoop. */
    public static void forget(java.util.UUID companionId) {}

    private static PlanGraph.NodeStatus parseStatus(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "in_progress" -> PlanGraph.NodeStatus.IN_PROGRESS;
            case "completed" -> PlanGraph.NodeStatus.VERIFIED;
            case "blocked" -> PlanGraph.NodeStatus.BLOCKED;
            case "invalidated" -> PlanGraph.NodeStatus.INVALIDATED;
            default -> PlanGraph.NodeStatus.PENDING;
        };
    }

    private static boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private static Map<String, Object> objectSchema(
            Map<String, Map<String, Object>> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
