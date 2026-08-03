package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Find the nearest biome of a given type. Uses the server's biome
 * locator to find the closest matching biome.
 *
 * <p>This is an <b>async</b> tool — it dispatches a LocateBiomeTaskRecord
 * and returns a task_id immediately.
 */
public class LocateBiomeTool implements Tool {

    @Override
    public String name() { return "locate_biome"; }

    @Override
    public String description() {
        return """
            Find the nearest biome of a specified type. Common biomes:
            - "minecraft:plains" - Plains
            - "minecraft:desert" - Desert
            - "minecraft:jungle" - Jungle
            - "minecraft:taiga" - Taiga
            - "minecraft:snowy_plains" - Snowy Plains
            - "minecraft:mushroom_fields" - Mushroom Fields
            - "minecraft:swamp" - Swamp
            
            Returns a task_id for tracking. The result will contain the
            biome's position and distance.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("biome_type", "Biome type ID (e.g. 'minecraft:desert')")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String biomeType = ToolArgs.getString(args, "biome_type");
        if (biomeType == null) {
            reply.accept("{\"error\":\"Missing required parameter 'biome_type'.\"}");
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var registryAccess = level.registryAccess();

        // Resolve the biome type
        var biomeResourceLoc = net.minecraft.resources.ResourceLocation.tryParse(biomeType);
        if (biomeResourceLoc == null) {
            reply.accept(ToolArgs.errorJson("Invalid biome type: " + biomeType));
            return;
        }

        var biomeRegistry = registryAccess.registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var biomeKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BIOME, biomeResourceLoc);

        if (!biomeRegistry.containsKey(biomeKey)) {
            reply.accept(ToolArgs.errorJson(
                    "Biome type '" + biomeType + "' not found in this world."));
            return;
        }

        var record = new LocateBiomeTaskRecord(toolCallId, biomeType);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for biome location. */
    public static class LocateBiomeTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String biomeType;

        public LocateBiomeTaskRecord(String toolCallId, String biomeType) {
            super(toolCallId);
            this.biomeType = biomeType;
        }
    }
}
