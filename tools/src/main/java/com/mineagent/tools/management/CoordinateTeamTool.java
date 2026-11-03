package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.cognition.TeamBlackboard;
import com.mineagent.engine.cognition.TeamWorkBoard;
import com.mineagent.engine.task.TaskContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Explicit coordination channel backed by live, owner-scoped team state. */
public final class CoordinateTeamTool implements Tool {
    private static final int MAX_TEXT = 512;
    private static final java.util.Set<String> PRIORITIES =
            java.util.Set.of("low", "medium", "high", "critical");

    @Override public String name() { return "coordinate_team"; }

    @Override
    public String description() {
        return "Coordinate owner-scoped jobs with dependencies and exclusive resource leases; verify completion from fresh world facts. "
                + "Share a verified experience as a teammate hypothesis, which still requires two local supporting contexts. "
                + "Also inspect team state, commit a role, request support, or clear your commitment.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>(Schema.object()
                .string("action", "One of: status, commit, request_support, clear")
                .optionalString("objective", "Objective to claim when action=commit")
                .optionalString("role", "Team role, such as builder, defender, scout or gatherer")
                .optionalString("request", "Concrete help required when action=request_support")
                .optionalString("priority", "low, medium, high or critical")
                .optionalString("target", "Optional coordinate, entity or asset target")
                .optionalString("job_id", "Immutable job ID for post_job, claim_job or complete_job")
                .optionalString("resource", "Exact shared resource, e.g. block:minecraft:overworld:10,64,20; transfer_port respects this lease")
                .optionalString("dependencies", "JSON array of existing predecessor job IDs")
                .optionalString("acceptance", "JSON array of observed goal conditions: subject, predicate, value, comparison, minimum_confidence")
                .optionalString("rule_id", "Own verified rule for share_experience, or shared candidate ID for adopt_experience")
                .optionalString("query", "Optional objective when listing team_experiences")
                .build());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties = new java.util.LinkedHashMap<>(
                (Map<String, Map<String, Object>>) schema.get("properties"));
        properties.put("action", Map.of("type", "string",
                "enum", List.of("status", "commit", "request_support", "clear", "post_job", "claim_job", "complete_job",
                        "share_experience", "team_experiences", "adopt_experience"),
                "description", "Coordination operation"));
        schema.put("properties", java.util.Collections.unmodifiableMap(properties));
        return java.util.Collections.unmodifiableMap(schema);
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String action = ToolArgs.getString(args, "action");
        if (action == null) {
            reply.accept(ToolArgs.errorJson("'action' is required."));
            return;
        }
        long gameTick;
        try {
            gameTick = TaskContext.serverPlayer(player).level().getGameTime();
        } catch (RuntimeException failure) {
            reply.accept(ToolArgs.errorJson("Companion is no longer active."));
            return;
        }

