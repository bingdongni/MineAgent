package com.mineagent.engine.pathing.goals;

/** Reach a horizontal radius around a coordinate at any valid altitude. */
public final class GoalNearXZ implements Goal {

    private final int x;
    private final int z;
    private final int radius;

    public GoalNearXZ(int x, int z, int radius) {
        this.x = x;
        this.z = z;
        this.radius = Math.max(0, radius);
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        double dx = (double) px - x;
        double dz = (double) pz - z;
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double dx = (double) px - x;
        double dz = (double) pz - z;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - radius);
    }

    @Override
    public String toString() {
        return "GoalNearXZ[" + x + ", " + z + ", r=" + radius + "]";
    }
}
