package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Equips an existing stack by performing one real inventory swap. */
public class EquipItemTool implements Tool {

    @Override
    public String name() { return "equip_item"; }

    @Override
    public String description() {
        return """
            Equip an inventory item in head, chest, legs, feet, offhand, or
            hotbar_1 through hotbar_9. Existing target contents are swapped
            back into the source slot.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Registered item ID to equip")
                .string("slot", "head, chest, legs, feet, offhand, or hotbar_1-9")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String requestedItem = ToolArgs.getString(args, "item_id");
        ResourceLocation itemId = requestedItem == null
                ? null : ResourceLocation.tryParse(requestedItem.trim());
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            reply.accept(ToolArgs.errorJson("Unknown item: " + requestedItem));
            return;
        }

        String slotName = ToolArgs.getString(args, "slot");
        if (slotName == null || slotName.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'slot'"));
            return;
        }
        slotName = slotName.trim().toLowerCase(Locale.ROOT);

        int targetSlot = inventorySlot(slotName);
        if (targetSlot < 0) {
            reply.accept(ToolArgs.errorJson("Unknown slot '" + slotName
                    + "'; use head/chest/legs/feet/offhand/hotbar_1-9"));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var inventory = sp.getInventory();
        int sourceSlot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
                sourceSlot = i;
                break;
            }
        }
        if (sourceSlot < 0) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId + "' not found in inventory"));
            return;
        }

        var sourceStack = inventory.getItem(sourceSlot);
        EquipmentSlot equipmentSlot = equipmentSlot(slotName);
        if (equipmentSlot != null
                && sp.getEquipmentSlotForItem(sourceStack) != equipmentSlot) {
            reply.accept(ToolArgs.errorJson("Item '" + itemId
                    + "' cannot be equipped in slot '" + slotName + "'"));
            return;
        }

        if (sourceSlot != targetSlot) {
            // Move object references rather than copies. This preserves the
            // exact total item count and all components/durability on both
            // stacks, including the displaced equipment.
            var targetStack = inventory.getItem(targetSlot);
            inventory.setItem(targetSlot, sourceStack);
            inventory.setItem(sourceSlot, targetStack);
        }
        if (targetSlot < 9) inventory.selected = targetSlot;
        TaskContext.syncInventory(sp);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("item", itemId.toString());
        result.addProperty("slot", slotName);
        reply.accept(result.toString());
    }

    private static int inventorySlot(String slot) {
        return switch (slot) {
            case "head" -> 39;
            case "chest" -> 38;
            case "legs" -> 37;
            case "feet" -> 36;
            case "offhand" -> 40;
            case "hotbar_1" -> 0;
            case "hotbar_2" -> 1;
            case "hotbar_3" -> 2;
            case "hotbar_4" -> 3;
            case "hotbar_5" -> 4;
            case "hotbar_6" -> 5;
            case "hotbar_7" -> 6;
            case "hotbar_8" -> 7;
            case "hotbar_9" -> 8;
            default -> -1;
        };
    }

    private static EquipmentSlot equipmentSlot(String slot) {
        return switch (slot) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "offhand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }
}
