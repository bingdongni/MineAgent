package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/** Starts one asynchronous, vanilla-duration eating action. */
public class EatItemTool implements Tool {

    @Override
    public String name() { return "eat_item"; }

    @Override
    public String description() {
        return """
            Eat one food item from inventory using its normal use duration.
            If item_id is omitted, the best available safe food is selected.
            Returns a task_id because eating can be interrupted before it ends.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("item_id", "Food item ID to eat (e.g. 'minecraft:cooked_beef'); omit to choose automatically")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (id == null
                    || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown item: " + itemId));
                return;
            }
            itemId = id.toString();
        }
        TaskDispatch.dispatchAsync(player,
                new EatItemTaskRecord(toolCallId, itemId), reply);
    }

    public static final class EatItemTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String itemId;

        public EatItemTaskRecord(String toolCallId, String itemId) {
            super(toolCallId);
            this.itemId = itemId;
        }
    }
}
