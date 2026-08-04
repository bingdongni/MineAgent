package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.api.task.reflex.ReflexRegistry;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Automatically performs one complete vanilla eating action when hungry. */
public final class FoodChain implements TaskChain {

    private static final float PRIORITY_HUNGRY = 3.0f;
    private static final float PRIORITY_CRITICAL = 4.5f;
    private static final float PRIORITY_STARVING = 6.5f;
    private static final int MAX_USE_TICKS = 80;

    private final SurvivalConfig config;
    private final CompanionBodyLog bodyLog;

    private enum Phase { IDLE, STARTING, EATING }
    private Phase phase = Phase.IDLE;
    private int useTicks;
    private int retryCooldown;
    private int foodBefore;
    private String selectedFoodId;

    public FoodChain(AgentPlayer companion, SurvivalConfig config) {
        this.config = config;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() { return "food"; }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            if (phase != Phase.IDLE) return currentPriority(companion.foodLevel());
            if (retryCooldown > 0) {
                retryCooldown--;
                return Float.NEGATIVE_INFINITY;
            }
            boolean enabled = ReflexRegistry.get("auto_eat")
                    .map(reflex -> reflex.isEnabled(companion))
                    .orElse(config.autoEat());
            if (!enabled) return Float.NEGATIVE_INFINITY;
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            if (sp.isUsingItem() && sp.getUseItem().get(
                    net.minecraft.core.component.DataComponents.FOOD) != null) {
                // An explicit eat_item task already owns the same vanilla use
                // state. Competing for body control here would cancel a valid
                // meal halfway through, especially at starvation priority.
                return Float.NEGATIVE_INFINITY;
            }
            int food = companion.foodLevel();
            return food < config.foodLow()
                    ? currentPriority(food) : Float.NEGATIVE_INFINITY;
        } catch (Exception error) {
            System.err.println("[MineAgent] Food priority error: " + error.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    private float currentPriority(int food) {
        if (food <= 2) return PRIORITY_STARVING;
        if (food <= config.foodCritical()) return PRIORITY_CRITICAL;
        return PRIORITY_HUNGRY;
    }

    @Override
    public void tick(AgentPlayer companion) {
        ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
        try {
            switch (phase) {
                case IDLE -> prepareFood(companion, sp);
                case STARTING -> startEating(companion, sp);
                case EATING -> waitForCompletion(companion, sp);
            }
        } catch (Exception error) {
            System.err.println("[MineAgent] Food tick error: " + error.getMessage());
            cleanup(companion, true);
        }
    }

    private void prepareFood(AgentPlayer companion, ServerPlayer sp) {
        Inventory inventory = sp.getInventory();
        FoodPolicy.FoodSlot food = FoodPolicy.getBestFood(inventory, companion.foodLevel());
        if (food == null) {
            bodyLog.report("need to eat but have no safe food");
            retryCooldown = 100;
            cleanup(companion, false);
            return;
        }

        if (food.isInHotbar()) {
            companion.holdInHand(food.hotbarIndex());
        } else {
            // Swap exact stack references. A copy here duplicates food because
            // the source slot would still own the original stack.
            int selected = inventory.selected;
            ItemStack displaced = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(food.slot()));
            inventory.setItem(food.slot(), displaced);
            companion.holdInHand(selected);
        }
        TaskContext.syncInventory(sp);
        selectedFoodId = BuiltInRegistries.ITEM.getKey(sp.getMainHandItem().getItem()).toString();
        foodBefore = companion.foodLevel();
        useTicks = 0;
        phase = Phase.STARTING;
        bodyLog.report("eating " + sp.getMainHandItem().getHoverName().getString());
    }

    private void startEating(AgentPlayer companion, ServerPlayer sp) {
        ItemStack held = sp.getMainHandItem();
        var food = held.get(net.minecraft.core.component.DataComponents.FOOD);
        if (held.isEmpty() || food == null || !sp.canEat(food.canAlwaysEat())) {
            cleanup(companion, false);
            return;
        }

        // One use call starts the vanilla use-duration state. doTick advances
        // it; repeated right clicks can restart or redirect the action.
        if (!Interaction.useItem(sp, InteractionHand.MAIN_HAND)
                || !sp.isUsingItem()
                || sp.getUsedItemHand() != InteractionHand.MAIN_HAND) {
            bodyLog.report("couldn't start eating " + selectedFoodId);
            retryCooldown = 40;
            cleanup(companion, false);
            return;
        }
        phase = Phase.EATING;
    }

    private void waitForCompletion(AgentPlayer companion, ServerPlayer sp) {
        useTicks++;
        if (sp.isUsingItem()) {
            if (useTicks <= MAX_USE_TICKS) return;
            bodyLog.report("eating took too long and was cancelled");
            cleanup(companion, true);
            return;
        }

        // Completion mutates hunger and the held stack. Requiring a hunger
        // increase avoids reporting knockback/interruption as a meal.
        if (companion.foodLevel() > foodBefore) {
            TaskContext.syncInventory(sp);
            bodyLog.report("finished eating " + selectedFoodId);
        } else {
            bodyLog.report("eating was interrupted before completion");
            retryCooldown = 20;
        }
        cleanup(companion, false);
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        cleanup(companion, true);
    }

    private void cleanup(AgentPlayer companion, boolean cancelUse) {
        ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
        if (cancelUse && sp.isUsingItem()
                && sp.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            sp.stopUsingItem();
        }
        inputDriver(companion).clear();
        phase = Phase.IDLE;
        useTicks = 0;
        foodBefore = 0;
        selectedFoodId = null;
    }

    private static InputDriver inputDriver(AgentPlayer companion) {
        return ((CompanionEntity) companion).inputDriver();
    }
}