        JsonObject result = new JsonObject();
        switch (action) {
            case "status" -> {
                result.addProperty("success", true);
                result.addProperty("team", TeamBlackboard.summarize(
                        player.ownerUuid(), player.companionId(), gameTick));
                result.add("jobs", new com.google.gson.Gson().toJsonTree(TeamWorkBoard.jobs(player.ownerUuid(), gameTick)));
            }
            case "commit" -> {
                String objective = bounded(args, "objective");
                if (objective == null) {
                    reply.accept(ToolArgs.errorJson(
                            "'objective' is required and must be at most " + MAX_TEXT + " characters."));
                    return;
                }
                TeamBlackboard.ClaimResult claim = TeamBlackboard.tryCommit(
                        player.ownerUuid(), player.companionId(), objective,
                        defaulted(bounded(args, "role"), "flex"),
                        bounded(args, "target"), gameTick);
                result.addProperty("success", claim.accepted());
                result.addProperty("committed", claim.accepted());
                result.addProperty("reason", claim.reason());
            }
            case "request_support" -> {
                String request = bounded(args, "request");
                if (request == null) {
                    reply.accept(ToolArgs.errorJson(
                            "'request' is required and must be at most " + MAX_TEXT + " characters."));
                    return;
                }
                String priority = defaulted(bounded(args, "priority"), "high");
                if (!PRIORITIES.contains(priority)) {
                    reply.accept(ToolArgs.errorJson(
                            "'priority' must be low, medium, high or critical."));
                    return;
                }
                String target = bounded(args, "target");
                TeamBlackboard.requestSupport(player.ownerUuid(), player.companionId(),
                        request, priority, target, gameTick);
                // Support is exceptional and time-sensitive, so it is the one
                // blackboard mutation that explicitly wakes sibling planners.
                MineAgentEngine.broadcastToOtherCompanions(player.companionId(),
                        player.ownerUuid(), "[TEAM] SUPPORT priority=" + priority
                                + " request=" + request
                                + (target == null ? "" : " target=" + target));
                result.addProperty("success", true);
                result.addProperty("support_requested", request);
            }
            case "clear" -> {
                TeamBlackboard.clearCommitment(player.ownerUuid(), player.companionId());
                result.addProperty("success", true);
                result.addProperty("cleared", true);
            }
            case "post_job", "claim_job", "complete_job", "share_experience", "team_experiences", "adopt_experience" -> {
                try { result = executeJob(action,args,player,gameTick); }
                catch (IllegalArgumentException invalid) { reply.accept(ToolArgs.errorJson(invalid.getMessage())); return; }
            }
            default -> {
                reply.accept(ToolArgs.errorJson("Unknown action: " + action));
                return;
            }
        }
        reply.accept(result.toString());
    }

    private static JsonObject executeJob(String action,JsonObject args,AgentPlayer player,long tick) {
        var result=new JsonObject();var gson=new com.google.gson.Gson();
        var owner=player.ownerUuid();var self=player.companionId();
        if (action.equals("post_job")||action.equals("claim_job")||action.equals("complete_job")) {
            TeamWorkBoard.Result response;
            String id=bounded(args,"job_id");
            if (action.equals("post_job")) {
                var dependencies=new java.util.ArrayList<String>();
                if(args.has("dependencies")) {
                    var values=ToolArgs.getArray(args,"dependencies");
                    if(values==null||values.size()>12)throw new IllegalArgumentException("At most 12 predecessor IDs are allowed");
                    for(var value:values) {
                        if(!value.isJsonPrimitive()||!value.getAsString().matches("[a-zA-Z0-9_-]{1,64}"))
                            throw new IllegalArgumentException("Invalid predecessor ID");
                        dependencies.add(value.getAsString());
                    }
                }
                response=TeamWorkBoard.post(owner,self,id,bounded(args,"objective"),bounded(args,"resource"),dependencies,
                        com.mineagent.tools.planning.ExploreMechanismTool.readConditions(args,"acceptance"),tick);
            } else if(action.equals("claim_job")) response=TeamWorkBoard.claim(owner,self,id,tick);
            else {
                var loop=TaskContext.agentLoop(player);
                if(loop==null)throw new IllegalArgumentException("Active loop is required for observed completion");
                loop.refreshServerObservation();
                response=TeamWorkBoard.complete(owner,self,id,loop.semanticWorldModel(),tick);
            }
            result.addProperty("success",response.accepted());result.addProperty("message",response.message());
            result.add("job",gson.toJsonTree(response.job()));return result;
        }
        var loop=TaskContext.agentLoop(player);
        if(loop==null)throw new IllegalArgumentException("Active loop is required for team experience");
        var explorer=loop.mechanismExplorer();var kb=explorer.knowledgeBase();
        if(action.equals("share_experience")) {
            String id=bounded(args,"rule_id");
            var rule=kb.archivedRules().stream().filter(r->r.id().equals(id)).findFirst().orElse(null);
            boolean shared=TeamWorkBoard.share(owner,self,rule,kb.currentFingerprint(),tick);
            result.addProperty("success",shared);result.addProperty("message",shared?"Shared as an untrusted teammate hypothesis":"Only a current locally verified rule can be shared");
        } else {
            var hints=TeamWorkBoard.hints(owner,self,kb.currentFingerprint(),bounded(args,"query"),tick);
            if(action.equals("team_experiences")) {
                result.addProperty("success",true);result.add("hypotheses",gson.toJsonTree(hints));
                result.addProperty("authority","Historical teammate evidence only; adopt then rebind and verify in two independent local contexts");
            } else {
                String id=bounded(args,"rule_id");
                var source=TeamWorkBoard.hint(owner,self,kb.currentFingerprint(),id,tick).map(TeamWorkBoard.Hint::rule).orElse(null);
                var candidate=explorer.seedTeamCandidate(source,tick);
                result.addProperty("success",candidate!=null);result.add("candidate",gson.toJsonTree(candidate));
                result.addProperty("message",candidate!=null?"Candidate has zero local supports and no executor; observe, rebind and test it locally":"No matching hint, or local evidence already exists; existing evidence is preserved");
            }
        }
        return result;
    }

    private static String bounded(JsonObject args, String name) {
        String value = ToolArgs.getString(args, name, null);
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) return null;
        return value.trim();
    }

    private static String defaulted(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
