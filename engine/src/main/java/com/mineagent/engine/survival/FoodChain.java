package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Auto eat when hungry — monitors the companion's food level and
 * automatically eats when it drops below configured thresholds.
 *
 * <p>Priority:
 * <ul>
 *   <li>4.0 — starving (food level below critical threshold)</li>
 *   <li>3.0 — hungry (food level below low threshold)</li>
 * </ul>
 *
 * <p>Uses {@link FoodPolicy} to select the best food and filter out
 * dangerous items (unless starving).
 * Controlled by the {@code auto_eat} reflex.
 */
public final class FoodChain implements TaskChain {

    private static final float PRIORITY_STARVING = 4.0f;
    private static final float PRIORITY_HUNGRY = 3.0f;

    private final SurvivalConfig config;
    private final CompanionBodyLog bodyLog;
    private final AgentPlayer companion;

    private enum Phase { IDLE, HOLDING_FOOD, EATING }
    private Phase phase = Phase.IDLE;
    private int eatTicks = 0;
    private FoodPolicy.FoodSlot selectedFood = null;

    public FoodChain(AgentPlayer companion, SurvivalConfig config) {
        this.companion = companion;
        this.config = config;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() {
        return "food";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            // Configuration seeds this per-companion reflex at spawn time.
            // Consulting config again here would make a runtime UI enable a
            // no-op whenever the default was false.
            boolean reflexEnabled = com.mineagent.api.task.reflex.ReflexRegistry
                    .get("auto_eat").map(r -> r.isEnabled(companion)).orElse(true);
            if (!reflexEnabled) {
                return Float.NEGATIVE_INFINITY;
            }

            // If currently eating, maintain priority
            if (phase == Phase.HOLDING_FOOD || phase == Phase.EATING) {
                return Math.max(PRIORITY_HUNGRY,
                        SurvivalDecisions.foodHungerPriority(companion.foodLevel()));
            }

            int foodLevel = companion.foodLevel();
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            if (FoodPolicy.getBestFood(sp.getInventory(), foodLevel) == null) {
                return Float.NEGATIVE_INFINITY;
            }
            if (foodLevel <= config.foodCritical()) {
                // Truly starving — highest food priority
                return SurvivalDecisions.foodHungerPriority(foodLevel);
            }
            if (foodLevel < config.foodLow()) {
                return SurvivalDecisions.foodHungerPriority(foodLevel);
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Food getPriority error: " + e.getMessage());
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(AgentPlayer companion) {
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            Inventory inventory = sp.getInventory();
            int foodLevel = companion.foodLevel();

            switch (phase) {
                case IDLE -> {
                    // Find best food for current hunger level
                    selectedFood = FoodPolicy.getBestFood(inventory, foodLevel);
                    if (selectedFood == null) {
                        // No food available — nothing we can do
                        bodyLog.report("need to eat but have no food");
                        reset();
                        return;
                    }

                    // If food is in hotbar, switch to it directly
                    if (selectedFood.isInHotbar()) {
                        companion.holdInHand(selectedFood.hotbarIndex());
                        phase = Phase.HOLDING_FOOD;
                        eatTicks = 0;
                    } else {
                        // Food is in main inventory — swap it with the currently
                        // selected hotbar slot using a REAL swap (not a copy).
                        // The previous code used `setItem(currentSlot, stack.copy())`
                        // which left the original stack in place, effectively
                        // duplicating the food item every time the companion
                        // ate from the main inventory.
                        int currentSlot = inventory.selected;
                        ItemStack hotbarItem = inventory.getItem(currentSlot);
                        ItemStack foodItem = inventory.getItem(selectedFood.slot());
                        inventory.setItem(currentSlot, foodItem);
                        inventory.setItem(selectedFood.slot(), hotbarItem);
                        com.mineagent.engine.task.TaskContext.syncInventory(sp);
                        companion.holdInHand(currentSlot);
                        phase = Phase.HOLDING_FOOD;
                        eatTicks = 0;
                    }
                    bodyLog.report("eating " + selectedFood.stack().getDescriptionId());
                }
                case HOLDING_FOOD -> {
                    // Hold food for 2 ticks before eating (simulate human delay)
                    eatTicks++;
                    if (eatTicks >= 2) {
                        sp.gameMode.useItem(sp, sp.serverLevel(), sp.getMainHandItem(),
                                InteractionHand.MAIN_HAND);
                        if (!sp.isUsingItem()) {
                            bodyLog.report("could not start eating");
                            reset();
                            return;
                        }
                        phase = Phase.EATING;
                        eatTicks = 0;
                    }
                }
                case EATING -> {
                    eatTicks++;
                    if (!sp.isUsingItem()) {
                        bodyLog.report("finished eating");
                        reset();
                        return;
                    }
                    if (eatTicks > 100) {
                        bodyLog.report("eating timed out");
                        reset();
                        return;
                    }
                    // Eating takes about 32 ticks (1.6 seconds) in vanilla
                    // We hold right-click during the eating animation
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] Food tick error: " + e.getMessage());
            reset();
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        reset();
    }

    private void reset() {
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            sp.stopUsingItem();
            inputDriver(companion).clear();
        } catch (Exception ignored) {
        }
        phase = Phase.IDLE;
        eatTicks = 0;
        selectedFood = null;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }
}
