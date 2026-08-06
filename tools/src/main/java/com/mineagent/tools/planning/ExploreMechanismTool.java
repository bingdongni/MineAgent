package com.mineagent.tools.planning;

import com.google.gson.JsonArray;
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

/** Controls one bounded, hypothesis-driven experiment at a time. */
public final class ExploreMechanismTool implements Tool {
    @Override public String name() { return "explore_mechanism"; }

    @Override public String description() {
        return "Propose, inspect, or abort a controlled experiment for an unfamiliar "
                + "block, item, GUI, recipe, or mod rule. A proposal arms exactly one "
                + "existing low/medium-risk probe tool and a semantic postcondition; call "
                + "that probe next. The hypothesis is supported only after the declared "
                + "postcondition is observed.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", "propose, status, or abort")
                .optionalString("experiment_id", "Experiment ID for status or abort")
                .optionalString("subject", "Registered ID or concise identity of the unfamiliar mechanism")
                .optionalString("hypothesis", "One falsifiable behavior hypothesis")
                .optionalString("probe_tool", "Existing safe tool to call exactly once after proposal")
                .optionalString("probe_arguments", "JSON arguments intended for the probe tool")
                .optionalString("expected_subject", "Semantic fact subject expected after the probe, e.g. tool:inspect_gui or inventory:mod:item")
                .optionalString("expected_predicate", "Semantic predicate such as outcome, result.menu_type, or count")
                .optionalString("expected_value", "Expected semantic value; use * only when existence is sufficient")
                .optionalString("risk", "low or medium")
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
            default -> reply.accept(ToolArgs.errorJson("mode must be propose, status, or abort."));
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
        MechanismExplorer.Risk risk = "medium".equalsIgnoreCase(
                ToolArgs.getString(args, "risk", "low"))
                ? MechanismExplorer.Risk.MEDIUM : MechanismExplorer.Risk.LOW;
        var proposal = explorer.propose(subject, hypothesis, probeTool,
                ToolArgs.getString(args, "probe_arguments", "{}"),
                ToolArgs.getString(args, "expected_subject", null),
                ToolArgs.getString(args, "expected_predicate", null),
                ToolArgs.getString(args, "expected_value", null),
                risk, gameTick);
        JsonObject result = new JsonObject();
        result.addProperty("success", proposal.accepted());
        result.addProperty("message", proposal.message());
        if (proposal.experiment() != null) {
            result.add("experiment", json(proposal.experiment()));
        }
        if (!proposal.accepted()) result.addProperty("error", proposal.message());
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
        out.addProperty("evidence", value.evidence());
        return out;
    }
}
