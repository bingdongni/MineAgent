package com.mineagent.engine.pathing.goals;

/**
 * Goal: get adjacent to a block at (x, y, z).
 * The companion must be standing on any of the 6 face-adjacent blocks.
 * This is the standard "interact with block" goal - the companion walks
 * up to the target block without standing on it.
 */
public class GoalGetToBlock implements Goal {

    private final int x;
    private final int y;
    private final int z;

    public GoalGetToBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        // Standing adjacent means Manhattan distance = 1 and same Y
        // or directly above/below (Y differs by 1, same XZ)
        long dx = Math.abs((long) px - x);
        long dy = Math.abs((long) py - y);
        long dz = Math.abs((long) pz - z);
        if (dx + dz == 1 && dy == 0) return true;  // horizontal neighbor
        if (dx == 0 && dz == 0 && dy == 1) return true;  // one above
        return false;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double dx = (double) px - x;
        double dy = (double) py - y;
        double dz = (double) pz - z;
        // Euclidean distance to the nearest face-adjacent cell is an
        // admissible lower bound even when diagonal moves are available.
        return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 1.0);
    }

    public int goalX() { return x; }
    public int goalY() { return y; }
    public int goalZ() { return z; }

    @Override
    public String toString() {
        return "GoalGetToBlock[" + x + ", " + y + ", " + z + "]";
    }
}
