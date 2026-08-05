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
            List tools that are not included in the default prompt. These are
            typically tools loaded dynamically via skills or plugins. Returns
            each extra tool's name, description, and parameter schema.
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

            JsonObject toolJson = new JsonObject();
            toolJson.addProperty("name", tool.name());
            toolJson.addProperty("description", tool.description());
            // The tool promises a discoverable callable contract. Returning
            // only prose left the LLM unable to construct valid arguments for
            // the very tools this endpoint revealed.
            toolJson.add("parameters", new com.google.gson.Gson()
                    .toJsonTree(tool.parameterSchema()));
            extraTools.add(toolJson);
            exposedNames.add(tool.name());
            count++;
        }

        // Discovery must change the next tool round, not merely describe
        // schemas that remain absent from the provider request.
        MineAgentEngine.getCompanion(player.companionId())
                .ifPresent(state -> state.loop.exposeExtraTools(exposedNames));

        // allTools.size() includes every default tool and overstated this count.
        result.add("extra_tools", extraTools);
        result.addProperty("total_extra", count);
        reply.accept(result.toString());
    }
}
