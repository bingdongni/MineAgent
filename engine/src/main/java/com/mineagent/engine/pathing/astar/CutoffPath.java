package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.Movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A path with a cutoff point - used when the full path cannot be computed
 * (e.g., due to node limit) and we only have a partial path towards the goal.
 * The cutoff index marks where the path was truncated.
 */
public class CutoffPath extends PathBase {

    private final List<Movement> movements;
    private final double totalCost;
    private final int cutoffIndex;
    private final int startX, startY, startZ;

    public CutoffPath(List<Movement> movements, Goal goal, double totalCost,
                      int cutoffIndex, int startX, int startY, int startZ) {
        super(goal);
        this.movements = Collections.unmodifiableList(new ArrayList<>(movements));
        this.totalCost = totalCost;
        this.cutoffIndex = cutoffIndex;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
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
        if (movements.isEmpty()) return startX;
        return movements.get(movements.size() - 1).dstX();
    }

    @Override
    public int destY() {
        if (movements.isEmpty()) return startY;
        return movements.get(movements.size() - 1).dstY();
    }

    @Override
    public int destZ() {
        if (movements.isEmpty()) return startZ;
        return movements.get(movements.size() - 1).dstZ();
    }

    /** Get the cutoff index (where the path was truncated). */
    public int cutoffIndex() { return cutoffIndex; }

    /** Whether this path was cut short. */
    public boolean wasCutoff() { return true; }

    public int startX() { return startX; }
    public int startY() { return startY; }
    public int startZ() { return startZ; }

    @Override
    public String toString() {
        return "CutoffPath[" + length() + " moves, cost=" + totalCost
                + ", cutoff=" + cutoffIndex + "]";
    }
}
