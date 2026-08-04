package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.survival.FoodPolicy;
import com.mineagent.engine.util.McCompat;
import com.mineagent.tools.inventory.EatItemTool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Executes a food tool call through vanilla's timed item-use state. */
public final class EatItemTask extends CompanionTask<EatItemTool.EatItemTaskRecord> {

    private ServerPlayer serverPlayer;
    private ItemStack consumedType = ItemStack.EMPTY;
    private String foodId;
    private int matchingCountBefore;
    private int foodLevelBefore;
    private int elapsedUseTicks;
    private int maxUseTicks;
    private String failReason;
    private boolean startedUse;

    public EatItemTask(AgentPlayer player, EatItemTool.EatItemTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        serverPlayer = TaskContext.serverPlayer(player);
        Inventory inventory = serverPlayer.getInventory();
        int foodSlot = findFoodSlot(inventory);
        if (foodSlot < 0) {
            failReason = record.itemId == null
                    ? "No safe food is available in the main inventory"
                    : "Food item '" + record.itemId + "' is not available in the main inventory";
            return;
        }

        ItemStack selectedFood = inventory.getItem(foodSlot);
        var food = selectedFood.get(DataComponents.FOOD);
        if (food == null || !serverPlayer.canEat(food.canAlwaysEat())) {
            failReason = "Companion is not hungry enough to eat this item";
            return;
        }

        int selectedSlot = inventory.selected;
        if (foodSlot < Inventory.getSelectionSize()) {
            player.holdInHand(foodSlot);
        } else {
            // Move the actual stack reference into the selected hotbar slot.
            // Copying here would leave two owners for the same food items.
            ItemStack displaced = inventory.getItem(selectedSlot);
            inventory.setItem(selectedSlot, selectedFood);
            inventory.setItem(foodSlot, displaced);
            player.holdInHand(selectedSlot);
            TaskContext.syncInventory(serverPlayer);
        }

        consumedType = serverPlayer.getMainHandItem().copyWithCount(1);
        foodId = BuiltInRegistries.ITEM.getKey(consumedType.getItem()).toString();
        matchingCountBefore = countMatching(inventory, consumedType);
        foodLevelBefore = serverPlayer.getFoodData().getFoodLevel();
        maxUseTicks = Math.max(20, consumedType.getUseDuration(serverPlayer) + 20);
        startedUse = Interaction.useItem(serverPlayer, InteractionHand.MAIN_HAND)
                && serverPlayer.isUsingItem()
                && serverPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND;
        if (!startedUse) failReason = "Vanilla rejected the attempt to start eating " + foodId;
    }

    @Override
    protected TaskState onTick() {
        if (failReason != null) return TaskState.FAILED;
        long now = serverPlayer.level().getGameTime();
        if (record.deadline() > 0L && now >= record.deadline()) {
            failReason = "Eating exceeded its task deadline";
            stopUse();
            return TaskState.FAILED;
        }

        if (serverPlayer.isUsingItem()) {
            if (serverPlayer.getUsedItemHand() != InteractionHand.MAIN_HAND) {
                failReason = "Another item use replaced the eating action";
                stopUse();
                return TaskState.FAILED;
            }
            if (++elapsedUseTicks <= maxUseTicks) return TaskState.RUNNING;
            failReason = "Eating did not finish within the item's expected use duration";
            stopUse();
            return TaskState.FAILED;
        }

        int matchingAfter = countMatching(serverPlayer.getInventory(), consumedType);
        boolean consumed = matchingAfter < matchingCountBefore;
        boolean fed = serverPlayer.getFoodData().getFoodLevel() > foodLevelBefore;
        if (!consumed && !fed) {
            failReason = "Eating was interrupted before the food was consumed";
            return TaskState.FAILED;
        }
        TaskContext.syncInventory(serverPlayer);
        return TaskState.SUCCESS;
    }

    private int findFoodSlot(Inventory inventory) {
        if (record.itemId == null) {
            FoodPolicy.FoodSlot best = FoodPolicy.getBestFood(
                    inventory, serverPlayer.getFoodData().getFoodLevel());
            return best == null ? -1 : best.slot();
        }
        // Vanilla's crafting and hotbar inventory excludes armor/offhand for
        // this operation; moving an edible custom helmet would corrupt armor.
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && McCompat.isItem(stack, record.itemId)
                    && stack.get(DataComponents.FOOD) != null) return slot;
        }
        return -1;
    }

    private static int countMatching(Inventory inventory, ItemStack type) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, type)) count += stack.getCount();
        }
        return count;
    }

    private void stopUse() {
        if (serverPlayer != null && startedUse && serverPlayer.isUsingItem()
                && serverPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            serverPlayer.stopUsingItem();
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() { stopUse(); }

    @Override
    protected String successMessage() { return "Ate one " + foodId; }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason : "Eating failed";
    }

    @Override
    protected String timeoutMessage() { return "Eating timed out for " + foodId; }
}
