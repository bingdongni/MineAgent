package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Equip an item from inventory to an armor slot or hotbar. The item
 * is moved from its current inventory slot to the destination slot.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class EquipItemTool implements Tool {

    @Override
    public String name() { return "equip_item"; }

    @Override
    public String description() {
        return """
            Equip an item from inventory to an armor slot or hotbar slot.
            Supported slot names: "head", "chest", "legs", "feet" for armor,
            "hotbar_1" through "hotbar_9" for hotbar, "offhand" for off-hand.
            The item is swapped with whatever is currently in the target slot.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Item ID to equip (e.g. 'minecraft:iron_helmet')")
                .string("slot", "Target slot: head, chest, legs, feet, offhand, hotbar_1-9")
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
        itemId = parsedItemId.toString();
        String slot = ToolArgs.getString(args, "slot");
        if (slot == null) {
            reply.accept("{\"error\":\"Missing required parameter 'slot'.\"}");
            return;
        }
        slot = slot.toLowerCase(java.util.Locale.ROOT);

        var sp = ((CompanionEntity) player).serverPlayer();
        var inv = sp.getInventory();

        // Find the item in inventory
        int sourceSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (id.equals(itemId)) {
                    sourceSlot = i;
                    break;
                }
            }
        }

        if (sourceSlot == -1) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId + "' not found in inventory."));
            return;
        }

        // Resolve target slot index
        int targetSlot;
        switch (slot) {
            case "head" -> targetSlot = 39;   // helmet
            case "chest" -> targetSlot = 38;  // chestplate
            case "legs" -> targetSlot = 37;   // leggings
            case "feet" -> targetSlot = 36;   // boots
            case "offhand" -> targetSlot = 40; // off-hand
            case "hotbar_1" -> targetSlot = 0;
            case "hotbar_2" -> targetSlot = 1;
            case "hotbar_3" -> targetSlot = 2;
            case "hotbar_4" -> targetSlot = 3;
            case "hotbar_5" -> targetSlot = 4;
            case "hotbar_6" -> targetSlot = 5;
            case "hotbar_7" -> targetSlot = 6;
            case "hotbar_8" -> targetSlot = 7;
            case "hotbar_9" -> targetSlot = 8;
            default -> {
                reply.accept(ToolArgs.errorJson("Unknown slot: '" + slot
                        + "'. Use head/chest/legs/feet/offhand/hotbar_1-9."));
                return;
            }
        }

        // Swap items between source and target slots — use REAL swap, not
        // copy(). The previous code used stack.copy() which duplicated
        // items, leaving copies in both slots.
        var sourceStack = inv.getItem(sourceSlot);
        var targetStack = inv.getItem(targetSlot);
        EquipmentSlot expectedArmorSlot = switch (targetSlot) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            default -> null;
        };
        if (expectedArmorSlot != null
                && sp.getEquipmentSlotForItem(sourceStack) != expectedArmorSlot) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId
                    + "' cannot be equipped in slot '" + slot + "'."));
            return;
        }
        EquipmentSlot sourceArmorSlot = switch (sourceSlot) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            default -> null;
        };
        if (sourceArmorSlot != null && sourceSlot != targetSlot
                && !targetStack.isEmpty()
                && sp.getEquipmentSlotForItem(targetStack) != sourceArmorSlot) {
            // A swap validates both directions. Validating only the requested
            // destination could move its displaced hotbar item into an armor
            // slot where vanilla would never permit it.
            reply.accept(ToolArgs.errorJson("The item displaced from '" + slot
                    + "' cannot occupy the source armor slot."));
            return;
        }
        inv.setItem(targetSlot, sourceStack);
        inv.setItem(sourceSlot, targetStack);

        // If equipped to hotbar, switch to that slot
        if (slot.startsWith("hotbar_")) {
            int hotbarIdx = targetSlot; // slots 0-8 map to hotbar
            inv.selected = hotbarIdx;
        }

        // Sync inventory to viewers — without this the owner's client
        // never sees the equipment change on the companion's model.
        TaskContext.syncInventory(sp);

        reply.accept("{\"success\":true,\"item\":\"" + itemId + "\",\"slot\":\"" + slot + "\"}");
    }
}
