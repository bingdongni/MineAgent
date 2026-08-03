package com.mineagent.engine.pathing.moves;

/**
 * Reusable result container for movement calculations. Avoids allocation
 * per movement by reusing a single instance across calculations.
 */
public class MutableMoveResult {

    public int x;
    public int y;
    public int z;
    public double cost;
    public boolean valid;

    public MutableMoveResult() {
        reset();
    }

    /** Reset to invalid state for reuse. */
    public void reset() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.cost = 0;
        this.valid = false;
    }

    /** Set a valid result. */
    public void set(int x, int y, int z, double cost) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.cost = cost;
        this.valid = true;
    }

    /** Mark as invalid (movement not possible). */
    public void invalidate() {
        this.valid = false;
    }

    @Override
    public String toString() {
        if (!valid) return "MutableMoveResult[INVALID]";
        return "MutableMoveResult[" + x + ", " + y + ", " + z + "] cost=" + cost;
    }
}
