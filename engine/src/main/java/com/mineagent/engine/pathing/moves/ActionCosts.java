package com.mineagent.engine.pathing.moves;

/**
 * Cost constants for each movement type. These values determine how
 * the A* algorithm weighs different actions. Lower cost = preferred.
 *
 * <p>All costs are in arbitrary units; the relative ratios matter.
 * Walking one block horizontally is the baseline at 1.0.
 */
public final class ActionCosts {

    private ActionCosts() {}

    /** Cost of walking one block horizontally (baseline). */
    public static final double WALK = 1.0;

    /** Cost of walking one block diagonally (√2 ≈ 1.414). */
    public static final double WALK_DIAGONAL = 1.414;

    /** Cost of jumping up one block (walk + jump effort). */
    public static final double JUMP_UP = 2.0;

    /** Cost of stepping down one block (slightly cheaper than walking). */
    public static final double STEP_DOWN = 0.8;

    /** Cost of falling 1 block (very cheap - gravity does the work). */
    public static final double FALL_1 = 0.5;

    /** Cost of falling N blocks (scales with distance). */
    public static double fallCost(int distance) {
        return 0.5 + (distance - 1) * 0.3;
    }

    /** Cost of placing a block (bridge-building, pillaring). */
    public static final double PLACE_BLOCK = 5.0;

    /** Cost of breaking a block (dig-through). */
    public static final double BREAK_BLOCK = 6.0;

    /** Cost of sprint-jumping (parkour). */
    public static final double SPRINT_JUMP = 3.0;

    /** Cost of placing a water bucket (MLG landing). */
    public static final double WATER_MLG = 15.0;

    /** Cost penalty for being in water (swimming is slow). */
    public static final double SWIM = 3.0;

    /** Cost penalty for being in lava (very dangerous). */
    public static final double LAVA = 50.0;

    /** Cost of climbing a ladder/vine. */
    public static final double CLIMB = 2.0;

    /** Cost multiplier when sneaking (bridging). */
    public static final double SNEAK_MULTIPLIER = 1.3;
}
