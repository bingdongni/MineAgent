package com.mineagent.tools.inventory;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Consumer;

/** Uses an item in air, including timed/charged items, through vanilla logic. */
public final class UseItemTool implements Tool {
    @Override public String name() { return "use_item"; }
    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return "Use an item without targeting a block/entity. Supports immediate throws, "
                + "natural-duration drinking/eating, and an explicit hold then release for "
                + "charged or continuous-use items. Optional aim coordinates control direction.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("hand", "Hand to use: 'main' or 'offhand'")
                .optionalString("item_id", "Exact registered item to select before use")
                .optionalInteger("hold_ticks", "0 lets consumables finish naturally; positive values release after this many ticks",
                        0, 1200)
                .nullableNumber("target_x", "Optional world X coordinate to aim at")
                .nullableNumber("target_y", "Optional world Y coordinate to aim at")
                .nullableNumber("target_z", "Optional world Z coordinate to aim at")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String hand = ToolArgs.getString(args, "hand");
        if (!"main".equals(hand) && !"offhand".equals(hand)) {
            reply.accept(ToolArgs.errorJson("'hand' must be 'main' or 'offhand'."));
            return;
        }
        String itemId = ToolArgs.getString(args, "item_id");
        if (itemId != null) {
            ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown registered item: " + itemId));
                return;
            }
            itemId = id.toString();
        }
        Integer holdTicks = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (holdTicks == null || holdTicks < 0 || holdTicks > 1200) {
            reply.accept(ToolArgs.errorJson("'hold_ticks' must be an integer from 0 to 1200."));
            return;
        }
        Double x = coordinate(args, "target_x");
        Double y = coordinate(args, "target_y");
        Double z = coordinate(args, "target_z");
        boolean anyTarget = ToolArgs.has(args, "target_x")
                || ToolArgs.has(args, "target_y") || ToolArgs.has(args, "target_z");
        if (anyTarget && (x == null || y == null || z == null)) {
            reply.accept(ToolArgs.errorJson(
                    "target_x, target_y, and target_z must be finite numbers supplied together."));
            return;
        }

        UseItemTaskRecord record = new UseItemTaskRecord(toolCallId, hand,
                itemId, holdTicks, x, y, z);
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        record.extendDeadlineTo(sp.level().getGameTime()
                + Math.max(200L, holdTicks + 200L));
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    private static Double coordinate(JsonObject args, String key) {
        if (!ToolArgs.has(args, key)) return null;
        Double value = ToolArgs.getDoubleOrNull(args, key);
        return value != null && Double.isFinite(value) ? value : null;
    }

    public static final class UseItemTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String hand;
        public final String itemId;
        public final int holdTicks;
        public final Double targetX;
        public final Double targetY;
        public final Double targetZ;

        public UseItemTaskRecord(String toolCallId, String hand, String itemId,
                                 int holdTicks, Double targetX, Double targetY,
                                 Double targetZ) {
            super(toolCallId);
            this.hand = hand;
            this.itemId = itemId;
            this.holdTicks = holdTicks;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
        }
    }
}
