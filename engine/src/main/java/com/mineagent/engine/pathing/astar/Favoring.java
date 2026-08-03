package com.mineagent.engine.pathing.astar;

/**
 * Coordinate favoring - biases the A* heuristic towards certain coordinates,
 * allowing the search to prefer paths near a favored position.
 *
 * <p>When a favoring is set, the heuristic cost is reduced for nodes closer
 * to the favored coordinates, effectively nudging the search towards them.
 */
public class Favoring {

    private final int favorX;
    private final int favorY;
    private final int favorZ;
    private final double coefficient;

    /**
     * Create a new favoring.
     *
     * @param favorX the X coordinate to favor
     * @param favorY the Y coordinate to favor
     * @param favorZ the Z coordinate to favor
     * @param coefficient how strongly to favor (0.0 = no favoring, 1.0 = strong)
     */
    public Favoring(int favorX, int favorY, int favorZ, double coefficient) {
        this.favorX = favorX;
        this.favorY = favorY;
        this.favorZ = favorZ;
        this.coefficient = coefficient;
    }

    /** No favoring - does not modify the heuristic at all. */
    public static final Favoring NONE = new Favoring(0, 0, 0, 0.0);

    /**
     * Apply favoring bias to a heuristic value.
     *
     * @param heuristic the base heuristic
     * @param x         the node's X
     * @param y         the node's Y
     * @param z         the node's Z
     * @return the biased heuristic (lower = more favorable)
     */
    public double apply(double heuristic, int x, int y, int z) {
        if (coefficient == 0.0) return heuristic;
        double distance = Math.abs((long) x - favorX)
                + Math.abs((long) y - favorY)
                + Math.abs((long) z - favorZ);
        return heuristic - distance * coefficient;
    }

    public int favorX() { return favorX; }
    public int favorY() { return favorY; }
    public int favorZ() { return favorZ; }
    public double coefficient() { return coefficient; }
}
