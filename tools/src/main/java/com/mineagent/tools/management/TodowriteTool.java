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
    private static final int MAX_GOAL_CONDITIONS = 16;
    private static final int MAX_TEXT_LENGTH = 512;

    @Override public String name() { return "todowrite"; }

    @Override public String description() {
        return "Create or repair the verifier-backed long-horizon plan. Use update_mode=repair "
                + "after a blocker so verified checkpoints are retained. Completed steps and "
                + "top-level goal acceptance require observed executor/world evidence.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> todoItem = objectSchema(Map.of(
                "id", Map.of("type", "string", "description", "Stable step ID"),
                "content", Map.of("type", "string", "description", "Concrete subgoal"),
                "success_criterion", Map.of("type", List.of("string", "null"),
                        "description", "Observable completion condition"),
                "depends_on", Map.of("type", "array",
                        "description", "Step IDs that must be verified first",
                        "items", Map.of("type", "string"), "maxItems", MAX_ITEMS),
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
        Map<String, Object> conditionItem = objectSchema(Map.of(
                "subject", Map.of("type", "string",
                        "description", "Semantic subject, e.g. inventory:minecraft:iron_ingot"),
                "predicate", Map.of("type", "string",
                        "description", "Semantic predicate, e.g. count"),
                "value", Map.of("type", "string",
                        "description", "Expected string or finite numeric value"),
                "comparison", Map.of("type", "string", "enum",
                        List.of("equals", "at_least", "at_most", "present")),
                "minimum_confidence", Map.of("type", List.of("number", "null"),
                        "minimum", 0.0, "maximum", 1.0)
        ), List.of("subject", "predicate", "value", "comparison"));
        return Schema.object()
                .optionalString("goal", "The owner's current top-level goal")
                .optionalString("update_mode", "Plan update: 'replace' for a new goal or 'repair' for the invalid suffix")
                .array("todos", "Complete ordered plan; replaces prior nodes", todoItem, 0)
                .optionalArray("constraints", "Hard constraints and owner preferences; omitted preserves existing constraints",
                        constraintItem, 0)
                .optionalArray("goal_conditions", "Machine-checkable top-level acceptance conditions; omitted preserves them during repair",
                        conditionItem, 0)
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
            List<String> dependencies = new ArrayList<>();
            if (value.has("depends_on") && !value.get("depends_on").isJsonNull()) {
                JsonArray dependencyValues = ToolArgs.getArray(value, "depends_on");
                if (dependencyValues == null || dependencyValues.size() > MAX_ITEMS) {
                    reply.accept(ToolArgs.errorJson("Todo item '" + id
                            + "' has invalid depends_on."));
                    return;
                }
                for (var dependencyValue : dependencyValues) {
                    if (!dependencyValue.isJsonPrimitive()
                            || !dependencyValue.getAsJsonPrimitive().isString()
                            || !validText(dependencyValue.getAsString(), 64)) {
                        reply.accept(ToolArgs.errorJson("Todo item '" + id
                                + "' has a non-string dependency."));
                        return;
                    }
                    dependencies.add(dependencyValue.getAsString());
                }
            }
            drafts.add(new PlanGraph.DraftNode(id, content, criterion, priority,
                    dependencies,
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

        List<PlanGraph.GoalCondition> goalConditions = null;
        if (args.has("goal_conditions") && !args.get("goal_conditions").isJsonNull()) {
            JsonArray values = ToolArgs.getArray(args, "goal_conditions");
            if (values == null || values.size() > MAX_GOAL_CONDITIONS) {
                reply.accept(ToolArgs.errorJson("'goal_conditions' must contain at most "
                        + MAX_GOAL_CONDITIONS + " objects."));
                return;
            }
            goalConditions = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                if (!values.get(index).isJsonObject()) {
                    reply.accept(ToolArgs.errorJson("Goal condition " + index
                            + " must be an object."));
                    return;
                }
                JsonObject value = values.get(index).getAsJsonObject();
                String subject = ToolArgs.getString(value, "subject");
                String predicate = ToolArgs.getString(value, "predicate");
                String expected = ToolArgs.getString(value, "value");
                String comparison = ToolArgs.getString(value, "comparison", "equals");
                Double confidence = ToolArgs.has(value, "minimum_confidence")
                        ? ToolArgs.getDoubleOrNull(value, "minimum_confidence") : 0.6;
                if (!validText(subject, 256) || !validText(predicate, 128)
                        || !validText(expected, 256) || confidence == null
                        || confidence < 0.0 || confidence > 1.0) {
                    reply.accept(ToolArgs.errorJson("Goal condition " + index
                            + " contains invalid fields."));
                    return;
                }
                PlanGraph.Comparison parsedComparison;
                try {
                    parsedComparison = PlanGraph.Comparison.valueOf(
                            comparison.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException invalidComparison) {
                    reply.accept(ToolArgs.errorJson("Goal condition " + index
                            + " has invalid comparison '" + comparison + "'."));
                    return;
                }
                if ((parsedComparison == PlanGraph.Comparison.AT_LEAST
                        || parsedComparison == PlanGraph.Comparison.AT_MOST)
                        && !finiteNumber(expected)) {
                    reply.accept(ToolArgs.errorJson("Goal condition " + index
                            + " requires a finite numeric value for " + comparison + "."));
                    return;
                }
                goalConditions.add(new PlanGraph.GoalCondition(subject, predicate,
                        expected, parsedComparison, confidence));
            }
        }

        var state = MineAgentEngine.getCompanion(player.companionId());
        if (state.isEmpty()) {
            reply.accept(ToolArgs.errorJson("Companion is no longer active."));
            return;
        }
        if (state.get().auction.hasRunningTask()
                || state.get().loop.skillRuntime().active()) {
            // Plan nodes own body/skill outcome bindings. Replacing them while
            // an executor is live would orphan its eventual evidence and can
            // make a failed action verify an unrelated new milestone.
            reply.accept(ToolArgs.errorJson(
                    "Cannot update the plan while a body task or skill is running; wait for its terminal result or stop it first."));
            return;
        }
        String goal = ToolArgs.getString(args, "goal", null);
        String updateMode = ToolArgs.getString(args, "update_mode", "replace")
                .trim().toLowerCase(Locale.ROOT);
        if (!"replace".equals(updateMode) && !"repair".equals(updateMode)) {
            reply.accept(ToolArgs.errorJson("'update_mode' must be 'replace' or 'repair'."));
            return;
        }
        PlanGraph.UpdateResult update = "repair".equals(updateMode)
                ? state.get().loop.planGraph().repairPlan(
                        goal, drafts, constraints, goalConditions)
                : state.get().loop.planGraph().replacePlan(
                        goal, drafts, constraints, goalConditions);
        if (update.accepted()) {
            long gameTick;
            try {
                gameTick = com.mineagent.engine.task.TaskContext.serverPlayer(player)
                        .level().getGameTime();
            } catch (RuntimeException unavailable) {
                gameTick = 0L;
            }
            state.get().loop.rollingPlanner().onPlanReplaced(goal, gameTick);
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", update.accepted());
        result.addProperty("revision", update.revision());
        result.addProperty("update_mode", updateMode);
        result.addProperty("goal_status", state.get().loop.planGraph()
                .goalStatus().name().toLowerCase(Locale.ROOT));
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

    private static boolean finiteNumber(String value) {
        try {
            return Double.isFinite(Double.parseDouble(value));
        } catch (NumberFormatException invalid) {
            return false;
        }
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
