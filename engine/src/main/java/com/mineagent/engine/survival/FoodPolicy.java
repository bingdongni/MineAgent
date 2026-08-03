package com.mineagent.engine.survival;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Inventory;

import java.util.Set;

/**
 * Food quality assessment and selection policy.
 * Categorises foods into safety tiers and picks the best food for the
 * companion's current hunger situation.
 */
public final class FoodPolicy {

    private FoodPolicy() {}

    // ── Food quality tiers ─────────────────────────────────────────

    public enum FoodTier {
        /** Always safe: bread, cooked meats, golden foods. */
        SAFE,
        /** Edible but has side-effects: chorus fruit (random teleport). */
        CAUTION,
        /** Only when starving: rotten flesh, pufferfish, spider eye, poisonous potato. */
        DANGEROUS
    }

    // ── Tier mapping ───────────────────────────────────────────────

    // Keep every item exactly once. Set.of intentionally rejects duplicates,
    // because a duplicate here otherwise becomes an ExceptionInInitializerError
    // the first time FoodChain evaluates a newly spawned companion.
    private static final Set<Item> SAFE_FOODS = Set.of(
            Items.BREAD,
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_MUTTON,
            Items.COOKED_CHICKEN,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.COOKED_RABBIT,
            Items.GOLDEN_CARROT,
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.MUSHROOM_STEW,
            Items.RABBIT_STEW,
            Items.BEETROOT_SOUP,
            Items.BAKED_POTATO,
            Items.PUMPKIN_PIE,
            Items.APPLE,
            Items.MELON_SLICE,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT,
            Items.SWEET_BERRIES,
            Items.GLOW_BERRIES,
            Items.DRIED_KELP
    );

    private static final Set<Item> CAUTION_FOODS = Set.of(
            Items.CHORUS_FRUIT
    );

    private static final Set<Item> DANGEROUS_FOODS = Set.of(
            Items.ROTTEN_FLESH,
            Items.PUFFERFISH,
            Items.SPIDER_EYE,
            Items.POISONOUS_POTATO,
            Items.CHICKEN
    );

    /** Classify a food item into a safety tier. */
    public static FoodTier classify(Item item) {
        if (SAFE_FOODS.contains(item)) return FoodTier.SAFE;
        if (CAUTION_FOODS.contains(item)) return FoodTier.CAUTION;
        if (DANGEROUS_FOODS.contains(item)) return FoodTier.DANGEROUS;
        // Unknown edible item — treat as CAUTION
        return FoodTier.CAUTION;
    }

    /** Check if an item is edible (has food properties). */
    public static boolean isEdible(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }

    // ── Best food selection ────────────────────────────────────────

    /**
     * Score a food item for selection priority.
     * Higher scores are preferred. The score considers:
     * <ul>
     *   <li>Nutrition (hunger restored)</li>
     *   <li>Saturation modifier</li>
     *   <li>Safety tier (safe > caution > dangerous)</li>
     * </ul>
     */
    public static float scoreFood(ItemStack stack, int currentHunger) {
        if (!isEdible(stack)) return Float.NEGATIVE_INFINITY;

        Item item = stack.getItem();
        FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food == null) return Float.NEGATIVE_INFINITY;

        float nutrition = food.nutrition();
        float saturation = food.saturation();
        float safetyBonus = switch (classify(item)) {
            case SAFE -> 1000.0f;
            case CAUTION -> 500.0f;
            case DANGEROUS -> {
                // Only consider dangerous food if truly starving (food level ≤ 6)
                yield currentHunger <= 6 ? 100.0f : Float.NEGATIVE_INFINITY;
            }
        };

        return safetyBonus + nutrition * 10.0f + saturation * 5.0f;
    }

    /**
     * Find the best food item in the player's inventory for the current hunger level.
     *
     * @param inventory    the player's inventory
     * @param currentHunger current food level (0-20)
     * @return the slot index and item stack of the best food, or null if none suitable
     */
    public static FoodSlot getBestFood(Inventory inventory, int currentHunger) {
        FoodSlot best = null;
        float bestScore = Float.NEGATIVE_INFINITY;

        // Scan carried inventory, not armor slots 36-39. Component-modified
        // armor can be edible, but swapping it with the selected hotbar item
        // would place an invalid item into the vacated equipment slot.
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isEdible(stack)) continue;

            float score = scoreFood(stack, currentHunger);
            if (score > bestScore) {
                bestScore = score;
                best = new FoodSlot(i, stack);
            }
        }

        if (inventory.getContainerSize() > 40) {
            ItemStack offhand = inventory.getItem(40);
            float score = scoreFood(offhand, currentHunger);
            if (score > bestScore) {
                best = new FoodSlot(40, offhand);
            }
        }

        return best;
    }

    /**
     * A selected food slot: index in the inventory + the item stack.
     */
    public record FoodSlot(int slot, ItemStack stack) {
        /** Whether this food is in the hotbar (slots 0-8). */
        public boolean isInHotbar() {
            return slot >= 0 && slot <= 8;
        }

        /** Convert to hotbar index (0-8), or -1 if not in hotbar. */
        public int hotbarIndex() {
            return isInHotbar() ? slot : -1;
        }
    }
}
