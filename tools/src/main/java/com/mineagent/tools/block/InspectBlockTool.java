package com.mineagent.tools.block;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Reads one already-loaded block without forcing chunk generation. */
public class InspectBlockTool implements Tool {

    @Override public String name() { return "inspect_block"; }

    @Override
    public String description() {
        return "Get a loaded block's ID, state properties, block-entity data, solidity, and hardness.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X coordinate", -30_000_000, 30_000_000)
                .integer("y", "Block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Block Z coordinate", -30_000_000, 30_000_000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson("Parameters x, y, and z must be exact integers."));
            return;
        }

        var level = ((CompanionEntity) player).serverPlayer().level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            // getBlockState on an arbitrary coordinate can synchronously load
            // or generate a chunk. Perception must never stall the server that way.
            reply.accept(ToolArgs.errorJson("Block position is outside the loaded world."));
            return;
        }

        var state = level.getBlockState(pos);
        JsonObject result = new JsonObject();
        result.addProperty("block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (var property : state.getProperties()) {
                properties.addProperty(property.getName(),
                        state.getValue(property).toString());
            }
            result.add("properties", properties);
        }

        var blockEntity = level.getBlockEntity(pos);
        result.addProperty("has_block_entity", blockEntity != null);
        if (blockEntity != null) {
            var tag = blockEntity.saveWithoutMetadata(level.registryAccess());
            if (!tag.isEmpty()) result.addProperty("block_entity_data", tag.toString());
        }
        result.addProperty("is_air", state.isAir());
        result.addProperty("is_solid", state.isSolidRender(level, pos));
        result.addProperty("hardness", state.getDestroySpeed(level, pos));
        reply.accept(result.toString());
    }
}
