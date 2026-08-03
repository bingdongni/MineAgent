package com.mineagent.engine.pathing.goals;

/**
 * Goal: get within a certain radius of a block position.
 * The companion is "in goal" when the three-dimensional distance to the
 * center is less than or equal to the radius.
 */
public class GoalNear implements Goal {

    private final int x;
    private final int y;
    private final int z;
    private final int radius;

    public GoalNear(int x, int y, int z, int radius) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = Math.max(0, radius);
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        double dx = (double) px - x;
        double dy = (double) py - y;
        double dz = (double) pz - z;
        return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double dx = (double) px - x;
        double dy = (double) py - y;
        double dz = (double) pz - z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double excess = dist - radius;
        return Math.max(0, excess);
    }

    public int goalX() { return x; }
    public int goalY() { return y; }
    public int goalZ() { return z; }

    @Override
    public String toString() {
        return "GoalNear[" + x + ", " + y + ", " + z + ", r=" + radius + "]";
    }
}
