package com.mineagent.tools.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.function.Consumer;

/** Starts a learned sequence in the verifier-backed closed-loop runtime. */
public final class ExecuteSkillTool implements Tool {
    @Override public String name() { return "execute_skill"; }

    @Override public String description() {
        return "Execute a learned skill autonomously, one verified step at a time. "
                + "Use optional overrides_json such as {\"0\":{\"x\":10}} to adapt "
                + "stored step arguments. The runtime stops and requests replanning if a "
                + "precondition, task result, or declared postcondition fails.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("skill_name", "Exact learned skill name")
                .optionalString("overrides_json",
                        "Optional JSON object keyed by zero-based step index; each value patches that step's arguments")
                .build();
    }

    @Override public boolean dispatchesAsyncTask() { return true; }
    @Override public int defaultTimeoutSeconds() { return 5; }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String skillName = ToolArgs.getString(args, "skill_name");
        if (skillName == null || skillName.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'skill_name'."));
            return;
        }
        JsonObject overrides = null;
        String overrideText = ToolArgs.getString(args, "overrides_json", null);
        if (overrideText != null && !overrideText.isBlank()) {
            try {
                var parsed = JsonParser.parseString(overrideText);
                if (!parsed.isJsonObject()) {
                    reply.accept(ToolArgs.errorJson("overrides_json must encode an object."));
                    return;
                }
                overrides = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                reply.accept(ToolArgs.errorJson("Invalid overrides_json: "
                        + malformed.getMessage()));
                return;
            }
        }
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson("Agent loop is unavailable."));
            return;
        }
        var result = loop.startSkill(skillName, overrides);
        JsonObject response = new JsonObject();
        if (!result.accepted()) {
            response.addProperty("error", result.message());
            if (result.runId() != null) response.addProperty("active_run_id", result.runId());
        } else {
            response.addProperty("success", true);
            response.addProperty("async", true);
            response.addProperty("skill_run_id", result.runId());
            response.addProperty("message", result.message());
        }
        reply.accept(response.toString());
    }
}
