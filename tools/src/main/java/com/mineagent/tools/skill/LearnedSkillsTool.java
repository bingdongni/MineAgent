package com.mineagent.tools.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * List learned skills — query the AI's personal skill library.
 *
 * <p>Backed by {@link com.mineagent.engine.skill.SkillLibrary}. The
 * AgentLoop automatically records successful tool-call sequences as
 * skills (Voyager-style). This tool lets the LLM recall what it has
 * already learned, so it can reuse a skill instead of re-planning
 * from scratch.
 *
 * <p>This is the "Augments" primitive (Mindcraft dev branch): the AI
 * builds up its own library of reusable action patterns through
 * experience. Over time, common tasks become one-tool-call lookups
 * instead of multi-step LLM reasoning.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class LearnedSkillsTool implements Tool {

    @Override
    public String name() { return "list_learned_skills"; }

    @Override
    public String description() {
        return """
                List skills you've learned from past successful actions.
                Each skill is a reusable action sequence recorded when you
                completed a task successfully. Call this when starting a
                task — you may already know how to do it.

                Returns skill names, descriptions, and success rates.
                High success-rate skills (>70%) are reliable; reuse them.
                """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("query",
                        "Current objective; returns only the most relevant learned skills")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept("{\"error\":\"agent loop unavailable\"}");
            return;
        }

        var skillLib = loop.skillLibrary();
        String query = ToolArgs.getString(args, "query", "");
        var skills = skillLib.relevant(query, query.isBlank() ? 5 : 3);

        if (skills.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("count", 0);
            result.addProperty("message", skillLib.size() == 0
                    ? "No verified action skills have been learned yet."
                    : "No reliable learned skill matches this objective.");
            reply.accept(result.toString());
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("count", skills.size());
        result.addProperty("total_available", skillLib.size());

        JsonArray arr = new JsonArray();
        for (var skill : skills) {
            JsonObject s = new JsonObject();
            s.addProperty("name", skill.name());
            s.addProperty("description", skill.description());
            s.addProperty("success_rate",
                    String.format(Locale.ROOT, "%.0f%%", skill.successRate() * 100));
            s.addProperty("invocations", skill.invocations());
            arr.add(s);
        }
        result.add("skills", arr);
        result.addProperty("hint",
                "Call execute_skill with the exact skill name. The closed-loop "
                + "runtime dispatches one step at a time and verifies executor "
                + "outcomes before continuing.");
        reply.accept(result.toString());
    }
}
