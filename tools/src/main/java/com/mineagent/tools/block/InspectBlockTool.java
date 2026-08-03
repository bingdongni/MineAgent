package com.mineagent.tools.block;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Get the block state at a specific position. Returns block ID,
 * block entity data (if any), and relevant properties.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class InspectBlockTool implements Tool {

    @Override
    public String name() { return "inspect_block"; }

    @Override
    public String description() {
        return """
            Get the block state at a specific position. Returns the block ID,
            relevant block state properties (e.g. orientation, powered state),
            and whether it has a block entity with extra data.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("y", "Block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Block Z coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept("{\"error\":\"x, y, and z must be valid integers.\"}");
            return;
        }

        var level = ((CompanionEntity) player).serverPlayer().level();
        var blockPos = new net.minecraft.core.BlockPos(x, y, z);
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            reply.accept("{\"error\":\"y is outside the dimension build height.\"}");
            return;
        }
        // Reading an arbitrary unloaded coordinate synchronously can load or
        // generate chunks on the server tick. Inspection is limited to state
        // that is already loaded around active players.
        if (!level.isLoaded(blockPos)) {
            reply.accept("{\"error\":\"Target position is not in a loaded chunk.\"}");
            return;
        }
        var blockState = level.getBlockState(blockPos);

        var block = blockState.getBlock();
        var blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"block_id\":\"").append(blockId).append("\"");

        // Block state properties
        var properties = blockState.getProperties();
        if (!properties.isEmpty()) {
            sb.append(",\"properties\":{");
            boolean first = true;
            for (var prop : properties) {
                if (!first) sb.append(",");
                sb.append("\"").append(prop.getName()).append("\":");
                var value = blockState.getValue(prop);
                if (value instanceof Number || value instanceof Boolean) {
                    sb.append(value);
                } else {
                    sb.append("\"").append(value.toString()).append("\"");
                }
                first = false;
            }
            sb.append("}");
        }

        // Block entity data
        var blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity != null) {
            sb.append(",\"has_block_entity\":true");
            var tag = blockEntity.saveWithoutMetadata(net.minecraft.core.HolderLookup.Provider.class.cast(level.registryAccess()));
            if (tag != null && !tag.isEmpty()) {
                // SNBT contains raw quotes (e.g. custom item names) which
                // would break the surrounding JSON — emit it as an escaped
                // JSON string value instead of embedding it raw.
                sb.append(",\"block_entity_data\":")
                  .append(new com.google.gson.Gson().toJson(tag.toString()));
            }
        } else {
            sb.append(",\"has_block_entity\":false");
        }

        // Is air / is solid
        sb.append(",\"is_air\":").append(blockState.isAir());
        sb.append(",\"is_solid\":").append(blockState.isSolidRender(level, blockPos));

        // Hardness
        sb.append(",\"hardness\":").append(blockState.getDestroySpeed(level, blockPos));

        sb.append("}");
        reply.accept(sb.toString());
    }
}
