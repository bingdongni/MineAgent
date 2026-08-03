package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.Movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A navigation path - an ordered list of movements from start to goal.
 * The path is the primary output of the A* search.
 */
public class NavPath {

    private final List<Movement> movements;
    private final Goal goal;
    private final double totalCost;
    private final int startX;
    private final int startY;
    private final int startZ;

    public NavPath(List<Movement> movements, Goal goal, double totalCost,
                   int startX, int startY, int startZ) {
        this.movements = Collections.unmodifiableList(new ArrayList<>(movements));
        this.goal = goal;
        this.totalCost = totalCost;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
    }

    /** Get the list of movements in this path. */
    public List<Movement> movements() {
        return movements;
    }

    /** Get the goal this path targets. */
    public Goal goal() {
        return goal;
    }

    /** Get the total path cost. */
    public double totalCost() {
        return totalCost;
    }

    /** Get the number of movements in the path. */
    public int length() {
        return movements.size();
    }

    /** Whether the path is empty. */
    public boolean isEmpty() {
        return movements.isEmpty();
    }

    /** Get a specific movement by index. */
    public Movement get(int index) {
        return movements.get(index);
    }

    /** Get the start position. */
    public int startX() { return startX; }
    public int startY() { return startY; }
    public int startZ() { return startZ; }

    /**
     * Get the destination position (end of the last movement).
     */
    public int destX() {
        if (movements.isEmpty()) return startX;
        return movements.get(movements.size() - 1).dstX();
    }

    public int destY() {
        if (movements.isEmpty()) return startY;
        return movements.get(movements.size() - 1).dstY();
    }

    public int destZ() {
        if (movements.isEmpty()) return startZ;
        return movements.get(movements.size() - 1).dstZ();
    }

    @Override
    public String toString() {
        return "NavPath[" + length() + " moves, cost=" + totalCost
                + ", (" + startX + "," + startY + "," + startZ
                + ") → (" + destX() + "," + destY() + "," + destZ() + ")]";
    }
}
