package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Interact with a block at a specific world position. Supports
 * use (right-click), attack (left-click), and hold interactions.
 *
 * <p>This is an <b>async</b> tool - it dispatches an InteractAtTaskRecord
 * and returns a task_id immediately.
 */
public class InteractAtTool implements Tool {

    @Override
    public String name() { return "interact_at"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return """
            Interact with a block at a specific world position. Supports:
            - "use": right-click interaction (open door, open chest, etc.)
            - "attack": left-click interaction (break block start)
            - "use_offhand": right-click with off-hand item
            
            Optionally specify an item to hold during the interaction.
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("button", "Interaction type: 'use', 'attack', or 'use_offhand'")
                .integer("x", "Target block X coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("y", "Target block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Target block Z coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", "How long to hold the button (0-40 ticks, default 0)", 0, 40)
                .optionalString("item_id", "Item ID to hold during interaction (e.g. 'minecraft:flint_and_steel')")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String button = ToolArgs.getString(args, "button");
        if (button == null) {
            reply.accept("{\"error\":\"Missing required parameter 'button'.\"}");
            return;
        }
        if (!ToolArgs.has(args, "x") || !ToolArgs.has(args, "y") || !ToolArgs.has(args, "z")) {
            reply.accept("{\"error\":\"Missing required parameters x, y, z.\"}");
            return;
        }
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson("Parameters x, y, and z must be exact integers."));
            return;
        }
        Integer holdTicks = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (holdTicks == null || holdTicks < 0 || holdTicks > 40) {
            reply.accept(ToolArgs.errorJson("'hold_ticks' must be an integer from 0 to 40."));
            return;
        }
        String itemId = ToolArgs.getString(args, "item_id");

        // Validate button
        if (!java.util.Set.of("use", "attack", "use_offhand").contains(button)) {
            reply.accept(ToolArgs.errorJson("Invalid button: " + button
                    + ". Use 'use', 'attack', or 'use_offhand'."));
            return;
        }

        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = new net.minecraft.core.BlockPos(x, y, z);
        if (!sp.level().isInWorldBounds(target)) {
            reply.accept(ToolArgs.errorJson("Target block is outside this dimension's world bounds."));
            return;
        }
        if (sp.level().getBlockState(target).isAir()) {
            reply.accept(ToolArgs.errorJson("Target block is air at " + target.toShortString()));
            return;
        }
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
                return;
            }
            itemId = id.toString();
        }

        var record = new InteractAtTaskRecord(toolCallId, button, x, y, z, holdTicks, itemId);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for block interaction. */
    public static class InteractAtTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String button;
        public final int x;
        public final int y;
        public final int z;
        public final int holdTicks;
        public final String itemId;

        public InteractAtTaskRecord(String toolCallId, String button, int x, int y, int z,
                                     int holdTicks, String itemId) {
            super(toolCallId);
            this.button = button;
            this.x = x;
            this.y = y;
            this.z = z;
            this.holdTicks = holdTicks;
            this.itemId = itemId;
        }
    }
}
