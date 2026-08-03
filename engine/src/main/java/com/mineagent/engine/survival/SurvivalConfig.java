package com.mineagent.engine.survival;

/**
 * Configuration record for all survival instinct thresholds and toggles.
 * Immutable by default - create a new instance to change settings.
 */
public record SurvivalConfig(
        int foodLow,
        int foodCritical,
        float healthFlee,
        int stuckTimeTicks,
        boolean autoEat,
        boolean fightBack,
        boolean pickupItems
) {

    /** Sensible defaults for survival behaviour. */
    public static final SurvivalConfig DEFAULTS = new SurvivalConfig(
            12,     // foodLow - start eating at 12/20 hunger
            6,      // foodCritical - urgent eating at 6/20 hunger
            8.0f,   // healthFlee - flee from mobs when health below 8 (4 hearts)
            60,     // stuckTimeTicks - 3 seconds at 20 tps
            true,   // autoEat
            true,   // fightBack
            true    // pickupItems
    );

    public SurvivalConfig {
        if (foodLow < 0 || foodLow > 20) throw new IllegalArgumentException("foodLow must be in [0, 20]");
        if (foodCritical < 0 || foodCritical > foodLow) {
            // A critical threshold above foodLow inverts urgency and marks a
            // nearly full player critical before the player is even hungry.
            throw new IllegalArgumentException("foodCritical must be in [0, foodLow]");
        }
        if (healthFlee < 0) throw new IllegalArgumentException("healthFlee must be >= 0");
        if (stuckTimeTicks < 1) throw new IllegalArgumentException("stuckTimeTicks must be >= 1");
    }

    /** Create a copy with a different autoEat flag. */
    public SurvivalConfig withAutoEat(boolean autoEat) {
        return new SurvivalConfig(foodLow, foodCritical, healthFlee, stuckTimeTicks, autoEat, fightBack, pickupItems);
    }

    /** Create a copy with a different fightBack flag. */
    public SurvivalConfig withFightBack(boolean fightBack) {
        return new SurvivalConfig(foodLow, foodCritical, healthFlee, stuckTimeTicks, autoEat, fightBack, pickupItems);
    }

    /** Create a copy with a different pickupItems flag. */
    public SurvivalConfig withPickupItems(boolean pickupItems) {
        return new SurvivalConfig(foodLow, foodCritical, healthFlee, stuckTimeTicks, autoEat, fightBack, pickupItems);
    }
}
