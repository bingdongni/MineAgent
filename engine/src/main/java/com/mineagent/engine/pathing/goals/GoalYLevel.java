package com.mineagent.engine.pathing.goals;

/**
 * Goal: reach a specific Y altitude. Horizontal position is irrelevant.
 * The companion must be standing at the target Y level.
 */
public class GoalYLevel implements Goal {

    private final int targetY;

    public GoalYLevel(int targetY) {
        this.targetY = targetY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == targetY;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return Math.abs((long) y - targetY);
    }

    public int targetY() { return targetY; }

    @Override
    public String toString() {
        return "GoalYLevel[y=" + targetY + "]";
    }
}
