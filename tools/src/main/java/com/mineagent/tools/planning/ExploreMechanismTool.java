package com.mineagent.tools.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.task.TaskContext;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Controls the bounded unfamiliar-mechanism learning and adaptation loop. */
public final class ExploreMechanismTool implements Tool {
    @Override public String name() { return "explore_mechanism"; }

    @Override public String description() {
        return "Learn an unfamiliar block, item, GUI, recipe, or mod rule: predict outcomes, "
                + "compose a bounded plan from verified transitions, find goal-relevant learning gaps, "
                + "propose/rank/inspect/abort experiments, or inspect cost and action metrics. State-changing probes require "
                + "an explicit reversible compensation. Rules need two independent "
                + "supporting contexts before they become reusable verified adapters.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", "propose, run, reflect, transfer, recall, activate, repair, recommend, predict, plan, curriculum, metrics, status, or abort")
                .optionalString("rule_id", "Verified rule ID for transfer")
                .optionalString("target_subject", "Observed block:<dimension>:x,y,z or entity:<UUID> subject for transfer")
                .optionalBoolean("apply", "For repair: apply a bounded verified-experience suffix repair; default false")
                .optionalString("prerequisites", "JSON array of up to 12 observed conditions: subject, predicate, value, comparison (equals/present/at_least/at_most), minimum_confidence")
                .optionalInteger("max_steps", "For plan: maximum learned transitions to compose", 1, 6)
                .optionalString("query", "For curriculum: current objective; blank explores observed knowledge gaps")
                .optionalString("before_value", "For predict/plan: observed pre-action value; plan requires a concrete observed state")
                .optionalString("experiment_id", "Experiment ID for status or abort")
                .optionalString("subject", "Registered ID or concise identity of the unfamiliar mechanism")
                .optionalString("hypothesis", "One falsifiable behavior hypothesis")
                .optionalString("probe_tool", "Existing safe tool to call exactly once after proposal")
                .optionalString("probe_arguments", "JSON arguments intended for the probe tool")
                .optionalString("expected_subject", "Semantic fact subject expected after the probe, e.g. tool:inspect_gui, inventory:mod:item, or profile:menu:mod:machine")
                .optionalString("expected_predicate", "Semantic predicate such as outcome, result.menu_type, count, or attribute.slot.0.occupied")
                .optionalString("expected_value", "Expected semantic value; use * only when existence is sufficient")
                .optionalString("context", "Independent setup identity, e.g. dimension+position+input configuration")
                .optionalString("risk", "low, medium, or high; high is always rejected")
                .optionalBoolean("reversible", "Whether the exact pre-probe state can be restored")
                .optionalString("compensation_tool", "Allowlisted compensation tool required for a state-changing probe; this is not a generic transaction rollback")
                .optionalString("compensation_arguments", "JSON arguments for the compensation tool")
                .optionalInteger("estimated_cost", "Budget units consumed by this probe", 1, 12)
                .nullableNumber("information_gain", "Expected uncertainty reduction from 0 to 1")
                .optionalString("candidate_probes", "For recommend: JSON array of candidate probe objects")
                .optionalString("reason", "Abort reason")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson("Agent loop is unavailable."));
            return;
        }
        String mode = ToolArgs.getString(args, "mode", "status")
                .toLowerCase(Locale.ROOT);
        long gameTick;
        try {
            gameTick = TaskContext.serverPlayer(player).level().getGameTime();
        } catch (RuntimeException unavailable) {
            reply.accept(ToolArgs.errorJson("Live server state is unavailable."));
            return;
        }
        switch (mode) {
            case "recall" -> {
                JsonObject result=new JsonObject();result.addProperty("success",true);
                var explorer=loop.mechanismExplorer();String query=ToolArgs.getString(args,"query","");
                result.add("archived_rules",new com.google.gson.Gson().toJsonTree(explorer.knowledgeBase().recallArchived(query,8)));
                result.addProperty("task_history",explorer.taskMemory().render(query,explorer.knowledgeBase().currentFingerprint().toString(),2400));
                result.addProperty("authority","History requires current target observations; it does not prove current goal completion");
                reply.accept(result.toString());
            }
            case "activate" -> {
                var rule=loop.mechanismExplorer().activateArchived(ToolArgs.getString(args,"rule_id",null),gameTick);
                JsonObject result=new JsonObject();result.addProperty("success",rule!=null);
                result.add("rule",new com.google.gson.Gson().toJsonTree(rule));
                if(rule==null)result.addProperty("error","Rule is missing, stale or unverified; local revalidation is required");
                reply.accept(result.toString());
            }
            case "transfer" -> reply.accept(new com.google.gson.Gson().toJson(
                    loop.mechanismExplorer().transfer(ToolArgs.getString(args, "rule_id", null),
                            ToolArgs.getString(args, "target_subject", null), player.dimensionKey(), gameTick)));
            case "repair" -> {
                loop.mechanismExplorer().restorePlanEvidence(loop.planner(),gameTick);
                var suggestion = com.mineagent.engine.planning.ExperienceRepairPlanner.suggest(
                        loop.planner(), loop.semanticWorldModel(), loop.mechanismExplorer().knowledgeBase(), gameTick);
                JsonObject result = new com.google.gson.Gson().toJsonTree(suggestion).getAsJsonObject();
                result.addProperty("success", true);
                result.addProperty("applied", false);
                if (ToolArgs.getBool(args, "apply", false) && suggestion.found()) {
                    var state = loop.planner().exportState();
                    if (loop.hasActiveExecutor()) {
                        result.addProperty("success", false);
                        result.addProperty("error", "An active executor must finish before plan repair");
                    } else {
                        var update = loop.planner().repairPlan(state.goal(), suggestion.drafts(), state.constraints(), state.goalConditions());
                        result.add("update", new com.google.gson.Gson().toJsonTree(update));
                        result.addProperty("success", update.accepted());
                        result.addProperty("applied", update.accepted());
                        if (!update.accepted()) result.addProperty("error", "Plan repair was rejected; inspect update warnings");
                    }
                }
                reply.accept(result.toString());
            }
            case "run" -> {
                var result = loop.runMechanismProbe(ToolArgs.getString(args, "experiment_id", null));
                JsonObject response = new JsonObject();
                response.addProperty("success", result.accepted());
                response.addProperty("pending", result.asynchronous());
                response.addProperty("message", result.evidence());
                if (!result.accepted()) response.addProperty("error", result.evidence());
                reply.accept(response.toString());
            }
            case "reflect" -> {
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.add("reflections", new com.google.gson.Gson().toJsonTree(
                        loop.mechanismExplorer().reflect(ToolArgs.getString(args, "query", ""), gameTick, 5)));
                reply.accept(result.toString());
            }
            case "propose" -> {
                try { propose(args, loop.mechanismExplorer(), gameTick, reply); }
                catch (IllegalArgumentException invalid) { reply.accept(ToolArgs.errorJson(invalid.getMessage())); }
            }
            case "recommend" -> recommend(args, loop.mechanismExplorer(), gameTick, reply);
            case "predict" -> {
                String subject = ToolArgs.getString(args, "subject", null);
                String tool = ToolArgs.getString(args, "probe_tool", null);
                String predicate = ToolArgs.getString(args, "expected_predicate", null);
                if (subject == null || tool == null || predicate == null) {
                    reply.accept(ToolArgs.errorJson("predict requires subject, probe_tool and expected_predicate."));
                    return;
                }
                try {
                    var prediction = loop.mechanismExplorer().predict(subject, tool,
                            ToolArgs.getString(args, "probe_arguments", "{}"), predicate,
                            ToolArgs.getString(args, "before_value", "<absent>"),
                            ToolArgs.getString(args, "expected_subject", "tool:" + tool), prerequisites(args), gameTick);
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.add("prediction", new com.google.gson.Gson().toJsonTree(prediction));
                    result.addProperty("authority", "Prediction is evidence for planning, not action permission or proof of causation");
                    if (prediction.independentContexts() == 0) result.addProperty("warning",
                            "No empirical data: entropy/gain are unestimated (0), not confidence. Observe or propose a bounded experiment.");
                    reply.accept(result.toString());
                } catch (IllegalArgumentException invalid) {
                    reply.accept(ToolArgs.errorJson(invalid.getMessage()));
                }
            }
            case "plan" -> {
                try {
                    var plan = loop.mechanismExplorer().plan(ToolArgs.getString(args, "subject", null),
                            ToolArgs.getString(args, "expected_predicate", null),
                            ToolArgs.getString(args, "before_value", null),
                            ToolArgs.getString(args, "expected_value", null),
                            ToolArgs.getInt(args, "max_steps", 4),
                            ToolArgs.getString(args, "expected_subject", null), prerequisites(args), gameTick);
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.add("plan", new com.google.gson.Gson().toJsonTree(plan));
                    reply.accept(result.toString());
                } catch (IllegalArgumentException invalid) {
                    reply.accept(ToolArgs.errorJson(invalid.getMessage()));
                }
            }
            case "curriculum" -> {
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.add("opportunities", new com.google.gson.Gson().toJsonTree(
                        loop.mechanismExplorer().curriculum(ToolArgs.getString(args, "query", ""), gameTick, 5)));
                result.addProperty("hint", "Select an owner-relevant gap, predict, then propose one bounded experiment. No action was dispatched.");
                reply.accept(result.toString());
            }
            case "metrics" -> {
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                var metrics = loop.metricsSnapshot();
                result.addProperty("llm_calls", metrics.llmCalls());
                result.addProperty("input_tokens", metrics.inputTokens());
                result.addProperty("cached_input_tokens", metrics.cachedInputTokens());
                result.addProperty("cache_write_tokens", metrics.cacheWriteTokens());
                result.addProperty("output_tokens", metrics.outputTokens());
                if (loop.cacheCoverage().inputTokensWithReadReporting() > 0)
                    result.addProperty("provider_cache_hit_rate", metrics.providerCacheHitRate());
                else result.add("provider_cache_hit_rate", com.google.gson.JsonNull.INSTANCE);
                result.add("cache_reporting", new com.google.gson.Gson().toJsonTree(loop.cacheCoverage()));
                result.add("experience_context", new com.google.gson.Gson().toJsonTree(loop.experienceContextReuse()));
                result.addProperty("verified_action_successes", metrics.successfulActions());
                result.addProperty("action_failures", metrics.failedActions());
                result.add("operations", new com.google.gson.Gson().toJsonTree(loop.metricOperations()));
                reply.accept(result.toString());
            }
            case "abort" -> {
                boolean aborted = loop.mechanismExplorer().abort(
                        ToolArgs.getString(args, "experiment_id", null),
                        ToolArgs.getString(args, "reason", "aborted by planner"), gameTick);
                JsonObject result = new JsonObject();
                result.addProperty("success", aborted);
                if (!aborted) result.addProperty("error", "No active matching experiment.");
                reply.accept(result.toString());
            }
            case "status" -> status(loop.mechanismExplorer(),
                    ToolArgs.getString(args, "experiment_id", null), reply);
            default -> reply.accept(ToolArgs.errorJson(
                    "mode must be propose, recommend, predict, plan, curriculum, metrics, status, or abort."));
        }
    }

    private static void propose(JsonObject args, MechanismExplorer explorer,
                                long gameTick, Consumer<String> reply) {
        String subject = ToolArgs.getString(args, "subject");
        String hypothesis = ToolArgs.getString(args, "hypothesis");
        String probeTool = ToolArgs.getString(args, "probe_tool");
        if (subject == null || hypothesis == null || probeTool == null) {
            reply.accept(ToolArgs.errorJson(
                    "propose requires subject, hypothesis, and probe_tool."));
            return;
        }
        MechanismExplorer.Risk risk = parseRisk(ToolArgs.getString(args, "risk", "low"));
        var proposal = explorer.propose(subject, hypothesis, probeTool,
                ToolArgs.getString(args, "probe_arguments", "{}"),
                ToolArgs.getString(args, "expected_subject", null),
                ToolArgs.getString(args, "expected_predicate", null),
                ToolArgs.getString(args, "expected_value", null),
                risk, ToolArgs.getString(args, "context",
                        subject + "|" + ToolArgs.getString(args, "probe_arguments", "{}")),
                ToolArgs.getBool(args, "reversible", risk == MechanismExplorer.Risk.LOW),
                ToolArgs.getString(args, "compensation_tool", null),
                ToolArgs.getString(args, "compensation_arguments", "{}"),
                ToolArgs.getInt(args, "estimated_cost",
                        risk == MechanismExplorer.Risk.MEDIUM ? 4 : 1),
                ToolArgs.getDouble(args, "information_gain", 0.6), gameTick, prerequisites(args));
        JsonObject result = new JsonObject();
        result.addProperty("success", proposal.accepted());
        result.addProperty("message", proposal.message());
        if (proposal.experiment() != null) {
            result.add("experiment", json(proposal.experiment()));
        }
        if (!proposal.accepted()) result.addProperty("error", proposal.message());
        reply.accept(result.toString());
    }

    private static void recommend(JsonObject args, MechanismExplorer explorer,
                                  long gameTick, Consumer<String> reply) {
        String subject = ToolArgs.getString(args, "subject");
        JsonArray values = ToolArgs.getArray(args, "candidate_probes");
        if (subject == null || values == null || values.isEmpty() || values.size() > 12) {
            reply.accept(ToolArgs.errorJson(
                    "recommend requires subject and 1-12 candidate_probes."));
            return;
        }
        java.util.ArrayList<MechanismExplorer.ProbeCandidate> candidates = new java.util.ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String tool = ToolArgs.getString(value, "tool");
            if (tool == null) continue;
            MechanismExplorer.Risk risk = parseRisk(ToolArgs.getString(value, "risk", "low"));
            candidates.add(new MechanismExplorer.ProbeCandidate(tool,
                    jsonObjectString(value, "args"), risk,
                    ToolArgs.getBool(value, "reversible", risk == MechanismExplorer.Risk.LOW),
                    ToolArgs.getString(value, "compensation_tool", null),
                    jsonObjectString(value, "compensation_args"),
                    ToolArgs.getInt(value, "estimated_cost",
                            risk == MechanismExplorer.Risk.MEDIUM ? 4 : 1),
                    ToolArgs.getDouble(value, "information_gain", 0.5)));
        }
        var recommendation = explorer.recommend(candidates, subject, gameTick);
        JsonObject result = new JsonObject();
        result.addProperty("success", recommendation.candidate() != null);
        result.addProperty("message", recommendation.message());
        if (recommendation.candidate() != null) {
            var candidate = recommendation.candidate();
            JsonObject selected = new JsonObject();
            selected.addProperty("tool", candidate.tool());
            selected.addProperty("arguments", candidate.arguments());
            selected.addProperty("risk", candidate.risk().name().toLowerCase(Locale.ROOT));
            selected.addProperty("reversible", candidate.reversible());
            if (candidate.compensationTool() != null) {
                selected.addProperty("compensation_tool", candidate.compensationTool());
                selected.addProperty("compensation_arguments", candidate.compensationArguments());
            }
            selected.addProperty("estimated_cost", candidate.estimatedCost());
            selected.addProperty("information_gain", candidate.estimatedInformationGain());
            selected.addProperty("score", recommendation.score());
            result.add("candidate", selected);
        } else result.addProperty("error", recommendation.message());
        reply.accept(result.toString());
    }

    private static void status(MechanismExplorer explorer, String experimentId,
                               Consumer<String> reply) {
        JsonObject result = new JsonObject();
        if (experimentId != null) {
            var experiment = explorer.get(experimentId);
            if (experiment == null) {
                reply.accept(ToolArgs.errorJson("Unknown experiment: " + experimentId));
                return;
            }
            result.add("experiment", json(experiment));
        } else {
            JsonArray values = new JsonArray();
            explorer.all().stream().skip(Math.max(0, explorer.all().size() - 8L))
                    .forEach(experiment -> values.add(json(experiment)));
            result.add("experiments", values);
        }
        result.addProperty("success", true);
        reply.accept(result.toString());
    }

    private static JsonObject json(MechanismExplorer.Experiment value) {
        JsonObject out = new JsonObject();
        out.addProperty("id", value.id());
        out.addProperty("subject", value.subject());
        out.addProperty("hypothesis", value.hypothesis());
        out.addProperty("probe_tool", value.probeTool());
        out.addProperty("probe_arguments", value.probeArguments());
        out.addProperty("expected", value.expectedSubject() + " "
                + value.expectedPredicate() + "=" + value.expectedValue());
        out.addProperty("risk", value.risk().name().toLowerCase(Locale.ROOT));
        out.addProperty("status", value.status().name().toLowerCase(Locale.ROOT));
        out.addProperty("context", value.contextKey());
        out.addProperty("cost", value.estimatedCost());
        out.addProperty("information_gain", value.estimatedInformationGain());
        out.addProperty("reversible", value.reversible());
        out.add("prerequisites", new com.google.gson.Gson().toJsonTree(value.prerequisites()));
        if (value.compensationTool() != null) {
            out.addProperty("compensation_tool", value.compensationTool());
            out.addProperty("compensation_attempted", value.compensationAttempted());
            out.addProperty("compensation_succeeded", value.compensationSucceeded());
        }
        out.addProperty("evidence", value.evidence());
        return out;
    }

    private static MechanismExplorer.Risk parseRisk(String value) {
        return switch (value == null ? "low" : value.toLowerCase(Locale.ROOT)) {
            case "high" -> MechanismExplorer.Risk.HIGH;
            case "medium" -> MechanismExplorer.Risk.MEDIUM;
            default -> MechanismExplorer.Risk.LOW;
        };
    }

    private static java.util.List<com.mineagent.engine.planning.PlanGraph.GoalCondition> prerequisites(JsonObject args) {
        return readConditions(args, "prerequisites");
    }

    public static java.util.List<com.mineagent.engine.planning.PlanGraph.GoalCondition> readConditions(JsonObject args, String field) {
        if (!args.has(field) || args.get(field).isJsonNull()) return java.util.List.of();
        JsonArray values = ToolArgs.getArray(args, field);
        if (values == null || values.size() > 12) throw new IllegalArgumentException("prerequisites must be an array of at most 12 conditions");
        var result = new java.util.ArrayList<com.mineagent.engine.planning.PlanGraph.GoalCondition>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each prerequisite must be an object");
            JsonObject condition = element.getAsJsonObject();
            String subject = ToolArgs.getString(condition, "subject");
            String predicate = ToolArgs.getString(condition, "predicate");
            if (subject == null || subject.isBlank() || predicate == null || predicate.isBlank())
                throw new IllegalArgumentException("Prerequisites require subject and predicate");
            var comparison = com.mineagent.engine.planning.PlanGraph.Comparison.valueOf(
                    ToolArgs.getString(condition, "comparison", "equals").toUpperCase(Locale.ROOT));
            String value = ToolArgs.getString(condition, "value", "*");
            if (comparison == com.mineagent.engine.planning.PlanGraph.Comparison.AT_LEAST
                    || comparison == com.mineagent.engine.planning.PlanGraph.Comparison.AT_MOST) {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Prerequisite numeric values must be finite");
            }
            double confidence = ToolArgs.getDouble(condition, "minimum_confidence", 0.7);
            if (!Double.isFinite(confidence) || confidence < 0.6 || confidence > 1)
                throw new IllegalArgumentException("Prerequisite confidence must be between 0.6 and 1");
            result.add(new com.mineagent.engine.planning.PlanGraph.GoalCondition(subject, predicate, value, comparison, confidence));
        }
        return com.mineagent.engine.exploration.LearningConditions.checked(result);
    }

    private static String jsonObjectString(JsonObject source, String key) {
        JsonObject value = ToolArgs.getObject(source, key);
        return value == null ? "{}" : value.toString();
    }
}
