package com.mineagent.engine.pathing.moves;

/**
 * Enumeration of all movement types the pathfinder considers.
 * Each move describes the offset from the current position and what
 * type of movement calculation to perform.
 */
public enum Moves {

    // Cardinal horizontal movements
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),

    // Diagonal movements
    NORTHEAST(1, 0, -1),
    NORTHWEST(-1, 0, -1),
    SOUTHEAST(1, 0, 1),
    SOUTHWEST(-1, 0, 1),

    // Ascend (jump up 1 block)
    ASCEND_NORTH(0, 1, -1),
    ASCEND_SOUTH(0, 1, 1),
    ASCEND_EAST(1, 1, 0),
    ASCEND_WEST(-1, 1, 0),

    // Descend (step down 1 block)
    DESCEND_NORTH(0, -1, -1),
    DESCEND_SOUTH(0, -1, 1),
    DESCEND_EAST(1, -1, 0),
    DESCEND_WEST(-1, -1, 0),

    // Fall (drop 2+ blocks)
    FALL_NORTH(0, -1, -1),
    FALL_SOUTH(0, -1, 1),
    FALL_EAST(1, -1, 0),
    FALL_WEST(-1, -1, 0),

    // Pillar (straight up)
    PILLAR(0, 1, 0),

    // Parkour (2-block gap jump)
    PARKOUR_NORTH(0, 0, -2),
    PARKOUR_SOUTH(0, 0, 2),
    PARKOUR_EAST(2, 0, 0),
    PARKOUR_WEST(-2, 0, 0),
    ;

    public final int offsetX;
    public final int offsetY;
    public final int offsetZ;

    Moves(int offsetX, int offsetY, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    /** Whether this move is diagonal. */
    public boolean isDiagonal() {
        return offsetX != 0 && offsetZ != 0 && offsetY == 0;
    }

    /** Whether this move is a cardinal horizontal move. */
    public boolean isCardinal() {
        return offsetY == 0 && (offsetX == 0 || offsetZ == 0) && (offsetX != 0 || offsetZ != 0);
    }

    /** Whether this is an ascending move. */
    public boolean isAscend() {
        return offsetY > 0 && (offsetX != 0 || offsetZ != 0);
    }

    /** Whether this is a descending move. */
    public boolean isDescend() {
        return offsetY < 0 && (offsetX != 0 || offsetZ != 0);
    }

    /** Whether this is a pillar move. */
    public boolean isPillar() {
        return offsetX == 0 && offsetZ == 0 && offsetY == 1;
    }

    /** Whether this is a parkour move. */
    public boolean isParkour() {
        return name().startsWith("PARKOUR");
    }

    /** Whether this is a fall move. */
    public boolean isFall() {
        return name().startsWith("FALL");
    }
}
