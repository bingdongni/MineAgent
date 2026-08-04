package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.util.McCompat;
import com.mineagent.tools.inventory.CollectItemsTool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Navigates to dropped items and verifies pickup through inventory deltas. */
public class CollectItemsTask extends CompanionTask<CollectItemsTool.CollectItemsTaskRecord> {

    private enum Phase { SCAN, NAVIGATE, PICKUP, DONE }

    private static final double PICKUP_RANGE = 1.5;
    private static final int MAX_WAIT_TICKS = 40;

    private PlayerNav nav;
    private Phase phase;
    private int collectedCount;
    private ItemEntity currentItem;
    private Item targetItemType;
    private int targetInventoryBaseline;
    private final Set<UUID> unreachableItems = new HashSet<>();
    private int waitTicks;
    private String failReason;

    public CollectItemsTask(AgentPlayer player, CollectItemsTool.CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.SCAN;
        collectedCount = 0;
        currentItem = null;
        targetItemType = null;
        waitTicks = 0;
        failReason = null;
        unreachableItems.clear();

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) phase = Phase.PICKUP;
            }

            @Override
            public void onNavigationFailed(String reason) {
                if (currentItem != null) unreachableItems.add(currentItem.getUUID());
                failReason = "Navigation to item failed: " + reason;
                currentItem = null;
                phase = Phase.SCAN;
            }
        });
        scanForItems();
    }

    @Override
    protected TaskState onTick() {
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (record.deadline() > 0L && gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> scanForItems();
            case NAVIGATE -> tickNavigate();
            case PICKUP -> tickPickup();
            case DONE -> { }
        }

        if (phase == Phase.DONE && collectedCount > 0) return TaskState.SUCCESS;
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (collectedCount >= record.maxCount) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        updateCollectedFromInventory();
        if (currentItem == null || !currentItem.isAlive()) {
            currentItem = null;
            phase = Phase.SCAN;
            return;
        }

        nav.tick();
        if (distanceToItem() <= PICKUP_RANGE) {
            nav.cancel();
            touchCurrentItemWithinLimit();
            updateCollectedFromInventory();
            phase = Phase.PICKUP;
        }
    }

    private void tickPickup() {
        if (currentItem != null && currentItem.isAlive()
                && distanceToItem() <= PICKUP_RANGE) {
            // Fake players do not receive a client movement packet that would
            // otherwise drive ItemEntity#playerTouch, so call vanilla's pickup
            // entry point once physical range has actually been reached.
            touchCurrentItemWithinLimit();
        }
        updateCollectedFromInventory();

        if (currentItem == null || !currentItem.isAlive()) {
            waitTicks = 0;
            currentItem = null;
            if (collectedCount >= record.maxCount) cancelNav();
            else phase = Phase.SCAN;
            return;
        }

        if (++waitTicks > MAX_WAIT_TICKS) {
            unreachableItems.add(currentItem.getUUID());
            waitTicks = 0;
            currentItem = null;
            failReason = "Item could not enter inventory";
            phase = Phase.SCAN;
        }
    }

    private void scanForItems() {
        var sp = TaskContext.serverPlayer(player);
        ServerLevel level = sp.serverLevel();
        var pos = sp.blockPosition();
        AABB searchBox = new AABB(
                pos.getX() - record.radius, pos.getY() - record.radius, pos.getZ() - record.radius,
                pos.getX() + record.radius + 1, pos.getY() + record.radius + 1,
                pos.getZ() + record.radius + 1);

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox,
                item -> item.isAlive()
                        && !unreachableItems.contains(item.getUUID())
                        && (record.itemId == null
                            || McCompat.isItem(item.getItem(), record.itemId)));
        if (items.isEmpty()) {
            if (collectedCount > 0) {
                cancelNav();
                failReason = null;
            } else if (failReason == null) {
                failReason = "No items found within radius " + record.radius;
            }
            phase = Phase.DONE;
            return;
        }

        currentItem = items.stream()
                .min(java.util.Comparator.comparingDouble(
                        item -> sp.distanceToSqr(item)))
                .orElse(null);
        if (currentItem == null) {
            failReason = "No reachable items found";
            phase = Phase.DONE;
            return;
        }

        targetItemType = currentItem.getItem().getItem();
        targetInventoryBaseline = inventoryCount(targetItemType);
        failReason = null;
        navigateToItem();
    }

    private void navigateToItem() {
        if (currentItem == null || !currentItem.isAlive()) {
            phase = Phase.SCAN;
            return;
        }
        BlockPos target = currentItem.blockPosition();
        nav.navigateTo(target.getX(), target.getY(), target.getZ());
        phase = Phase.NAVIGATE;
    }

    private double distanceToItem() {
        return currentItem == null ? Double.MAX_VALUE
                : TaskContext.serverPlayer(player).position().distanceTo(currentItem.position());
    }

    private void updateCollectedFromInventory() {
        if (targetItemType == null) return;
        int current = inventoryCount(targetItemType);
        int gained = current - targetInventoryBaseline;
        if (gained > 0) collectedCount += gained;
        targetInventoryBaseline = current;
    }

    /** Preserve world item totals while respecting the requested maximum. */
    private void touchCurrentItemWithinLimit() {
        if (currentItem == null || !currentItem.isAlive()) return;
        int remaining = record.maxCount - collectedCount;
        if (remaining <= 0) return;

        ItemStack entityStack = currentItem.getItem();
        if (entityStack.getCount() > remaining) {
            int excessCount = entityStack.getCount() - remaining;
            ItemStack excess = entityStack.split(excessCount);
            currentItem.setItem(entityStack);

            ItemEntity excessEntity = new ItemEntity(currentItem.level(),
                    currentItem.getX(), currentItem.getY(), currentItem.getZ(), excess);
            excessEntity.setPickUpDelay(MAX_WAIT_TICKS + 20);
            if (!currentItem.level().addFreshEntity(excessEntity)) {
                entityStack.grow(excess.getCount());
                currentItem.setItem(entityStack);
                return;
            }
        }

        currentItem.playerTouch(TaskContext.serverPlayer(player));
        TaskContext.syncInventory(TaskContext.serverPlayer(player));
    }

    private int inventoryCount(Item item) {
        int total = 0;
        var inventory = TaskContext.serverPlayer(player).getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() { cancelNav(); }

    @Override
    protected String successMessage() {
        return "Collected " + collectedCount + " items"
                + (record.itemId != null ? " of " + record.itemId : "");
    }

    @Override
    protected String timeoutMessage() {
        return "Item collection timed out after collecting " + collectedCount
                + "/" + record.maxCount + " items";
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Item collection failed after collecting " + collectedCount + " items";
    }
}
