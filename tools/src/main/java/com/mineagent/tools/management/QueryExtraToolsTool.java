package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.loop.AgentLoop;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * List tools that are not included in the default prompt. Some tools
 * may be loaded dynamically (via skills) and not included in the
 * initial tool set shown to the LLM.
 *
 * <p>This is a <b>sync</b> tool - replies immediately.
 */
public class QueryExtraToolsTool implements Tool {

    @Override
    public String name() { return "query_extra_tools"; }

    @Override
    public String description() {
        return """
            Expose specialized tools that are not in the compact default tool
            surface. The next model request contains their complete callable
            schemas, and they remain exposed for the current owner goal.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var allTools = ToolRegistry.all();
        JsonObject result = new JsonObject();
        var extraTools = new com.google.gson.JsonArray();

        int count = 0;
        java.util.List<String> exposedNames = new java.util.ArrayList<>();
        for (var tool : allTools) {
            if (AgentLoop.isCoreTool(tool.name())) continue;
            // Also skip this tool itself and management tools
            if (tool.name().equals("query_extra_tools")) continue;

            // Full schemas are attached to the very next provider request.
            // Repeating them inside this tool result paid for the same tokens
            // twice and made the discovery round needlessly slow.
            extraTools.add(tool.name());
            exposedNames.add(tool.name());
            count++;
        }

        // Discovery must change the next tool round, not merely describe
        // schemas that remain absent from the provider request.
        MineAgentEngine.getCompanion(player.companionId())
                .ifPresent(state -> state.loop.exposeExtraTools(exposedNames));

        // allTools.size() includes every default tool and overstated this count.
        result.add("exposed_tools", extraTools);
        result.addProperty("total_extra", count);
        result.addProperty("next_step",
                "Call the required tool directly; its full schema is now available.");
        reply.accept(result.toString());
    }
}
