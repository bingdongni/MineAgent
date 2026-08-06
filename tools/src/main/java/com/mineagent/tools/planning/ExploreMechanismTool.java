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
        return "Propose, rank, inspect, or abort a budgeted experiment for an unfamiliar "
                + "block, item, GUI, recipe, or mod rule. State-changing probes require "
                + "an explicit reversible compensation. Rules need two independent "
                + "supporting contexts before they become reusable verified adapters.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", "propose, recommend, status, or abort")
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
            case "propose" -> propose(args, loop.mechanismExplorer(), gameTick, reply);
            case "recommend" -> recommend(args, loop.mechanismExplorer(), gameTick, reply);
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
                    "mode must be propose, recommend, status, or abort."));
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
                ToolArgs.getDouble(args, "information_gain", 0.6), gameTick);
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
        out.addProperty("expected", value.expectedSubject() + " "
                + value.expectedPredicate() + "=" + value.expectedValue());
        out.addProperty("risk", value.risk().name().toLowerCase(Locale.ROOT));
        out.addProperty("status", value.status().name().toLowerCase(Locale.ROOT));
        out.addProperty("context", value.contextKey());
        out.addProperty("cost", value.estimatedCost());
        out.addProperty("information_gain", value.estimatedInformationGain());
        out.addProperty("reversible", value.reversible());
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

    private static String jsonObjectString(JsonObject source, String key) {
        JsonObject value = ToolArgs.getObject(source, key);
        return value == null ? "{}" : value.toString();
    }
}
