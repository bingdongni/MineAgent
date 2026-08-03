package com.mineagent.engine.pathing.goals;

/**
 * Goal: reach a specific horizontal coordinate (x, z) at any Y level.
 * The companion must be standing at the XZ position; altitude is irrelevant.
 */
public class GoalXZ implements Goal {

    private final int x;
    private final int z;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        return px == x && pz == z;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double dx = (double) px - x;
        double dz = (double) pz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public int goalX() { return x; }
    public int goalZ() { return z; }

    @Override
    public String toString() {
        return "GoalXZ[" + x + ", " + z + "]";
    }
}
