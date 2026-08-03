package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.Movement;

import java.util.List;

/**
 * Abstract path base - provides the common interface for all path types.
 * Concrete implementations include simple paths, spliced paths, and
 * cutoff paths.
 */
public abstract class PathBase {

    protected final Goal goal;

    protected PathBase(Goal goal) {
        this.goal = goal;
    }

    /** Get the goal this path targets. */
    public Goal goal() {
        return goal;
    }

    /** Get the list of movements. */
    public abstract List<Movement> movements();

    /** Get the total path cost. */
    public abstract double totalCost();

    /** Get the number of movements. */
    public int length() {
        return movements().size();
    }

    /** Whether the path is empty. */
    public boolean isEmpty() {
        return movements().isEmpty();
    }

    /** Get a specific movement by index. */
    public Movement get(int index) {
        return movements().get(index);
    }

    /**
     * Get the destination block X.
     */
    public abstract int destX();

    /**
     * Get the destination block Y.
     */
    public abstract int destY();

    /**
     * Get the destination block Z.
     */
    public abstract int destZ();
}
