package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.management.WaitForTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Polls a bounded condition on the server thread and reports a real terminal result. */
public final class WaitForTask extends CompanionTask<WaitForTool.WaitForTaskRecord>
        implements IntentAwareTask {
    private long startedTick;
    private int stable;
    private boolean done;
    private String failReason;

    public WaitForTask(AgentPlayer player, WaitForTool.WaitForTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        if (startedTick <= 0L) startedTick = now();
        stable = 0;
        done = false;
        failReason = null;
    }

    @Override protected void onResume() {
        // Waiting has no volatile path or held item. Preserve elapsed time and
        // require the condition to be observed stable again after preemption.
        stable = 0;
        done = false;
        failReason = null;
    }

    @Override protected TaskState onTick() {
        long tick = now();
        if (record.deadline() > 0L && tick >= record.deadline()) {
            failReason = "Condition timed out after waiting "
                    + Math.max(0L, tick - startedTick) + " ticks";
            return TaskState.FAILED;
        }
        boolean satisfied = condition(tick);
        stable = satisfied ? stable + 1 : 0;
        if (stable >= record.stableTicks) {
            done = true;
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private boolean condition(long tick) {
        var sp = TaskContext.serverPlayer(player);
        return switch (record.kind) {
            case "duration" -> tick - startedTick >= record.durationTicks;
            case "inventory" -> compare(inventoryCount(sp, record.itemId), record.count,
                    record.comparison);
            case "semantic" -> semanticCondition(tick);
            case "menu_slot" -> menuSlotCondition(sp);
            case "dimension" -> sp.level().dimension().location().toString()
                    .equals(record.dimension);
            case "block" -> blockCondition(sp);
            case "entity" -> entityCondition(sp);
            default -> false;
        };
    }

    private boolean semanticCondition(long tick) {
        var loop = TaskContext.agentLoop(player);
        if (loop == null) return false;
        var fact = loop.semanticWorldModel().find(record.subject, record.predicate, tick)
                .orElse(null);
        if (fact == null || fact.confidenceAt(tick) < 0.6) return false;
        if ("present".equals(record.comparison)) return true;
        if ("equals".equals(record.comparison)) return "*".equals(record.value)
                || fact.value().equalsIgnoreCase(record.value);
        try {
            double actual = Double.parseDouble(fact.value());
            double expected = Double.parseDouble(record.value);
            return "at_least".equals(record.comparison) ? actual >= expected : actual <= expected;
        } catch (NumberFormatException invalid) { return false; }
    }

    private boolean menuSlotCondition(net.minecraft.server.level.ServerPlayer sp) {
        var menu = sp.containerMenu;
        if (menu == null || menu == sp.inventoryMenu) return false;
        ItemStack stack;
        if ("player".equals(record.endpoint)) {
            if (record.slot < 0 || record.slot >= sp.getInventory().getContainerSize()) return false;
            stack = sp.getInventory().getItem(record.slot);
        } else {
            if (record.slot < 0 || record.slot >= menu.slots.size()) return false;
            stack = menu.getSlot(record.slot).getItem();
        }
        int matchingCount;
        if (record.itemId == null) {
            matchingCount = stack.isEmpty() ? 0 : stack.getCount();
        } else {
            boolean matches = !stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString().equals(record.itemId);
            // Count the requested item, not arbitrary contents of the slot.
            // This makes equals/at_most 0 a real absence condition while
            // preserving exact-item checks for positive quantities.
            matchingCount = matches ? stack.getCount() : 0;
        }
        return compare(matchingCount, record.count, record.comparison);
    }

    private boolean blockCondition(net.minecraft.server.level.ServerPlayer sp) {
        BlockPos pos = new BlockPos(record.x, record.y, record.z);
        if (!sp.level().hasChunkAt(pos)) return false;
        String id = BuiltInRegistries.BLOCK.getKey(sp.level().getBlockState(pos).getBlock()).toString();
        return id.equals(record.blockId);
    }

    private boolean entityCondition(net.minecraft.server.level.ServerPlayer sp) {
        AABB box = new AABB(record.x - record.radius, record.y - record.radius,
                record.z - record.radius, record.x + record.radius,
                record.y + record.radius, record.z + record.radius);
        boolean found = sp.serverLevel().getEntitiesOfClass(Entity.class, box, entity ->
                entity != sp && entity.isAlive()
                        && (record.entityType == null || BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType()).toString().equals(record.entityType))).size() > 0;
        if (record.present || found) return record.present == found;
        // Absence is only evidence when the complete horizontal observation
        // area is loaded. Treating an unloaded chunk as "no entity" can make
        // a portal arrival, boss despawn, or machine-output wait succeed from
        // ignorance and corrupt the enclosing plan checkpoint.
        int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.minX));
        int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.maxX));
        int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.minZ));
        int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(
                net.minecraft.util.Mth.floor(box.maxZ));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!sp.serverLevel().hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private static int inventoryCount(net.minecraft.server.level.ServerPlayer sp, String id) {
        int total = 0;
        for (int slot = 0; slot < sp.getInventory().getContainerSize(); slot++) {
            ItemStack stack = sp.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean compare(int actual, int expected, String comparison) {
        return switch (comparison) {
            case "at_least" -> actual >= expected;
            case "at_most" -> actual <= expected;
            case "present" -> actual > 0;
            default -> actual == expected;
        };
    }

    private long now() { return TaskContext.serverPlayer(player).level().getGameTime(); }

    @Override protected void onInterrupt() { }

    @Override public TaskSnapshot snapshot() {
        long elapsed = Math.max(0L, now() - startedTick);
        boolean positioned = "block".equals(record.kind) || "entity".equals(record.kind);
        return TaskSnapshot.progress(record.kind, "Waiting for " + record.kind,
                stable, record.stableTicks,
                positioned ? record.x : null, positioned ? record.y : null,
                positioned ? record.z : null,
                done ? null : failReason,
                "elapsed_ticks=" + elapsed + " stable=" + stable + "/" + record.stableTicks,
                (elapsed << 8) ^ stable);
    }

    @Override public IntentContract intentContract() {
        return new IntentContract("Wait for " + record.kind,
                "The requested server condition remains true for the required stability window",
                null, null, null, IntentContract.TerrainPolicy.CONSERVATIVE,
                java.util.List.of());
    }

    @Override protected String successMessage() {
        return "Condition '" + record.kind + "' verified for " + record.stableTicks + " ticks";
    }
    @Override protected String timeoutMessage() {
        return failReason == null ? "Wait condition timed out" : failReason;
    }
    @Override protected String failureMessage() {
        return failReason == null ? "Wait condition failed" : failReason;
    }
}
