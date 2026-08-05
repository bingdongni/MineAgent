package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Walk to and pick up dropped items. The companion will navigate to each
 * item entity and collect it.
 *
 * <p>This is an <b>async</b> tool - it dispatches a CollectItemsTaskRecord
 * and returns a task_id immediately.
 */
public class CollectItemsTool implements Tool {

    private static final int DEFAULT_RADIUS = 8;
    private static final int MAX_RADIUS = 32;

    @Override
    public String name() { return "collect_items"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return """
            Walk to and pick up dropped items on the ground. Specify item IDs
            to filter which items to collect, or leave empty to collect all.
            The companion will navigate within the specified radius and pick
            up matching items.
            
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("item_id", "Item ID to collect (e.g. 'minecraft:diamond'). If null, collects all items.")
                .optionalInteger("radius", "Search radius in blocks (1-32, default 8)", 1, 32)
                .optionalInteger("count", "Max number of items to collect (1-64, default 16)", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
                return;
            }
            itemId = id.toString();
        }
        Integer radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : DEFAULT_RADIUS;
        if (radius == null || radius < 1 || radius > MAX_RADIUS) {
            reply.accept(ToolArgs.errorJson("'radius' must be an integer from 1 to " + MAX_RADIUS + "."));
            return;
        }
        Integer count = ToolArgs.has(args, "count")
                ? ToolArgs.getIntOrNull(args, "count") : 16;
        if (count == null || count < 1 || count > 64) {
            reply.accept(ToolArgs.errorJson("'count' must be an integer from 1 to 64."));
            return;
        }

        var record = new CollectItemsTaskRecord(toolCallId, itemId, radius, count);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for collecting items. */
    public static class CollectItemsTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String itemId;
        public final int radius;
        public final int maxCount;

        public CollectItemsTaskRecord(String toolCallId, String itemId, int radius, int maxCount) {
            super(toolCallId);
            this.itemId = itemId;
            this.radius = radius;
            this.maxCount = maxCount;
        }
    }
}
