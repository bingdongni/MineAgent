package com.mineagent.engine.pathing.goals;

/**
 * Goal: stand in a nearby cell from which a block can be placed at the target.
 *
 * <p>The generic interaction goal also accepts the cell directly below a
 * target. That is valid for mining, but a player's two-block-tall body then
 * overlaps a solid block placed at the target. Placement needs a distinct
 * goal which stays horizontally offset (at up to one block of height
 * difference), or directly above the target for a legitimate place-below
 * action.
 */
public final class GoalPlaceBlock implements Goal {

    private final int x;
    private final int y;
    private final int z;

    public GoalPlaceBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        long dx = Math.abs((long) px - x);
        long dz = Math.abs((long) pz - z);
        long dy = (long) py - y;

        // Any of the eight horizontally neighboring columns is close enough
        // to click the target, including a one-block height difference.
        if (Math.max(dx, dz) == 1 && Math.abs(dy) <= 1) return true;

        // Standing directly above an empty target is also collision-safe. The
        // mirrored cell below is deliberately excluded because the player's
        // head/body occupies the block that would be placed.
        return dx == 0 && dz == 0 && dy == 1;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double best = Double.POSITIVE_INFINITY;
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    boolean horizontalNeighbor = Math.max(
                            Math.abs(xOffset), Math.abs(zOffset)) == 1;
                    boolean directlyAbove = xOffset == 0
                            && zOffset == 0 && yOffset == 1;
                    if (!horizontalNeighbor && !directlyAbove) continue;

                    double dx = (double) px - (x + xOffset);
                    double dy = (double) py - (y + yOffset);
                    double dz = (double) pz - (z + zOffset);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return "GoalPlaceBlock[" + x + ", " + y + ", " + z + "]";
    }
}
