package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;

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

    /** Set of tool names that are included in the default prompt. */
    private static final Set<String> DEFAULT_TOOLS = Set.of(
            "goto", "look_around", "scan_blocks", "get_self_status", "resolve_need",
            "auto_mine", "build", "inspect_block", "inspect_block_storage",
            "melee_attack", "ranged_attack",
            "equip_item", "eat_item", "drop_items", "collect_items", "transfer_items",
            "craft", "lookup_recipe",
            "interact_at", "interact_entity",
            "inspect_gui", "close_gui",
            "locate_structure", "locate_biome",
            "get_owner_status", "get_world_info",
            "todowrite", "task_status", "task_stop",
            "scan_nearby_entities"
    );

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
        for (var tool : allTools) {
            if (DEFAULT_TOOLS.contains(tool.name())) continue;
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
            count++;
        }

        // allTools.size() includes every default tool and overstated this count.
        result.add("extra_tools", extraTools);
        result.addProperty("total_extra", count);
        reply.accept(result.toString());
    }
}
