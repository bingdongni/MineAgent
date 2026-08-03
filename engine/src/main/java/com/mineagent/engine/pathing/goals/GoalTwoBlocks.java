package com.mineagent.engine.pathing.goals;

/**
 * Goal: reach EITHER of two block positions (OR-goal).
 * The position is in goal if it satisfies either sub-goal.
 * The heuristic is the minimum of the two sub-goal heuristics.
 */
public class GoalTwoBlocks implements Goal {

    private final int x1, y1, z1;
    private final int x2, y2, z2;

    public GoalTwoBlocks(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.x2 = x2; this.y2 = y2; this.z2 = z2;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return (x == x1 && y == y1 && z == z1)
                || (x == x2 && y == y2 && z == z2);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx1 = (double) x1 - x, dy1 = (double) y1 - y, dz1 = (double) z1 - z;
        double dx2 = (double) x2 - x, dy2 = (double) y2 - y, dz2 = (double) z2 - z;
        double h1 = Math.sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1);
        double h2 = Math.sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2);
        return Math.min(h1, h2);
    }

    @Override
    public String toString() {
        return "GoalTwoBlocks[(" + x1 + "," + y1 + "," + z1 + ") OR ("
                + x2 + "," + y2 + "," + z2 + ")]";
    }
}
