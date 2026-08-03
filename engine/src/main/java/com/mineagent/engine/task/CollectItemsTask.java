package com.mineagent.engine.task;

import com.mineagent.engine.util.McCompat;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.inventory.CollectItemsTool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Executes an item collection task — finds dropped item entities within
 * radius, navigates to each one, and relies on vanilla auto-pickup
 * mechanics to collect items when the companion walks over them.
 */
public class CollectItemsTask extends CompanionTask<CollectItemsTool.CollectItemsTaskRecord> {

    private enum Phase { SCAN, NAVIGATE, PICKUP, DONE }

    /** Distance within which vanilla auto-pickup triggers. */
    private static final double PICKUP_RANGE = 1.5;

    private PlayerNav nav;
    private Phase phase;
    private int collectedCount;
    private ItemEntity currentItem;
    private Item targetItemType;
    private int targetInventoryBaseline;
    private final Set<UUID> unreachableItems = new HashSet<>();
    private int waitTicks;
    private String failReason;

    /** Max ticks to wait for pickup after arriving at item position. */
    private static final int MAX_WAIT_TICKS = 40;

    public CollectItemsTask(AgentPlayer player, CollectItemsTool.CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.SCAN;
        collectedCount = 0;
        currentItem = null;
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
                if (currentItem != null) {
                    unreachableItems.add(currentItem.getUUID());
                }
                failReason = "Navigation to item failed: " + reason;
                // Exclude this entity before rescanning. Retrying the same
                // unreachable item forever prevents a terminal result.
                currentItem = null;
                // Don't abort — try scanning for another item
                phase = Phase.SCAN;
            }
        });

        scanForItems();
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        switch (phase) {
            case SCAN -> tickScan();
            case NAVIGATE -> tickNavigate();
            case PICKUP -> tickPickup();
            case DONE -> {}
        }

        if (phase == Phase.DONE && collectedCount > 0) return TaskState.SUCCESS;
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        if (collectedCount >= record.maxCount) return TaskState.SUCCESS;
        return TaskState.RUNNING;
    }

    private void tickScan() {
        scanForItems();
    }

    private void tickNavigate() {
        updateCollectedFromInventory();

        // Check if item still exists
        if (currentItem == null || !currentItem.isAlive()) {
            // Disappearance alone is not proof of pickup: the item may have
            // despawned or another player may have taken it.
            currentItem = null;
            // Item despawned or picked up — scan for next
            phase = Phase.SCAN;
            return;
        }

        nav.tick();

        // Check if we're close enough for pickup
        double dist = distanceToItem();
        if (dist <= PICKUP_RANGE) {
            nav.cancel();
            touchCurrentItemWithinLimit();
            updateCollectedFromInventory();
            phase = Phase.PICKUP;
        }
    }

    private void tickPickup() {
        if (currentItem != null && currentItem.isAlive()
                && distanceToItem() <= PICKUP_RANGE) {
            // Use vanilla's real pickup entry point, then count only the units
            // that actually appeared in the fake player's inventory.
            touchCurrentItemWithinLimit();
        }
        updateCollectedFromInventory();

        // Check if item was picked up (by vanilla mechanics)
        if (currentItem == null || !currentItem.isAlive()) {
            waitTicks = 0;
            if (collectedCount >= record.maxCount) {
                cancelNav();
                return;
            }
            currentItem = null;
            phase = Phase.SCAN;
            return;
        }

        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS) {
            unreachableItems.add(currentItem.getUUID());
            // Item wasn't picked up — maybe we're not close enough
            // Try navigating again
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

        // Search for item entities in radius
        AABB searchBox = new AABB(
                pos.getX() - record.radius, pos.getY() - record.radius, pos.getZ() - record.radius,
                pos.getX() + record.radius + 1, pos.getY() + record.radius + 1, pos.getZ() + record.radius + 1
        );

        List<ItemEntity> items;
        if (record.itemId != null) {
            items = level.getEntitiesOfClass(ItemEntity.class, searchBox,
                    item -> item.isAlive()
                            && !unreachableItems.contains(item.getUUID())
                            && McCompat.isItem(item.getItem(), record.itemId));
        } else {
            items = level.getEntitiesOfClass(ItemEntity.class, searchBox,
                    item -> item.isAlive() && !unreachableItems.contains(item.getUUID()));
        }

        if (items.isEmpty()) {
            if (collectedCount > 0) {
                // No more items found but we collected some — partial success
                cancelNav();
                failReason = null;
                phase = Phase.DONE;
                return;
            }
            failReason = "No items found within radius " + record.radius;
            phase = Phase.DONE;
            return;
        }

        // Find nearest item
        currentItem = items.stream()
                .min((a, b) -> {
                    double distA = sp.position().distanceTo(a.position());
                    double distB = sp.position().distanceTo(b.position());
                    return Double.compare(distA, distB);
                })
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
        BlockPos targetPos = currentItem.blockPosition();
        nav.navigateTo(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        phase = Phase.NAVIGATE;
    }

    private double distanceToItem() {
        if (currentItem == null) return Double.MAX_VALUE;
        return TaskContext.serverPlayer(player).position().distanceTo(currentItem.position());
    }

    private void updateCollectedFromInventory() {
        if (targetItemType == null) return;
        int currentCount = inventoryCount(targetItemType);
        int gained = currentCount - targetInventoryBaseline;
        if (gained > 0) {
            collectedCount += gained;
        }
        targetInventoryBaseline = currentCount;
    }

    /**
     * Pick up no more than the remaining requested amount while preserving the
     * authoritative world-item total. Vanilla playerTouch consumes an entire
     * entity stack when inventory capacity allows it, so merely capping the
     * reported counter would violate the tool's maxCount contract.
     */
    private void touchCurrentItemWithinLimit() {
        if (currentItem == null || !currentItem.isAlive()) return;
        int remaining = record.maxCount - collectedCount;
        if (remaining <= 0) return;

        ItemStack entityStack = currentItem.getItem();
        if (entityStack.getCount() > remaining) {
            int excessCount = entityStack.getCount() - remaining;
            ItemStack excess = entityStack.split(excessCount);
            currentItem.setItem(entityStack);

            // Materialize the excess before invoking playerTouch, which may
            // discard currentItem. If spawning fails, restore the original
            // stack and skip pickup so items can neither vanish nor duplicate.
            ItemEntity excessEntity = new ItemEntity(currentItem.level(),
                    currentItem.getX(), currentItem.getY(), currentItem.getZ(), excess);
            // The fake player's physics tick runs immediately after this task
            // tick. A zero-delay excess entity at the same coordinates is
            // therefore picked up as well, violating maxCount. Delay it long
            // enough for this collection action to finish and relinquish the
            // body; the item remains a normal world entity afterward.
            excessEntity.setPickUpDelay(MAX_WAIT_TICKS + 20);
            if (!currentItem.level().addFreshEntity(excessEntity)) {
                entityStack.grow(excess.getCount());
                currentItem.setItem(entityStack);
                return;
            }
        }

        currentItem.playerTouch(TaskContext.serverPlayer(player));
    }

    private int inventoryCount(Item item) {
        int total = 0;
        var inventory = TaskContext.serverPlayer(player).getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cancelNav();
    }

    @Override
    protected String successMessage() {
        return "Collected " + collectedCount + " items"
                + (record.itemId != null ? " of " + record.itemId : "");
    }

    @Override
    protected String timeoutMessage() {
        return "Item collection timed out after collecting " + collectedCount + "/" + record.maxCount + " items";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Item collection failed after collecting " + collectedCount + " items";
    }
}
