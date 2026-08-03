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
 * Interact with an entity. Supports use (right-click) and attack
 * (left-click) interactions with entities by entity ID.
 *
 * <p>This is an <b>async</b> tool - it dispatches an InteractEntityTaskRecord
 * and returns a task_id immediately.
 */
public class InteractEntityTool implements Tool {

    @Override
    public String name() { return "interact_entity"; }

    @Override
    public String description() {
        return """
            Interact with an entity by its ID. Supports:
            - "use": right-click (trade with villager, shear sheep, etc.)
            - "attack": left-click (hit entity)
            - "use_offhand": right-click with off-hand item
            
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("button", "Interaction type: 'use', 'attack', or 'use_offhand'")
                .integer("entity_id", "The entity ID of the target entity", 0, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", "How long to hold the button (0-40 ticks, default 0)", 0, 40)
                .optionalString("item_id", "Item ID to hold during interaction (e.g. 'minecraft:wheat' for feeding animals)")
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
        Integer entityId = ToolArgs.getIntOrNull(args, "entity_id");
        if (entityId == null || entityId <= 0) {
            reply.accept("{\"error\":\"entity_id must be a positive integer.\"}");
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

        var record = new InteractEntityTaskRecord(toolCallId, button, entityId, holdTicks, itemId);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for entity interaction. */
    public static class InteractEntityTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String button;
        public final int entityId;
        public final int holdTicks;
        public final String itemId;

        public InteractEntityTaskRecord(String toolCallId, String button, int entityId,
                                         int holdTicks, String itemId) {
            super(toolCallId);
            this.button = button;
            this.entityId = entityId;
            this.holdTicks = holdTicks;
            this.itemId = itemId;
        }
    }
}
