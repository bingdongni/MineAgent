package com.mineagent.engine.pathing.goals;

/**
 * Goal: NOT the wrapped goal - the position is in goal when it is NOT
 * in the wrapped goal. This is used for "run away" type objectives where
 * the companion wants to be far from a position.
 *
 * <p>Since the heuristic must be admissible for A* (and an inverted goal
 * is inherently unbounded), this goal uses a distance threshold and
 * declares success when the position is sufficiently far away.
 */
public class GoalInverted implements Goal {

    private final Goal goal;
    private final double distance;

    /**
     * @param goal     the goal to invert
     * @param distance the minimum distance to be considered "away"
     */
    public GoalInverted(Goal goal, double distance) {
        this.goal = goal;
        this.distance = distance;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        // We're "in goal" when we're NOT in the original goal
        // AND we're far enough away
        return !goal.isInGoal(x, y, z) && goal.heuristic(x, y, z) >= distance;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double h = goal.heuristic(x, y, z);
        // Cost is how far we still need to go to get away
        return Math.max(0, distance - h);
    }

    public Goal invertedGoal() { return goal; }

    @Override
    public String toString() {
        return "GoalInverted[" + goal + ", d=" + distance + "]";
    }
}
