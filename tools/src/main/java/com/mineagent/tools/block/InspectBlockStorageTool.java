package com.mineagent.tools.block;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Inspect opened container contents at a block position. Works on chests,
 * furnaces, hoppers, dispensers, and any other Container block.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class InspectBlockStorageTool implements Tool {

    @Override
    public String name() { return "inspect_block_storage"; }

    @Override
    public String description() {
        return """
            Inspect the contents of a container block (chest, furnace, hopper,
            barrel, dispenser, dropper, etc.) at the given position. Returns
            a list of items in the container's slots with item IDs, counts,
            and slot indices. The companion must be close enough and able to
            open the container normally; walls, blocked/locked containers and
            unloaded positions cannot be inspected.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Container block X coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("y", "Container block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Container block Z coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept("{\"error\":\"x, y, and z must be valid integers.\"}");
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var blockPos = new net.minecraft.core.BlockPos(x, y, z);
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()
                || !level.isLoaded(blockPos)) {
            // Avoid forcing arbitrary chunk generation from a synchronous
            // inspection tool running on the server tick.
            reply.accept("{\"error\":\"Target position is outside loaded world data.\"}");
            return;
        }
        var blockState = level.getBlockState(blockPos);

        // Check if the block has a container
        if (!(blockState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
            reply.accept("{\"error\":\"Block at (" + x + "," + y + "," + z + ") is not a container.\"}");
            return;
        }

        var blockEntity = level.getBlockEntity(blockPos);
        if (blockEntity == null) {
            reply.accept("{\"error\":\"No block entity at (" + x + "," + y + "," + z + ").\"}");
            return;
        }

        if (!(blockEntity instanceof net.minecraft.world.Container)) {
            reply.accept("{\"error\":\"Block entity at (" + x + "," + y + "," + z + ") is not a container.\"}");
            return;
        }

        // Reading the BlockEntity directly bypasses the same reach, line of
        // sight, blocked-lid and lock checks that constrain a real player. Open
        // it through vanilla first and require an actual server menu transition.
        if (sp.containerMenu != sp.inventoryMenu) sp.closeContainer();
        var openResult = com.mineagent.engine.act.Interaction.interactBlock(
                sp, blockPos, net.minecraft.world.InteractionHand.MAIN_HAND);
        if (!openResult.consumesAction() || sp.containerMenu == sp.inventoryMenu) {
            reply.accept("{\"error\":\"Container could not be opened from the companion's current position.\"}");
            return;
        }

        var openedMenu = sp.containerMenu;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"block_id\":\"")
              .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString())
              .append("\"");
            sb.append(",\"slots\":[");

            boolean first = true;
            int totalSlots = 0;
            for (var slot : openedMenu.slots) {
                // Menus append the companion's 36 inventory slots after the
                // container slots. Excluding that exact Container identity
                // also handles double chests and modded menu sizes correctly.
                if (slot.container == sp.getInventory()) continue;
                totalSlots++;
                var stack = slot.getItem();
                if (!stack.isEmpty()) {
                    if (!first) sb.append(",");
                    sb.append("{\"slot\":").append(slot.getContainerSlot());
                    sb.append(",\"item\":\"")
                      .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                      .append("\"");
                    sb.append(",\"count\":").append(stack.getCount());
                    if (stack.isDamageableItem()) {
                        sb.append(",\"durability\":").append(stack.getMaxDamage() - stack.getDamageValue());
                        sb.append(",\"max_durability\":").append(stack.getMaxDamage());
                    }
                    if (stack.isEnchanted()) sb.append(",\"enchanted\":true");
                    sb.append("}");
                    first = false;
                }
            }

            sb.append("],\"total_slots\":").append(totalSlots);
            sb.append("}");
            reply.accept(sb.toString());
        } finally {
            // This query should not leave a stale remote container menu owned
            // by later inventory tools after its synchronous reply completes.
            sp.closeContainer();
        }
    }
}
