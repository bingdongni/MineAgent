package com.mineagent.tools.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Inspects a loaded container that is within normal player reach. */
public class InspectBlockStorageTool implements Tool {

    @Override public String name() { return "inspect_block_storage"; }

    @Override
    public String description() {
        return "Inspect item slots in a nearby loaded container block without moving items.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Container block X coordinate", -30_000_000, 30_000_000)
                .integer("y", "Container block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Container block Z coordinate", -30_000_000, 30_000_000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson("Parameters x, y, and z must be exact integers."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            reply.accept(ToolArgs.errorJson("Container position is outside the loaded world."));
            return;
        }
        if (sp.getEyePosition().distanceTo(pos.getCenter()) > 5.0) {
            // The old implementation provided unlimited x-ray access to any
            // coordinate and could force remote chunks to load. Keep this
            // query within the same reach envelope as a normal interaction.
            reply.accept(ToolArgs.errorJson("Container is outside interaction range."));
            return;
        }

        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            reply.accept(ToolArgs.errorJson("Block at " + pos.toShortString()
                    + " is not an item container."));
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        JsonArray slots = new JsonArray();
        for (int i = 0; i < container.getContainerSize(); i++) {
            var stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString());
            slot.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                slot.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
                slot.addProperty("max_durability", stack.getMaxDamage());
            }
            if (stack.isEnchanted()) slot.addProperty("enchanted", true);
            slots.add(slot);
        }
        result.add("slots", slots);
        result.addProperty("total_slots", container.getContainerSize());
        reply.accept(result.toString());
    }
}
