package com.mineagent.engine.pathing.goals;

/**
 * Goal: reach the exact block position (x, y, z).
 * The companion must be standing on this block.
 */
public class GoalBlock implements Goal {

    private final int x;
    private final int y;
    private final int z;

    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // Convert before subtraction so callers outside the command bounds
        // cannot overflow an int and corrupt A*'s heuristic ordering.
        double dx = (double) this.x - x;
        double dy = (double) this.y - y;
        double dz = (double) this.z - z;
        // Use Euclidean distance as admissible heuristic
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public int goalX() { return x; }
    public int goalY() { return y; }
    public int goalZ() { return z; }

    @Override
    public String toString() {
        return "GoalBlock[" + x + ", " + y + ", " + z + "]";
    }
}
