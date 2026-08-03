package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.Movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Concrete path implementation - a simple ordered list of movements
 * from start to goal.
 */
public class Path extends PathBase {

    private final List<Movement> movements;
    private final double totalCost;
    private final int startX, startY, startZ;

    public Path(List<Movement> movements, Goal goal, double totalCost,
                int startX, int startY, int startZ) {
        super(goal);
        this.movements = Collections.unmodifiableList(new ArrayList<>(movements));
        this.totalCost = totalCost;
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

    public int startX() { return startX; }
    public int startY() { return startY; }
    public int startZ() { return startZ; }

    @Override
    public String toString() {
        return "Path[" + length() + " moves, cost=" + totalCost + "]";
    }
}
