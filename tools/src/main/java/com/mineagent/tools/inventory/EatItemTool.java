package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Eat food from inventory. The companion will swap the food to the main hand
 * and consume it. Works with any food item.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class EatItemTool implements Tool {

    @Override
    public String name() { return "eat_item"; }

    @Override
    public String description() {
        return """
            Eat a food item from inventory. The companion will move the food
            to the main hand and consume it. Specify the food item by ID.
            If no item_id is provided, the best available food is eaten.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("item_id", "Food item ID to eat (e.g. 'minecraft:cooked_beef'). If null, eats the best food available.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null
                    || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
                return;
            }
            itemId = id.toString();
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var inv = sp.getInventory();

        // Find the food item in inventory
        int foodSlot = -1;
        float bestSaturation = -1;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            // Armor slots cannot safely be swapped with the selected hotbar
            // slot. Command-added food components can otherwise make an armor
            // item enter this path and leave invalid equipment behind.
            if (i >= 36 && i <= 39) continue;
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            var item = stack.getItem();
            if (stack.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;

            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();

            if (itemId != null) {
                // Looking for a specific food
                if (id.equals(itemId)) {
                    foodSlot = i;
                    break;
                }
            } else {
                // Find the best food (highest saturation)
                var foodProps = stack.get(net.minecraft.core.component.DataComponents.FOOD);
                if (foodProps != null && foodProps.saturation() > bestSaturation) {
                    bestSaturation = foodProps.saturation();
                    foodSlot = i;
                }
            }
        }

        if (foodSlot == -1) {
            String msg = itemId != null
                    ? "Food item '" + itemId + "' not found in inventory."
                    : "No food items found in inventory.";
            reply.accept(ToolArgs.errorJson(msg));
            return;
        }

        var foodStack = inv.getItem(foodSlot);
        String foodId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(foodStack.getItem()).toString();
        var foodProps = foodStack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (foodProps == null || !sp.canEat(foodProps.canAlwaysEat())) {
            reply.accept("{\"error\":\"Companion is not hungry enough to eat '" + foodId + "'.\"}");
            return;
        }
        float saturation = foodProps.saturation();
        int nutrition = foodProps.nutrition();

        // Move food to main hand
        int currentHeldSlot = inv.selected;
        var currentMainHand = inv.getItem(currentHeldSlot);

        // Swap food to current hotbar slot — use REAL swap, not copy().
        // The previous code used stack.copy() which duplicated the item,
        // leaving the original in the food slot AND a copy in the hotbar.
        if (foodSlot >= 0 && foodSlot < 9) {
            // Already in hotbar — just switch to it
            player.holdInHand(foodSlot);
        } else {
            // Swap from inventory to current hotbar slot
            inv.setItem(currentHeldSlot, foodStack);
            inv.setItem(foodSlot, currentMainHand);
        }

        // Consume the food immediately (sync tool semantics).
        // Do NOT call startUsingItem first — that would leave the entity in
        // "using" state, and ~32 ticks later vanilla's updateUsingItem would
        // auto-complete a SECOND eat, silently consuming another item.
        var mainHandStack = inv.getItem(inv.selected);
        var foodResult = mainHandStack.finishUsingItem(sp.level(), sp);
        inv.setItem(inv.selected, foodResult);

        // Sync inventory so the owner's client sees the food disappear
        TaskContext.syncInventory(sp);


        reply.accept("{\"success\":true,\"food\":\"" + foodId + "\",\"nutrition\":" + nutrition
                // JSON numbers must use a dot even when the host locale uses commas.
                + ",\"saturation\":" + String.format(Locale.ROOT, "%.1f", saturation) + "}");
    }
}
