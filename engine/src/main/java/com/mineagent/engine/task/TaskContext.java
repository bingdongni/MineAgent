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

    /**
     * Select an inventory item, swapping it into the current hotbar slot when
     * necessary. Every real mutation is synchronized through both inventory
     * menus so fake-player viewers do not retain a stale held-item snapshot.
     */
    public static boolean selectInventoryItem(AgentPlayer player, String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        var sp = serverPlayer(player);
        var inventory = sp.getInventory();
        for (int i = 0; i < 9; i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                player.holdInHand(i);
                return true;
            }
        }
        // Main inventory ends at slot 35. Scanning through containerSize also
        // includes armor slots 36-39; swapping a requested item out of armor
        // can put an arbitrary held item into an invalid equipment slot.
        for (int i = 9; i < Math.min(36, inventory.getContainerSize()); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                int selected = inventory.selected;
                var selectedStack = inventory.getItem(selected);
                inventory.setItem(selected, stack);
                inventory.setItem(i, selectedStack);
                syncInventory(sp);
                player.holdInHand(selected);
                return true;
            }
        }
        // Offhand accepts an arbitrary displaced main-hand item and is safe to
        // swap, unlike armor slots.
        if (inventory.getContainerSize() > 40) {
            var stack = inventory.getItem(40);
            if (!stack.isEmpty() && McCompat.isItem(stack, itemId)) {
                int selected = inventory.selected;
                var selectedStack = inventory.getItem(selected);
                inventory.setItem(selected, stack);
                inventory.setItem(40, selectedStack);
                syncInventory(sp);
                player.holdInHand(selected);
                return true;
            }
        }
        return false;
    }

    /** Mark and broadcast a fake player's inventory after a server-side edit. */
    public static void syncInventory(net.minecraft.server.level.ServerPlayer sp) {
        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        if (sp.containerMenu != sp.inventoryMenu) {
            sp.containerMenu.broadcastChanges();
        }
    }

    /** Get or create the per-companion PathCaches. */
    public static PathCaches navCaches(AgentPlayer player) {
        return NAV_CACHES.computeIfAbsent(player,
                p -> new PathCaches(TaskContext.serverPlayer(p).serverLevel()));
    }

    /** Remove caches for a companion (on disconnect). */
    public static void removeCaches(AgentPlayer player) {
        NAV_CACHES.remove(player);
    }
}
