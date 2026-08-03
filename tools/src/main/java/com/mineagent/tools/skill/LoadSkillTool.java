package com.mineagent.tools.skill;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.skill.SkillInfo;
import com.mineagent.api.agent.skill.SkillRegistry;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Load a skill document into the conversation context.
 * Skills teach the AI how to use specific capabilities or play mods.
 *
 * <p>This is a <b>sync</b> tool - replies immediately with the skill content.
 */
public class LoadSkillTool implements Tool {

    @Override
    public String name() { return "load_skill"; }

    @Override
    public String description() {
        return """
            Load a skill document into your context. Skills teach you how to
            use specific capabilities (combat, crafting, mods, etc.).
            The skill content becomes part of your knowledge for this session.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("skill_name", "Name of the skill to load (e.g. 'combat_basics', 'nether_entry')")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String skillName = ToolArgs.getString(args, "skill_name");
        if (skillName == null) {
            reply.accept("{\"error\":\"Missing required parameter 'skill_name'.\"}");
            return;
        }

        var skill = SkillRegistry.get(skillName);
        if (skill.isEmpty()) {
            // List available skills
            StringBuilder available = new StringBuilder();
            available.append("{\"error\":").append(new com.google.gson.Gson().toJson("Skill not found: " + skillName));
            available.append(",\"available_skills\":[");
            boolean first = true;
            for (SkillInfo s : SkillRegistry.all()) {
                if (!first) available.append(",");
                available.append(new com.google.gson.Gson().toJson(s.name()));
                first = false;
            }
            available.append("]}");
            reply.accept(available.toString());
            return;
        }

        reply.accept(skill.get().content());
    }
}
