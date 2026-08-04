package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.util.McCompat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine-internal helper that provides task executors with access to
 * the companion's {@link InputDriver}, {@link ServerPlayer}, and
 * per-companion {@link PathCaches}.
 *
 * <p>Since {@link AgentPlayer} is the abstract API type and doesn't
 * expose engine internals, we cast to {@link CompanionEntity} which
 * is the only concrete implementation in the engine module.
 */
public final class TaskContext {

    private TaskContext() {}

    private static final Map<AgentPlayer, PathCaches> NAV_CACHES = new ConcurrentHashMap<>();

    /** Get the underlying ServerPlayer for a companion. */
    public static net.minecraft.server.level.ServerPlayer serverPlayer(AgentPlayer player) {
        return ((CompanionEntity) player).serverPlayer();
    }

    /**
     * Get the AgentLoop for a companion, if available.
     *
     * <p>Looks up the companion in the global {@link
     * com.mineagent.engine.MineAgentEngine} registry by its UUID. Returns
     * {@code null} if the companion is not registered (e.g. during early
     * init or after teardown).
     */
    public static com.mineagent.engine.loop.AgentLoop agentLoop(AgentPlayer player) {
        try {
            var state = com.mineagent.engine.MineAgentEngine
                    .getCompanion(player.companionId());
            return state.map(s -> s.loop).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Get the input driver for a companion. */
    public static InputDriver inputDriver(AgentPlayer player) {
        return ((CompanionEntity) player).inputDriver();
    }

    /** Select an item using a real swap when it is outside the hotbar. */
    public static boolean selectInventoryItem(AgentPlayer player, String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        var sp = serverPlayer(player);
        var inventory = sp.getInventory();
        for (int i = 0; i < Math.min(9, inventory.getContainerSize()); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                player.holdInHand(i);
                return true;
            }
        }
        for (int i = 9; i < Math.min(36, inventory.getContainerSize()); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                int selected = inventory.selected;
                var displaced = inventory.getItem(selected);
                inventory.setItem(selected, stack);
                inventory.setItem(i, displaced);
                syncInventory(sp);
                player.holdInHand(selected);
                return true;
            }
        }
        // Offhand can safely receive the displaced hotbar stack; armor slots cannot.
        if (inventory.getContainerSize() > 40) {
            var stack = inventory.getItem(40);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                int selected = inventory.selected;
                var displaced = inventory.getItem(selected);
                inventory.setItem(selected, stack);
                inventory.setItem(40, displaced);
                syncInventory(sp);
                player.holdInHand(selected);
                return true;
            }
        }
        return false;
    }

    /** Select an exact inventory item in the hand that will actually be used. */
    public static boolean selectInventoryItemForHand(
            AgentPlayer player, String itemId,
            net.minecraft.world.InteractionHand hand) {
        if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            return selectInventoryItem(player, itemId);
        }
        if (itemId == null || itemId.isBlank()) return false;
        var sp = serverPlayer(player);
        var inventory = sp.getInventory();
        if (inventory.getContainerSize() <= 40) return false;
        var offhand = inventory.getItem(40);
        if (!offhand.isEmpty() && McCompat.isItem(offhand, itemId)) return true;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || !McCompat.isItem(stack, itemId)) continue;
            // Swap real references. This preserves the displaced offhand item
            // and ensures use_offhand does not accidentally use the main hand.
            inventory.setItem(40, stack);
            inventory.setItem(slot, offhand);
            syncInventory(sp);
            return true;
        }
        return false;
    }

    /** Broadcast every server-side inventory edit to fake-player viewers. */
    public static void syncInventory(net.minecraft.server.level.ServerPlayer sp) {
        if (sp == null) return;
        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        if (sp.containerMenu != sp.inventoryMenu) sp.containerMenu.broadcastChanges();
    }

    /** Get or create the per-companion PathCaches. */
    public static PathCaches navCaches(AgentPlayer player) {
        var currentLevel = serverPlayer(player).serverLevel();
        // A ServerPlayer keeps the same companion identity across dimension
        // changes, while a PathCaches instance owns a concrete ServerLevel.
        // Reusing the overworld view after a Nether/End teleport makes every
        // subsequent path read blocks and chunk-loaded state from the wrong
        // dimension. compute atomically replaces that stale view.
        return NAV_CACHES.compute(player, (key, existing) ->
                existing == null || existing.level() != currentLevel
                        ? new PathCaches(currentLevel) : existing);
    }

    /** Remove caches for a companion (on disconnect). */
    public static void removeCaches(AgentPlayer player) {
        NAV_CACHES.remove(player);
    }
}
