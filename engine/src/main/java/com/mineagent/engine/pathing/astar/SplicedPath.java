package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.Movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A path created by splicing (joining) two path segments together.
 * Used when a path needs to be extended or when two partial paths
 * need to be connected.
 */
public class SplicedPath extends PathBase {

    private final List<Movement> movements;
    private final double totalCost;
    private final PathBase first;
    private final PathBase second;

    public SplicedPath(PathBase first, PathBase second) {
        super(second.goal());
        this.first = first;
        this.second = second;

        List<Movement> combined = new ArrayList<>(first.movements().size() + second.movements().size());
        combined.addAll(first.movements());
        combined.addAll(second.movements());
        this.movements = Collections.unmodifiableList(combined);
        this.totalCost = first.totalCost() + second.totalCost();
    }

    @Override
    public List<Movement> movements() {
        return movements;
    }

    @Override
    public double totalCost() {
        return totalCost;
    }

    @Override
    public int destX() {
        return second.destX();
    }

    @Override
    public int destY() {
        return second.destY();
    }

    @Override
    public int destZ() {
        return second.destZ();
    }

    /** Get the first path segment. */
    public PathBase first() { return first; }

    /** Get the second path segment. */
    public PathBase second() { return second; }

    @Override
    public String toString() {
        return "SplicedPath[" + length() + " moves, cost=" + totalCost
                + ", first=" + first + ", second=" + second + "]";
    }
}
