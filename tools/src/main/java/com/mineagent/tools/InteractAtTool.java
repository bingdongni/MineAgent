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
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept("{\"error\":\"x, y, and z must be valid integers.\"}");
            return;
        }
        var level = com.mineagent.engine.task.TaskContext.serverPlayer(player).serverLevel();
        if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L
                || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            reply.accept("{\"error\":\"Target position is outside world bounds.\"}");
            return;
        }
        Integer parsedHold = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (parsedHold == null || parsedHold < 0 || parsedHold > 40) {
            reply.accept("{\"error\":\"hold_ticks must be an integer between 0 and 40.\"}");
            return;
        }
        int holdTicks = parsedHold;
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null
                    || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown held item: " + itemId));
                return;
            }
        }

        // Validate button
        if (!java.util.Set.of("use", "attack", "use_offhand").contains(button)) {
            reply.accept(ToolArgs.errorJson("Invalid button: " + button
                    + ". Use 'use', 'attack', or 'use_offhand'."));
            return;
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
