package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Drop items from inventory. The companion will drop the specified
 * items onto the ground at its current position.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class DropItemsTool implements Tool {

    private static final int MAX_DROP_COUNT = 64;

    @Override
    public String name() { return "drop_items"; }

    @Override
    public String description() {
        return """
            Drop items from inventory onto the ground. Specify the item ID
            and count. The items are dropped at the companion's current position.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Item ID to drop (e.g. 'minecraft:cobblestone')")
                .integer("count", "Number of items to drop (1-64)", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId == null) {
            reply.accept("{\"error\":\"Missing required parameter 'item_id'.\"}");
            return;
        }
        var parsedItemId = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (parsedItemId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(parsedItemId)) {
            reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
            return;
        }
        // ResourceLocation accepts shorthand such as "stone" and resolves it
        // to minecraft:stone. Compare against that canonical registry ID.
        itemId = parsedItemId.toString();
        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (requestedCount == null) {
            reply.accept("{\"error\":\"count must be a valid integer.\"}");
            return;
        }
        if (requestedCount < 1 || requestedCount > MAX_DROP_COUNT) {
            reply.accept("{\"error\":\"count must be between 1 and 64.\"}");
            return;
        }
        int count = requestedCount;

        var sp = ((CompanionEntity) player).serverPlayer();
        var inv = sp.getInventory();

        // Find and count matching items
        int remaining = count;
        int dropped = 0;

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (!id.equals(itemId)) continue;

            int toDrop = Math.min(remaining, stack.getCount());
            var dropStack = stack.split(toDrop);
            sp.drop(dropStack, false, true);
            remaining -= toDrop;
            dropped += toDrop;

            if (stack.isEmpty()) {
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }

        if (dropped == 0) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId + "' not found in inventory."));
        } else {
            // Sync inventory so the owner's client sees items removed
            TaskContext.syncInventory(sp);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("item", itemId);
            result.addProperty("dropped", dropped);
            reply.accept(result.toString());
        }
    }
}
