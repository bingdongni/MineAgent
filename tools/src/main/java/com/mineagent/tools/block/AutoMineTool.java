package com.mineagent.tools.block;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Mine blocks by type and quantity. The companion will navigate to and break
 * blocks matching the specified type until the desired count is collected or
 * no more matching blocks are reachable.
 *
 * <p>This is an <b>async</b> tool - it dispatches a MineBlockTaskRecord
 * and returns a task_id immediately.
 */
public class AutoMineTool implements Tool {

    @Override
    public String name() { return "auto_mine"; }

    @Override
    public String description() {
        return """
            Mine blocks of a specified type and quantity. The companion will
            navigate to and break matching blocks until the desired count is
            collected or no more are reachable within range.
            
            Returns a task_id for tracking. The companion will pick up dropped
            items automatically.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_type", "Block ID to mine (e.g. 'minecraft:iron_ore', 'minecraft:oak_log')")
                .integer("count", "Number of blocks to mine (1-64)", 1, 64)
                .optionalInteger("radius", "Search radius in blocks (1-32, default 16)", 1, 32)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String blockType = ToolArgs.getString(args, "block_type");
        if (blockType == null) {
            reply.accept("{\"error\":\"Missing required parameter 'block_type'.\"}");
            return;
        }
        var blockId = net.minecraft.resources.ResourceLocation.tryParse(blockType);
        if (blockId == null
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(blockId)) {
            reply.accept(ToolArgs.errorJson("Unknown block type: " + blockType));
            return;
        }
        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (requestedCount == null) {
            reply.accept("{\"error\":\"count must be a valid integer.\"}");
            return;
        }
        if (requestedCount < 1 || requestedCount > 64) {
            reply.accept("{\"error\":\"count must be between 1 and 64.\"}");
            return;
        }
        int count = requestedCount;
        Integer parsedRadius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 16;
        if (parsedRadius == null || parsedRadius < 1 || parsedRadius > 32) {
            reply.accept("{\"error\":\"radius must be an integer between 1 and 32.\"}");
            return;
        }
        int radius = parsedRadius;

        var record = new MineBlockTaskRecord(toolCallId, blockType, count, radius);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for auto-mining. */
    public static class MineBlockTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String blockType;
        public final int count;
        public final int radius;

        public MineBlockTaskRecord(String toolCallId, String blockType, int count, int radius) {
            super(toolCallId);
            this.blockType = blockType;
            this.count = count;
            this.radius = radius;
        }
    }
}
