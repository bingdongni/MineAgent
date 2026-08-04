package com.mineagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Reads the slots of the companion's currently open non-inventory menu. */
public class InspectGuiTool implements Tool {
    @Override public String name() { return "inspect_gui"; }
    @Override public String description() {
        return "Read item slots in the currently open chest, furnace, crafting, or other container menu.";
    }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var menu = sp.containerMenu;
        if (menu == null || menu == sp.inventoryMenu) {
            // containerMenu normally points at inventoryMenu when no screen is
            // open; a null-only check incorrectly reported the player inventory
            // as an open container.
            reply.accept(ToolArgs.errorJson("No container GUI is currently open."));
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("container_type", menu.getClass().getSimpleName());
        JsonArray slots = new JsonArray();
        for (int i = 0; i < menu.slots.size(); i++) {
            var stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                slot.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
            }
            if (stack.isEnchanted()) slot.addProperty("enchanted", true);
            slots.add(slot);
        }
        result.add("slots", slots);
        result.addProperty("total_slots", menu.slots.size());
        reply.accept(result.toString());
    }
}
