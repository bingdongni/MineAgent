package com.mineagent.engine.pathing.astar;

import net.minecraft.core.BlockPos;

/**
 * A single node in the A* search graph. Each node represents a block position
 * and carries cost information for the pathfinding algorithm.
 *
 * <p>PathNode instances are pooled and reused to reduce GC pressure during
 * path searches. The {@link #reset} method prepares a node for reuse.
 */
public class PathNode {

    /** The block position this node represents. */
    public int x;
    public int y;
    public int z;

    /** The cost from start to this node (g-cost). */
    public double cost;

    /** The estimated total cost from start to goal through this node (f = g + h). */
    public double estimatedTotalCost;

    /** The heuristic component (h-cost). */
    public double heuristic;

    /** The parent node in the search path. */
    public PathNode parent;

    /** Index within the binary heap. -1 means not in the heap. */
    public int heapIndex;

    /** Whether this node is in the closed set. */
    public boolean closed;

    public PathNode() {
        this.heapIndex = -1;
    }

    /** Reset this node for reuse from the object pool. */
    public void reset(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.cost = 0;
        this.estimatedTotalCost = 0;
        this.heuristic = 0;
        this.parent = null;
        this.heapIndex = -1;
        this.closed = false;
    }

    /** Get the BlockPos representation of this node. */
    public BlockPos asBlockPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public String toString() {
        return "PathNode[" + x + ", " + y + ", " + z + "] cost=" + cost
                + " total=" + estimatedTotalCost;
    }
}
