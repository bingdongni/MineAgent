package com.mineagent.engine.pathing.goals;

/**
 * A pathfinding goal - determines when the A* search has reached its target
 * and provides the heuristic estimate for any position.
 *
 * <p>Different goal types allow the pathfinder to navigate to blocks, areas,
 * altitudes, or away from positions.
 */
public interface Goal {

    /**
     * Whether the given position satisfies this goal.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return true if the position is in the goal
     */
    boolean isInGoal(int x, int y, int z);

    /**
     * Estimate the minimum cost from the given position to the goal.
     * Must be admissible (never overestimate) for A* optimality.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return the heuristic cost estimate
     */
    double heuristic(int x, int y, int z);

    /**
     * Whether this goal can be reached at all from the current world state.
     * Default: true.
     */
    default boolean isImpossible() {
        return false;
    }
}
