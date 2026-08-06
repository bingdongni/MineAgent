package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Waits for a verifiable world condition instead of ending the LLM turn. */
public final class WaitForTool implements Tool {
    private static final Set<String> KINDS = Set.of(
            "duration", "inventory", "semantic", "menu_slot", "dimension", "block", "entity");

    @Override public String name() { return "wait_for"; }
    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return "Wait asynchronously for a bounded, server-observed condition: duration, "
                + "inventory count, semantic fact, menu slot contents, dimension change, "
                + "block state, or entity presence/absence. It wakes the decision loop only "
                + "on a verified stable result or a diagnostic timeout.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("kind", "Condition kind: duration, inventory, semantic, menu_slot, dimension, block, entity")
                .optionalInteger("duration_ticks", "For kind=duration, required elapsed ticks", 1, 12000)
                .optionalString("item_id", "For inventory/menu_slot, exact item ID")
                .optionalInteger("count", "For inventory/menu_slot, required count", 0, 4096)
                .optionalString("subject", "For kind=semantic, semantic subject")
                .optionalString("predicate", "For kind=semantic, semantic predicate")
                .optionalString("value", "For kind=semantic, expected value")
                .optionalString("comparison", "Semantic comparison: equals, at_least, at_most, present")
                .optionalString("endpoint", "For menu_slot: container or player")
                .optionalInteger("slot", "For menu_slot, slot index", 0, 255)
                .optionalString("dimension", "For kind=dimension, expected dimension ID")
                .optionalInteger("x", "For kind=block, block/entity center X", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("y", "For kind=block, block/entity center Y", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", "For kind=block, block/entity center Z", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalString("block_id", "For kind=block, expected registered block ID")
                .optionalString("entity_type", "For kind=entity, optional registered entity ID")
                .nullableNumber("radius", "For kind=entity, search radius (default 8)")
                .optionalBoolean("present", "For kind=entity, true for present and false for absent")
                .optionalInteger("stable_ticks", "Condition must remain true for this many ticks (default 1)", 1, 100)
                .optionalInteger("max_wait_ticks", "Hard timeout (default 600)", 20, 12000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String kind = ToolArgs.getString(args, "kind");
        if (!KINDS.contains(kind)) {
            reply.accept(ToolArgs.errorJson("Unknown wait kind. Choose one of " + KINDS));
            return;
        }
        int duration = integer(args, "duration_ticks", 0);
        int count = integer(args, "count", -1);
        int slot = integer(args, "slot", -1);
        int stable = integer(args, "stable_ticks", 1);
        int timeout = integer(args, "max_wait_ticks", 600);
        if ((ToolArgs.has(args, "stable_ticks")
                && ToolArgs.getIntOrNull(args, "stable_ticks") == null)
                || (ToolArgs.has(args, "max_wait_ticks")
                && ToolArgs.getIntOrNull(args, "max_wait_ticks") == null)) {
            reply.accept(ToolArgs.errorJson(
                    "stable_ticks and max_wait_ticks must be integers when supplied."));
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
        if (duration < 0 || duration > 12000 || stable < 1 || stable > 100
                || timeout < 20 || timeout > 12000) {
            reply.accept(ToolArgs.errorJson("Invalid duration/stability/timeout bounds."));
            return;
        }
        if ("duration".equals(kind) && duration < 1) {
            reply.accept(ToolArgs.errorJson("duration_ticks is required for kind=duration."));
            return;
        }
        if ("inventory".equals(kind) && (itemId == null || count < 0)) {
            reply.accept(ToolArgs.errorJson("inventory waits require item_id and count."));
            return;
        }
        if ("semantic".equals(kind)
                && (blank(args, "subject") || blank(args, "predicate") || !ToolArgs.has(args, "value"))) {
            reply.accept(ToolArgs.errorJson("semantic waits require subject, predicate and value."));
            return;
        }
        if ("menu_slot".equals(kind) && (slot < 0 || count < 0)) {
            reply.accept(ToolArgs.errorJson("menu_slot waits require slot and count."));
            return;
        }
        String dimension = ToolArgs.getString(args, "dimension");
        if ("dimension".equals(kind)) {
            ResourceLocation id = dimension == null ? null
                    : ResourceLocation.tryParse(dimension.trim());
            if (id == null) {
                reply.accept(ToolArgs.errorJson(
                        "dimension waits require a valid namespaced dimension ID."));
                return;
            }
            // Dimensions are dynamic registry keys, so syntax is validated
            // here while existence is left to the bounded runtime condition.
            dimension = id.toString();
        }
        if ("block".equals(kind)
                && (integerMissing(args, "x") || integerMissing(args, "y")
                || integerMissing(args, "z") || blank(args, "block_id"))) {
            reply.accept(ToolArgs.errorJson("block waits require x, y, z and block_id."));
            return;
        }
        String blockId = ToolArgs.getString(args, "block_id");
        if (blockId != null) {
            ResourceLocation id = ResourceLocation.tryParse(blockId.trim());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown registered block: " + blockId));
                return;
            }
            blockId = id.toString();
        }
        if ("entity".equals(kind)
                && (integerMissing(args, "x") || integerMissing(args, "y")
                || integerMissing(args, "z"))) {
            reply.accept(ToolArgs.errorJson("entity waits require x, y and z."));
            return;
        }
        String entityType = ToolArgs.getString(args, "entity_type");
        if (entityType != null) {
            ResourceLocation id = ResourceLocation.tryParse(entityType.trim());
            if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                reply.accept(ToolArgs.errorJson("Unknown registered entity type: " + entityType));
                return;
            }
            entityType = id.toString();
        }
        String endpoint = ToolArgs.getString(args, "endpoint", "container");
        if (!List.of("container", "player").contains(endpoint)) {
            reply.accept(ToolArgs.errorJson("endpoint must be container or player."));
            return;
        }
        String comparison = ToolArgs.getString(args, "comparison", "equals");
        if (!List.of("equals", "at_least", "at_most", "present").contains(comparison)) {
            reply.accept(ToolArgs.errorJson("Invalid semantic comparison."));
            return;
        }
        String semanticValue = ToolArgs.getString(args, "value");
        if ("semantic".equals(kind)
                && ("at_least".equals(comparison) || "at_most".equals(comparison))
                && !finiteNumber(semanticValue)) {
            reply.accept(ToolArgs.errorJson(
                    "Numeric semantic comparisons require a finite numeric value."));
            return;
        }
        Double radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getDoubleOrNull(args, "radius") : 8.0;
        if (radius == null || !Double.isFinite(radius) || radius <= 0.0 || radius > 128.0) {
            reply.accept(ToolArgs.errorJson("radius must be finite and between 0 and 128."));
            return;
        }
        Boolean requestedPresence = ToolArgs.has(args, "present")
                ? ToolArgs.getBoolOrNull(args, "present") : Boolean.TRUE;
        if (requestedPresence == null) {
            reply.accept(ToolArgs.errorJson("present must be a boolean when supplied."));
            return;
        }
        boolean present = requestedPresence;
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        WaitForTaskRecord record = new WaitForTaskRecord(toolCallId, kind, duration,
                itemId, count, ToolArgs.getString(args, "subject"),
                ToolArgs.getString(args, "predicate"), semanticValue,
                comparison, endpoint, slot, dimension,
                integer(args, "x", 0), integer(args, "y", 0), integer(args, "z", 0),
                blockId, entityType,
                radius, present, stable);
        record.extendDeadlineTo(sp.level().getGameTime() + timeout);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    private static boolean blank(JsonObject args, String key) {
        String value = ToolArgs.getString(args, key);
        return value == null || value.isBlank();
    }
    private static boolean integerMissing(JsonObject args, String key) {
        return !ToolArgs.has(args, key) || ToolArgs.getIntOrNull(args, key) == null;
    }
    private static int integer(JsonObject args, String key, int fallback) {
        Integer value = ToolArgs.has(args, key) ? ToolArgs.getIntOrNull(args, key) : null;
        return value == null ? fallback : value;
    }
    private static boolean finiteNumber(String value) {
        try { return Double.isFinite(Double.parseDouble(value)); }
        catch (NumberFormatException | NullPointerException invalid) { return false; }
    }

    public static final class WaitForTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String kind;
        public final int durationTicks;
        public final String itemId;
        public final int count;
        public final String subject;
        public final String predicate;
        public final String value;
        public final String comparison;
        public final String endpoint;
        public final int slot;
        public final String dimension;
        public final int x, y, z;
        public final String blockId;
        public final String entityType;
        public final double radius;
        public final boolean present;
        public final int stableTicks;

        public WaitForTaskRecord(String toolCallId, String kind, int durationTicks,
                                 String itemId, int count, String subject, String predicate,
                                 String value, String comparison, String endpoint, int slot,
                                 String dimension, int x, int y, int z, String blockId,
                                 String entityType, double radius, boolean present,
                                 int stableTicks) {
            super(toolCallId);
            this.kind = kind; this.durationTicks = durationTicks; this.itemId = itemId;
            this.count = count; this.subject = subject; this.predicate = predicate;
            this.value = value; this.comparison = comparison; this.endpoint = endpoint;
            this.slot = slot; this.dimension = dimension; this.x = x; this.y = y; this.z = z;
            this.blockId = blockId; this.entityType = entityType; this.radius = radius;
            this.present = present; this.stableTicks = stableTicks;
        }
    }
}
