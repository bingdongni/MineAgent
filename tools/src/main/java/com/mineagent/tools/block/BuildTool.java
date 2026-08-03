package com.mineagent.tools.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Place or clear blocks in a 1-512 block range. The companion will navigate
 * to each position and place/remove blocks sequentially.
 *
 * <p>This is an <b>async</b> tool — it dispatches a BuildTaskRecord
 * and returns a task_id immediately.
 */
public class BuildTool implements Tool {

    @Override
    public String name() { return "build"; }

    @Override
    public String description() {
        return """
            Place or clear blocks at specified positions. Supports two modes:
            - "place": place blocks from inventory at each position
            - "clear": break and remove blocks at each positions
            
            Provide a list of (x, y, z) positions. The companion will navigate
            to each position and place/clear blocks sequentially.
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("mode", "Build mode: 'place' or 'clear'")
                .optionalString("block_type", "Block ID to place (e.g. 'minecraft:stone'). Ignored for 'clear' mode.")
                // The old schema declared each position as a string while the
                // executor requires an integer triple. Strict providers could
                // therefore reject the only valid argument shape.
                .array("positions", "Array of [x,y,z] integer triples",
                        Map.of("type", "array",
                                "items", Map.of("type", "integer"),
                                "minItems", 3, "maxItems", 3), 1)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String mode = ToolArgs.getString(args, "mode");
        if (mode == null) {
            reply.accept("{\"error\":\"Missing required parameter 'mode'.\"}");
            return;
        }
        if (!mode.equals("place") && !mode.equals("clear")) {
            reply.accept(ToolArgs.errorJson("Invalid mode: " + mode
                    + ". Use 'place' or 'clear'."));
            return;
        }

        String blockType = ToolArgs.getString(args, "block_type");
        if (mode.equals("place")) {
            var blockId = blockType == null ? null
                    : net.minecraft.resources.ResourceLocation.tryParse(blockType);
            if (blockId == null
                    || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(blockId)
                    || net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId)
                        .asItem() == net.minecraft.world.item.Items.AIR) {
                // BuildTask searches inventory by this ID. Reject non-block
                // items and unknown IDs before consuming a scheduler slot.
                reply.accept(ToolArgs.errorJson("Unknown placeable block: " + blockType));
                return;
            }
        } else if (blockType == null) {
            // Clear mode never consumes or compares a block item.
            blockType = "minecraft:air";
        }
        JsonArray positionsArray = ToolArgs.getArray(args, "positions");
        if (positionsArray == null || positionsArray.isEmpty()) {
            reply.accept("{\"error\":\"positions must be a non-empty array.\"}");
            return;
        }
        if (positionsArray.size() > 512) {
            reply.accept("{\"error\":\"Too many positions (max 512).\"}");
            return;
        }

        int[][] positions = new int[positionsArray.size()][3];
        try {
            var level = com.mineagent.engine.task.TaskContext.serverPlayer(player).serverLevel();
            for (int i = 0; i < positionsArray.size(); i++) {
                var coord = positionsArray.get(i).getAsJsonArray();
                if (coord.size() != 3) throw new IllegalArgumentException("coordinate length");
                for (int axis = 0; axis < 3; axis++) {
                    if (!coord.get(axis).isJsonPrimitive()) {
                        throw new IllegalArgumentException("coordinate type");
                    }
                    // getAsInt truncates decimals; construction coordinates
                    // must be represented by exact 32-bit integers.
                    positions[i][axis] = new java.math.BigDecimal(
                            coord.get(axis).getAsString()).intValueExact();
                }
                if (Math.abs((long) positions[i][0]) > 30_000_000L
                        || Math.abs((long) positions[i][2]) > 30_000_000L
                        || positions[i][1] < level.getMinBuildHeight()
                        || positions[i][1] >= level.getMaxBuildHeight()) {
                    throw new IllegalArgumentException("coordinate outside world bounds");
                }
            }
        } catch (Exception e) {
            reply.accept("{\"error\":\"Each position must be [x, y, z].\"}");
            return;
        }

        var record = new BuildTaskRecord(toolCallId, mode, blockType, positions);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for building. */
    public static class BuildTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String mode;
        public final String blockType;
        public final int[][] positions;

        public BuildTaskRecord(String toolCallId, String mode, String blockType, int[][] positions) {
            super(toolCallId);
            this.mode = mode;
            this.blockType = blockType;
            this.positions = positions;
        }
    }
}
