package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Read the contents of the currently open container GUI. Returns all
 * items in the container with slot indices, item IDs, and counts.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class InspectGuiTool implements Tool {

    @Override
    public String name() { return "inspect_gui"; }

    @Override
    public String description() {
        return """
            Read the contents of the currently open container GUI (chest,
            furnace, crafting table, etc.). Returns all items with their
            slot positions, item IDs, counts, and any enchantments.
            Returns an error if no container is open.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var containerMenu = sp.containerMenu;

        // ServerPlayer always has inventoryMenu; it is not an opened GUI.
        // Testing only for null therefore returned the player's own inventory.
        if (containerMenu == null || containerMenu == sp.inventoryMenu) {
            reply.accept("{\"error\":\"No container GUI is currently open.\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"container_type\":\"").append(containerMenu.getClass().getSimpleName()).append("\"");
        sb.append(",\"slots\":[");

        boolean first = true;
        for (int i = 0; i < containerMenu.slots.size(); i++) {
            var stack = containerMenu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                if (!first) sb.append(",");
                sb.append("{\"slot\":").append(i);
                sb.append(",\"item\":\"")
                  .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                  .append("\"");
                sb.append(",\"count\":").append(stack.getCount());
                if (stack.isDamageableItem()) {
                    sb.append(",\"durability\":").append(stack.getMaxDamage() - stack.getDamageValue());
                }
                if (stack.isEnchanted()) {
                    sb.append(",\"enchanted\":true");
                }
                sb.append("}");
                first = false;
            }
        }

        sb.append("],\"total_slots\":").append(containerMenu.slots.size());
        sb.append("}");
        reply.accept(sb.toString());
    }
}
