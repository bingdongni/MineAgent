package com.mineagent.engine.pathing.goals;

/**
 * Goal: run away from a position. The companion is "in goal" when
 * the Euclidean distance from the target position exceeds the given
 * escape distance.
 *
 * <p>This is a specialized version of GoalInverted for the common
 * case of fleeing from a point.
 */
public class GoalRunAway implements Goal {

    private final int fromX;
    private final int fromY;
    private final int fromZ;
    private final double distance;

    /**
     * @param fromX    center X to flee from
     * @param fromY    center Y to flee from
     * @param fromZ    center Z to flee from
     * @param distance minimum Manhattan distance to achieve
     */
    public GoalRunAway(int fromX, int fromY, int fromZ, double distance) {
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.distance = distance;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        double dx = (double) x - fromX;
        double dy = (double) y - fromY;
        double dz = (double) z - fromZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) >= distance;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = (double) x - fromX;
        double dy = (double) y - fromY;
        double dz = (double) z - fromZ;
        double currentDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0, distance - currentDist);
    }

    public int fromX() { return fromX; }
    public int fromY() { return fromY; }
    public int fromZ() { return fromZ; }

    @Override
    public String toString() {
        return "GoalRunAway[from=(" + fromX + "," + fromY + "," + fromZ + "), d=" + distance + "]";
    }
}
