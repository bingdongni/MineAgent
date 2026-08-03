package com.mineagent.tools.perception;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Get the companion's current status — health, food, position, inventory,
 * equipment, and active effects.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class GetSelfStatusTool implements Tool {

    @Override
    public String name() { return "get_self_status"; }

    @Override
    public String description() {
        return "Get your current status: health, food, position, inventory, equipment, and active effects.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var pos = sp.blockPosition();
        var inv = sp.getInventory();

        StringBuilder sb = new StringBuilder();
        sb.append("=== STATUS ===\n");
        sb.append(String.format("Position: (%d, %d, %d)\n", pos.getX(), pos.getY(), pos.getZ()));
        sb.append(String.format("Health: %.1f/%.1f\n", sp.getHealth(), sp.getMaxHealth()));
        sb.append(String.format("Food: %d | Saturation: %.1f\n",
                sp.getFoodData().getFoodLevel(), sp.getFoodData().getSaturationLevel()));
        sb.append(String.format("Air: %d | XP Level: %d\n", sp.getAirSupply(), sp.experienceLevel));

        // Active potion effects
        var effects = sp.getActiveEffects();
        if (!effects.isEmpty()) {
            sb.append("Effects: ");
            for (var effect : effects) {
                String effectName = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                        .getKey(effect.getEffect().value()).toString()
                        .replace("minecraft:", "");
                sb.append(effectName).append("(")
                  .append(effect.getDuration() / 20).append("s) ");
            }
            sb.append("\n");
        }

        // Equipment
        sb.append("\n=== EQUIPMENT ===\n");
        var armorSlots = sp.getInventory().armor;
        String[] slotNames = {"Boots", "Leggings", "Chestplate", "Helmet"};
        for (int i = 0; i < 4; i++) {
            var stack = armorSlots.get(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                sb.append(slotNames[i]).append(": ").append(itemName);
                // Check enchantments
                if (stack.isEnchanted()) {
                    sb.append(" [ENCHANTED]");
                }
                sb.append("\n");
            } else {
                sb.append(slotNames[i]).append(": (empty)\n");
            }
        }

        // Main hand and off hand
        var mainHand = sp.getMainHandItem();
        sb.append("Main Hand: ");
        if (!mainHand.isEmpty()) {
            sb.append(mainHand.getHoverName().getString());
            if (mainHand.isEnchanted()) sb.append(" [ENCHANTED]");
        } else {
            sb.append("(empty)");
        }
        sb.append("\n");

        var offHand = sp.getOffhandItem();
        sb.append("Off Hand: ");
        if (!offHand.isEmpty()) {
            sb.append(offHand.getHoverName().getString());
        } else {
            sb.append("(empty)");
        }
        sb.append("\n");

        // Inventory — only the 36 main slots (9 hotbar + 27 inventory).
        // getContainerSize() is 41 (36 + 4 armor + 1 offhand); iterating
        // beyond 36 would double-report armor/offhand as fake "Inv 27-31".
        sb.append("\n=== INVENTORY ===\n");
        int hotbarCount = 0;
        int invCount = 0;
        for (int i = 0; i < 36; i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                String slotType = (i < 9) ? "Hotbar" : "Inv";
                int slotNum = (i < 9) ? i : i - 9;
                sb.append(String.format("[%s %d] %s x%d\n", slotType, slotNum, itemName, stack.getCount()));
                if (i < 9) hotbarCount++; else invCount++;
            }
        }
        sb.append(String.format("\nHotbar: %d/9 slots | Inventory: %d/27 slots used\n",
                hotbarCount, invCount));

        reply.accept(sb.toString());
    }
}
