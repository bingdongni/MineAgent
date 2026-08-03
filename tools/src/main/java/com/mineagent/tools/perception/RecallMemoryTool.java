package com.mineagent.tools.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Recall memory — query the AI's spatial/semantic memory.
 *
 * <p>Backed by {@link com.mineagent.engine.memory.CognitiveMap} and
 * {@link com.mineagent.engine.memory.PlaceEventMemory}. This lets the
 * LLM actively recall "where did I see iron ore" or "what's nearby"
 * without re-scanning the environment.
 *
 * <p>This is critical for spatial reasoning: instead of re-exploring,
 * the AI recalls "iron was at (10, 64, -5), I can go back there".
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class RecallMemoryTool implements Tool {

    @Override
    public String name() { return "recall_memory"; }

    @Override
    public String description() {
        return """
                Recall places you've discovered. Query by category:
                - "resource:iron_ore" — where you saw iron ore
                - "resource:coal_ore" — where you saw coal
                - "resource:diamond_ore" — where you saw diamonds
                - "structure:village" — villages you've found
                - "structure:furnace" — furnaces (yours or found)
                - "hazard:creeper" — where you saw creepers
                - "hazard:lava" — lava locations
                - "chest" — chests you've seen
                - "" (empty) — all nearby points of interest

                Returns locations you can navigate back to.
                Use this BEFORE exploring new areas — you may already
                know where to find what you need.
                """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("category",
                        "Category prefix to search (e.g. 'resource:iron_ore', "
                        + "'structure:village', 'hazard:lava'). Empty = all nearby.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String category = ToolArgs.getString(args, "category", "");

        var loopOpt = com.mineagent.engine.task.TaskContext.agentLoop(player);
        if (loopOpt == null) {
            reply.accept("{\"error\":\"agent loop unavailable\"}");
            return;
        }

        var cmap = loopOpt.cognitiveMap();
        var sp = com.mineagent.engine.task.TaskContext.serverPlayer(player);
        String dimension = sp.level().dimension().location().toString();
        List<com.mineagent.engine.memory.CognitiveMap.PointOfInterest> results;

        if (category.isBlank()) {
            // All nearby (within 64 blocks of current position)
            results = cmap.findNearby(sp.blockPosition().getX(),
                    sp.blockPosition().getZ(), 64, dimension);
        } else {
            results = cmap.findByCategory(category.toLowerCase(java.util.Locale.ROOT), dimension);
        }

        if (results.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("found", false);
            result.addProperty("message", "No memory matches '" + category
                    + "'. You haven't seen any such location yet.");
            reply.accept(result.toString());
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("found", true);
        result.addProperty("count", results.size());

        JsonArray arr = new JsonArray();
        int limit = Math.min(results.size(), 10); // cap to prevent prompt bloat
        for (int i = 0; i < limit; i++) {
            var poi = results.get(i);
            JsonObject p = new JsonObject();
            p.addProperty("category", poi.category());
            p.addProperty("label", poi.label());
            p.addProperty("x", poi.x());
            p.addProperty("y", poi.y());
            p.addProperty("z", poi.z());
            p.addProperty("dimension", poi.dimension());
            arr.add(p);
        }
        result.add("locations", arr);
        result.addProperty("hint",
                "Use `goto` to navigate to any of these locations.");
        reply.accept(result.toString());
    }
}
