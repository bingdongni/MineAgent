package com.mineagent.engine.pathing.astar;

import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.pathing.moves.movements.*;

import java.util.*;

/**
 * Full A* pathfinding algorithm with binary heap, node pooling, and
 * cutoff support. This is the core search engine for the pathing system.
 *
 * <p>The search is interruptible — it checks a cutoff condition every
 * {@link #CUTOFF_CHECK_INTERVAL} nodes, allowing the caller to cancel
 * long-running searches.
 *
 * <p>Key features:
 * <ul>
 *   <li>Binary min-heap for efficient open-set management</li>
 *   <li>Node pooling to reduce GC pressure</li>
 *   <li>Long-based position keys for fast hash lookups</li>
 *   <li>Interruptible search via cutoff supplier</li>
 *   <li>Coordinate favoring for heuristic bias</li>
 * </ul>
 */
public class AStarPathFinder {

    /** How many nodes to explore between cutoff checks. */
    private static final int CUTOFF_CHECK_INTERVAL = 256;

    /** Default maximum nodes to explore before giving up. */
    private static final int DEFAULT_NODE_LIMIT = 50_000;

    /** Maximum fall distance the pathfinder will consider. */
    private static final int MAX_FALL_DISTANCE = 20;

    private final int nodeLimit;
    private final boolean allowParkour;
    private final ObjectPool<PathNode> nodePool;
    private final Long2ObjectOpenHashMap visitedNodes;
    private final BinaryHeap openHeap;
    private final MutableMoveResult moveResult;

    private int nodesExplored;
    private volatile boolean cancelled;

    // ── Sliced (non-blocking) search state ──────────────────────────
    // When a search is sliced across multiple ticks, these fields hold
    // the live context between continueSearch() calls. This avoids
    // blocking the server tick thread on long pathfinding runs.
    private Goal currentGoal;
    private Favoring currentFavoring;
    private CalculationContext currentCtx;
    private int startNodeX, startNodeY, startNodeZ;
    private boolean searchActive;
    private PathCalcResult searchResult;

    public AStarPathFinder() {
        this(DEFAULT_NODE_LIMIT, true);
    }

    public AStarPathFinder(int nodeLimit) {
        this(nodeLimit, true);
    }

    public AStarPathFinder(int nodeLimit, boolean allowParkour) {
        this.nodeLimit = nodeLimit;
        this.allowParkour = allowParkour;
        this.nodePool = new ObjectPool<>(PathNode::new);
        this.visitedNodes = new Long2ObjectOpenHashMap(1024);
        this.openHeap = new BinaryHeap();
        this.moveResult = new MutableMoveResult();
    }

    /**
     * Cancel the current search. Can be called from another thread.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Run the A* search synchronously from start to goal.
     *
     * <p>This is a convenience wrapper around the sliced API
     * ({@link #initializeSearch} + {@link #continueSearch}). It runs
     * the entire search in one call and blocks the caller until it
     * completes. Prefer the sliced API from tick-driven contexts
     * (e.g. {@code PathingCore}) to avoid stalling the server tick.
     *
     * @param startX  start X
     * @param startY  start Y
     * @param startZ  start Z
     * @param goal    the pathfinding goal
     * @param favoring coordinate favoring for heuristic bias (or Favoring.NONE)
     * @param ctx     the calculation context for world queries
     * @return the path calculation result
     */
    public PathCalcResult search(int startX, int startY, int startZ,
                                  Goal goal, Favoring favoring,
                                  CalculationContext ctx) {
        if (!initializeSearch(startX, startY, startZ, goal, favoring, ctx)) {
            return searchResult;
        }
        // Run to completion using the sliced loop with a generous budget.
        // The nodeLimit is still enforced inside continueSearch().
        while (continueSearch(nodeLimit)) {
            // keep going until the search completes
        }
        return searchResult;
    }

    /**
     * Initialize a new sliced (non-blocking) A* search. Sets up the
     * start node and clears previous state, but does <b>not</b> enter
     * the main expansion loop. After this returns, call
     * {@link #continueSearch(int)} repeatedly until
     * {@link #isSearchActive()} returns false, then read
     * {@link #searchResult()}.
     *
     * <p>This is the entry point for tick-driven pathfinding: it lets
     * the caller bound how much work each tick spends on search,
     * preventing the server tick thread from stalling on long paths.
     *
     * @return true if the search needs to continue via
     *         {@link #continueSearch(int)}; false if the search already
     *         completed in this call (e.g. already at goal) — in that
     *         case read {@link #searchResult()} for the answer.
     */
    public boolean initializeSearch(int startX, int startY, int startZ,
                                     Goal goal, Favoring favoring,
                                     CalculationContext ctx) {
        this.cancelled = false;
        this.nodesExplored = 0;
        this.visitedNodes.clear();
        this.openHeap.clear();
        this.nodePool.reset();
        this.searchResult = null;
        this.currentGoal = goal;
        this.currentFavoring = favoring;
        this.currentCtx = ctx;
        this.startNodeX = startX;
        this.startNodeY = startY;
        this.startNodeZ = startZ;

        // Check if the goal is impossible
        if (goal.isImpossible()) {
            this.searchActive = false;
            this.searchResult = PathCalcResult.failure(
                    PathCalcResult.FailureReason.GOAL_IMPOSSIBLE, 0);
            return false;
        }

        // Check if already at the goal
        if (goal.isInGoal(startX, startY, startZ)) {
            this.searchActive = false;
            this.searchResult = PathCalcResult.success(
                    new Path(Collections.emptyList(), goal, 0, startX, startY, startZ),
                    0);
            return false;
        }

        // Create start node
        PathNode startNode = nodePool.acquire();
        startNode.reset(startX, startY, startZ);
        startNode.heuristic = favoring.apply(
                goal.heuristic(startX, startY, startZ), startX, startY, startZ);
        startNode.estimatedTotalCost = startNode.heuristic;

        visitedNodes.put(posKey(startX, startY, startZ), startNode);
        openHeap.insert(startNode);

        this.searchActive = true;
        return true;
    }

    /**
     * Continue the sliced A* search for up to {@code maxNodes} more
     * node expansions.
     *
     * <p>This is the workhorse of the non-blocking pathfinder: each
     * call explores a bounded number of nodes, then returns control
     * to the caller so the server tick can finish on time. When the
     * search completes (path found, no path, cancelled, or node limit
     * reached), {@link #isSearchActive()} becomes false and the result
     * is available via {@link #searchResult()}.
     *
     * @param maxNodes maximum number of nodes to expand this call.
     *                 Must be &gt; 0. Typical values: 500–2000 per tick.
     * @return true if the search is still active (call again next
     *         tick); false if the search has finished — read
     *         {@link #searchResult()} for the outcome.
     */
    public boolean continueSearch(int maxNodes) {
        if (!searchActive) return false;
        if (maxNodes <= 0) return true;

        int exploredThisCall = 0;
        while (!openHeap.isEmpty()) {
            // Hard cancellation (from another thread)
            if (cancelled) {
                searchResult = buildCutoffResult(
                        currentGoal, startNodeX, startNodeY, startNodeZ);
                searchActive = false;
                return false;
            }
            // Node limit reached — produce the best partial path we have
            if (nodesExplored >= nodeLimit) {
                searchResult = buildCutoffResult(
                        currentGoal, startNodeX, startNodeY, startNodeZ);
                searchActive = false;
                return false;
            }
            // Budget exhausted for this call — yield back to the caller.
            // The search state is fully preserved in the open heap /
            // visited map, so we can resume next tick.
            if (exploredThisCall >= maxNodes) {
                return true;
            }
            // Periodic soft-cutoff check (subclass hook)
            if (exploredThisCall % CUTOFF_CHECK_INTERVAL == 0 && shouldCutoff()) {
                searchResult = buildCutoffResult(
                        currentGoal, startNodeX, startNodeY, startNodeZ);
                searchActive = false;
                return false;
            }

            PathNode current = openHeap.pop();
            current.closed = true;
            nodesExplored++;
            exploredThisCall++;

            // Goal reached — assemble the full path
            if (currentGoal.isInGoal(current.x, current.y, current.z)) {
                searchResult = buildPathResult(
                        current, currentGoal, startNodeX, startNodeY, startNodeZ);
                searchActive = false;
                return false;
            }

            // Expand neighbors
            expandNode(current, currentGoal, currentFavoring, currentCtx);
        }

        // Open set is empty — no path exists
        searchResult = PathCalcResult.failure(
                PathCalcResult.FailureReason.NO_PATH, nodesExplored);
        searchActive = false;
        return false;
    }

    /**
     * Is a sliced search currently in progress?
     */
    public boolean isSearchActive() {
        return searchActive;
    }

    /**
     * Get the result of a completed sliced search.
     *
     * @return the result, or null if the search is still active or
     *         was never started.
     */
    public PathCalcResult searchResult() {
        return searchResult;
    }

    /**
     * Abort the current sliced search and discard any partial state.
     * Safe to call even if no search is active.
     */
    public void abortSearch() {
        this.searchActive = false;
        this.searchResult = null;
    }

    /** Number of nodes explored so far in the current/last search. */
    public int nodesExplored() {
        return nodesExplored;
    }

    /**
     * Expand a node by considering all possible movements from it.
     */
    private void expandNode(PathNode current, Goal goal, Favoring favoring,
                            CalculationContext ctx) {
        // Cardinal movements
        expandCardinal(current, 0, 0, -1, goal, favoring, ctx);  // North
        expandCardinal(current, 0, 0, 1, goal, favoring, ctx);   // South
        expandCardinal(current, 1, 0, 0, goal, favoring, ctx);   // East
        expandCardinal(current, -1, 0, 0, goal, favoring, ctx);  // West

        // Diagonal movements
        expandDiagonal(current, 1, -1, goal, favoring, ctx);   // NE
        expandDiagonal(current, -1, -1, goal, favoring, ctx);  // NW
        expandDiagonal(current, 1, 1, goal, favoring, ctx);    // SE
        expandDiagonal(current, -1, 1, goal, favoring, ctx);   // SW

        // Pillar up
        expandPillar(current, goal, favoring, ctx);

        // Real vertical traversal for ladders, vines and scaffolding. This is
        // distinct from a pillar: it consumes no block and also supports down.
        expandClimb(current, 1, goal, favoring, ctx);
        expandClimb(current, -1, goal, favoring, ctx);

        if (allowParkour) {
            // Parkour is optional because its jump execution is less robust
            // than ordinary walking on high-latency or low-TPS servers.
            expandParkour(current, 0, -2, goal, favoring, ctx);  // North
            expandParkour(current, 0, 2, goal, favoring, ctx);   // South
            expandParkour(current, 2, 0, goal, favoring, ctx);   // East
            expandParkour(current, -2, 0, goal, favoring, ctx);  // West
        }
    }

    /**
     * Expand a cardinal (horizontal) movement.
     */
    private void expandCardinal(PathNode current, int dx, int dy, int dz,
                                 Goal goal, Favoring favoring,
                                 CalculationContext ctx) {
        int nx = current.x + dx;
        int nz = current.z + dz;

        // Try same-level traverse first
        double cost = calculateTraverseCost(current.x, current.y, current.z,
                nx, current.y, nz, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, nx, current.y, nz, cost, goal, favoring);
        }

        // Try ascending (jump up 1)
        cost = calculateAscendCost(current.x, current.y, current.z,
                nx, current.y + 1, nz, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, nx, current.y + 1, nz, cost, goal, favoring);
        }

        // Try descending (step down 1)
        cost = calculateDescendCost(current.x, current.y, current.z,
                nx, current.y - 1, nz, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, nx, current.y - 1, nz, cost, goal, favoring);
        }

        // Try falling (2+ blocks)
        for (int fallDist = 2; fallDist <= MAX_FALL_DISTANCE; fallDist++) {
            int targetY = current.y - fallDist;
            cost = calculateFallCost(current.x, current.y, current.z,
                    nx, targetY, nz, ctx, fallDist);
            if (cost < Double.POSITIVE_INFINITY) {
                considerNode(current, nx, targetY, nz, cost, goal, favoring);
            }
            // If there's a block in the way, stop falling further
            if (ctx.getBlockState(nx, targetY, nz) != null
                    && !com.mineagent.engine.pathing.util.BlockHelper.isPassable(
                            ctx.getBlockState(nx, targetY, nz))) {
                break;
            }
        }
    }

    /**
     * Expand a diagonal movement.
     */
    private void expandDiagonal(PathNode current, int dx, int dz,
                                 Goal goal, Favoring favoring,
                                 CalculationContext ctx) {
        int nx = current.x + dx;
        int nz = current.z + dz;

        double cost = calculateDiagonalCost(current.x, current.y, current.z,
                nx, nz, dx, dz, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, nx, current.y, nz, cost, goal, favoring);
        }
    }

    /**
     * Expand a pillar (vertical up) movement.
     */
    private void expandPillar(PathNode current, Goal goal, Favoring favoring,
                               CalculationContext ctx) {
        int ny = current.y + 1;
        double cost = calculatePillarCost(current.x, current.y, current.z, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, current.x, ny, current.z, cost, goal, favoring);
        }
    }

    private void expandClimb(PathNode current, int dy, Goal goal,
                             Favoring favoring, CalculationContext ctx) {
        int ny = current.y + dy;
        MovementClimb move = new MovementClimb(
                current.x, current.y, current.z, ny);
        double cost = move.calculateCost(ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, current.x, ny, current.z,
                    cost, goal, favoring);
        }
    }

    /**
     * Expand a parkour (2-block gap jump) movement.
     */
    private void expandParkour(PathNode current, int dx, int dz,
                                Goal goal, Favoring favoring,
                                CalculationContext ctx) {
        int nx = current.x + dx;
        int nz = current.z + dz;

        double cost = calculateParkourCost(current.x, current.y, current.z,
                nx, current.y, nz, ctx);
        if (cost < Double.POSITIVE_INFINITY) {
            considerNode(current, nx, current.y, nz, cost, goal, favoring);
        }
    }

    // --- Cost calculation methods ---

    private double calculateTraverseCost(int sx, int sy, int sz,
                                          int dx, int dy, int dz,
                                          CalculationContext ctx) {
        MovementTraverse move = new MovementTraverse(sx, sy, sz, dx, dz);
        return move.calculateCost(ctx);
    }

    private double calculateAscendCost(int sx, int sy, int sz,
                                        int dx, int dy, int dz,
                                        CalculationContext ctx) {
        MovementAscend move = new MovementAscend(sx, sy, sz, dx, dz);
        return move.calculateCost(ctx);
    }

    private double calculateDescendCost(int sx, int sy, int sz,
                                         int dx, int dy, int dz,
                                         CalculationContext ctx) {
        MovementDescend move = new MovementDescend(sx, sy, sz, dx, dz);
        return move.calculateCost(ctx);
    }

    private double calculateFallCost(int sx, int sy, int sz,
                                      int dx, int dy, int dz,
                                      CalculationContext ctx, int fallDist) {
        MovementFall move = new MovementFall(sx, sy, sz, dx, dy, dz);
        return move.calculateCost(ctx);
    }

    private double calculateDiagonalCost(int sx, int sy, int sz,
                                          int dx, int dz,
                                          int offsetX, int offsetZ,
                                          CalculationContext ctx) {
        // Determine the two adjacent cardinal positions for corner-cutting check
        int adj1X, adj1Z, adj2X, adj2Z;
        if (offsetX > 0 && offsetZ < 0) {  // NE
            adj1X = sx + 1; adj1Z = sz;     // East
            adj2X = sx;     adj2Z = sz - 1;  // North
        } else if (offsetX < 0 && offsetZ < 0) {  // NW
            adj1X = sx - 1; adj1Z = sz;     // West
            adj2X = sx;     adj2Z = sz - 1;  // North
        } else if (offsetX > 0 && offsetZ > 0) {  // SE
            adj1X = sx + 1; adj1Z = sz;     // East
            adj2X = sx;     adj2Z = sz + 1;  // South
        } else {  // SW
            adj1X = sx - 1; adj1Z = sz;     // West
            adj2X = sx;     adj2Z = sz + 1;  // South
        }

        MovementDiagonal move = new MovementDiagonal(sx, sy, sz, dx, dz,
                adj1X, adj1Z, adj2X, adj2Z);
        return move.calculateCost(ctx);
    }

    private double calculatePillarCost(int sx, int sy, int sz,
                                        CalculationContext ctx) {
        MovementPillar move = new MovementPillar(sx, sy, sz);
        return move.calculateCost(ctx);
    }

    private double calculateParkourCost(int sx, int sy, int sz,
                                         int dx, int dy, int dz,
                                         CalculationContext ctx) {
        MovementParkour move = new MovementParkour(sx, sy, sz, dx, dy, dz);
        return move.calculateCost(ctx);
    }

    /**
     * Consider a neighbor node for the open set. If it's a new node or
     * we found a cheaper path to it, update its cost and add it to
     * the heap.
     */
    private void considerNode(PathNode parent, int x, int y, int z,
                               double moveCost, Goal goal, Favoring favoring) {
        if (moveCost >= Double.POSITIVE_INFINITY) return;
        if (moveCost <= 0) return;

        long key = posKey(x, y, z);
        PathNode existing = visitedNodes.get(key);

        double newCost = parent.cost + moveCost;

        if (existing != null) {
            if (existing.closed) return;  // Already processed
            if (newCost >= existing.cost) return;  // Not better

            // Found a better path — update
            existing.cost = newCost;
            existing.estimatedTotalCost = newCost + existing.heuristic;
            existing.parent = parent;

            // Update position in heap
            if (existing.heapIndex >= 0) {
                openHeap.decreaseKey(existing);
            } else {
                openHeap.insert(existing);
            }
        } else {
            // New node
            PathNode node = nodePool.acquire();
            node.reset(x, y, z);
            node.cost = newCost;
            node.heuristic = favoring.apply(goal.heuristic(x, y, z), x, y, z);
            node.estimatedTotalCost = newCost + node.heuristic;
            node.parent = parent;

            visitedNodes.put(key, node);
            openHeap.insert(node);
        }
    }

    /**
     * Build a path result by tracing back from the goal node.
     */
    private PathCalcResult buildPathResult(PathNode goalNode, Goal goal,
                                            int startX, int startY, int startZ) {
        List<Movement> movements = new ArrayList<>();

        // Trace back from goal to start
        List<PathNode> nodePath = new ArrayList<>();
        PathNode node = goalNode;
        while (node != null) {
            nodePath.add(node);
            node = node.parent;
        }
        Collections.reverse(nodePath);

        // Convert node path to movement path
        for (int i = 0; i < nodePath.size() - 1; i++) {
            PathNode from = nodePath.get(i);
            PathNode to = nodePath.get(i + 1);
            Movement movement = createMovement(from, to);
            if (movement != null) {
                movements.add(movement);
            }
        }

        double totalCost = goalNode.cost;
        PathBase path = new Path(movements, goal, totalCost, startX, startY, startZ);
        return PathCalcResult.success(path, nodesExplored);
    }

    /**
     * Build a cutoff result (partial path) when the search is interrupted.
     */
    private PathCalcResult buildCutoffResult(Goal goal, int startX, int startY, int startZ) {
        // Find the node closest to the goal
        PathNode bestNode = null;
        double bestHeuristic = Double.POSITIVE_INFINITY;

        for (PathNode node : visitedNodes.values()) {
            if (node.heuristic < bestHeuristic) {
                bestHeuristic = node.heuristic;
                bestNode = node;
            }
        }

        if (bestNode == null || bestNode.parent == null) {
            return PathCalcResult.failure(PathCalcResult.FailureReason.CANCELLED, nodesExplored);
        }

        // Build a partial path to the best node
        List<Movement> movements = new ArrayList<>();
        List<PathNode> nodePath = new ArrayList<>();
        PathNode n = bestNode;
        while (n != null) {
            nodePath.add(n);
            n = n.parent;
        }
        Collections.reverse(nodePath);

        for (int i = 0; i < nodePath.size() - 1; i++) {
            PathNode from = nodePath.get(i);
            PathNode to = nodePath.get(i + 1);
            Movement movement = createMovement(from, to);
            if (movement != null) {
                movements.add(movement);
            }
        }

        double totalCost = bestNode.cost;
        PathBase path = new CutoffPath(movements, goal, totalCost,
                movements.size(), startX, startY, startZ);
        return PathCalcResult.success(path, nodesExplored);
    }

    /**
     * Create the appropriate Movement object from two consecutive nodes.
     */
    private Movement createMovement(PathNode from, PathNode to) {
        int dx = to.x - from.x;
        int dy = to.y - from.y;
        int dz = to.z - from.z;

        // A vertical geometry can represent either a climb or a pillar. The
        // node map stores positions rather than edge types, so reconstruct the
        // physical climb first when the world still contains one.
        if (dx == 0 && dz == 0 && Math.abs(dy) == 1) {
            MovementClimb climb = new MovementClimb(
                    from.x, from.y, from.z, to.y);
            if (currentCtx != null
                    && climb.calculateCost(currentCtx) < Double.POSITIVE_INFINITY) {
                return climb;
            }
        }

        // Pillar
        if (dx == 0 && dz == 0 && dy == 1) {
            return new MovementPillar(from.x, from.y, from.z);
        }

        // Cardinal traverse
        if (dy == 0 && (Math.abs(dx) + Math.abs(dz)) == 1) {
            return new MovementTraverse(from.x, from.y, from.z, to.x, to.z);
        }

        // Ascend
        if (dy == 1 && (Math.abs(dx) + Math.abs(dz)) == 1) {
            return new MovementAscend(from.x, from.y, from.z, to.x, to.z);
        }

        // Descend
        if (dy == -1 && (Math.abs(dx) + Math.abs(dz)) == 1) {
            return new MovementDescend(from.x, from.y, from.z, to.x, to.z);
        }

        // Fall
        if (dy < -1 && (Math.abs(dx) + Math.abs(dz)) <= 1) {
            return new MovementFall(from.x, from.y, from.z, to.x, to.y, to.z);
        }

        // Diagonal
        if (dy == 0 && Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            int adj1X = from.x + dx, adj1Z = from.z;
            int adj2X = from.x, adj2Z = from.z + dz;
            return new MovementDiagonal(from.x, from.y, from.z, to.x, to.z,
                    adj1X, adj1Z, adj2X, adj2Z);
        }

        // Parkour
        if (dy == 0 && (Math.abs(dx) == 2 || Math.abs(dz) == 2)) {
            return new MovementParkour(from.x, from.y, from.z, to.x, to.y, to.z);
        }

        return null;  // Unknown movement type
    }

    /**
     * Check if the search should be cut off. Override for custom logic.
     */
    protected boolean shouldCutoff() {
        return false;
    }

    /**
     * Create a position key from block coordinates. Packs x, y, z into
     * a single long for efficient hashing.
     */
    private static long posKey(int x, int y, int z) {
        // Minecraft uses 26 signed bits for X/Z and 12 for Y. The previous
        // 24-bit masks aliased valid world coordinates 16,777,216 blocks
        // apart, violating the visited-map uniqueness contract.
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFFL);
    }

    // --- Inner classes ---

    /**
     * Simple object pool to reduce GC pressure during searches.
     */
    private static class ObjectPool<T> {
        private final java.util.function.Supplier<T> factory;
        private final List<T> pool = new ArrayList<>();
        private int index = 0;

        ObjectPool(java.util.function.Supplier<T> factory) {
            this.factory = factory;
        }

        T acquire() {
            if (index < pool.size()) {
                return pool.get(index++);
            }
            T obj = factory.get();
            pool.add(obj);
            index++;
            return obj;
        }

        void reset() {
            index = 0;
        }
    }

    /**
     * Long-to-Object open-addressing hash map. Avoids the overhead of
     * java.util.HashMap for the critical path node lookup.
     */
    private static class Long2ObjectOpenHashMap {
        private long[] keys;
        private PathNode[] values;
        private int[] states;  // 0=empty, 1=occupied, 2=deleted
        private int size;
        private int mask;
        private static final double LOAD_FACTOR = 0.75;

        Long2ObjectOpenHashMap(int initialCapacity) {
            int capacity = 1;
            while (capacity < initialCapacity) capacity <<= 1;
            this.keys = new long[capacity];
            this.values = new PathNode[capacity];
            this.states = new int[capacity];
            this.mask = capacity - 1;
            this.size = 0;
        }

        void put(long key, PathNode value) {
            if (size >= keys.length * LOAD_FACTOR) {
                resize();
            }
            int idx = findSlot(key);
            if (states[idx] != 1) {
                size++;
            }
            keys[idx] = key;
            values[idx] = value;
            states[idx] = 1;
        }

        PathNode get(long key) {
            int idx = hash(key);
            while (states[idx] != 0) {
                if (states[idx] == 1 && keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) & mask;
            }
            return null;
        }

        Collection<PathNode> values() {
            List<PathNode> result = new ArrayList<>(size);
            for (int i = 0; i < states.length; i++) {
                if (states[i] == 1) {
                    result.add(values[i]);
                }
            }
            return result;
        }

        void clear() {
            Arrays.fill(states, 0);
            size = 0;
        }

        private int hash(long key) {
            return (int) (key ^ (key >>> 16)) & mask;
        }

        private int findSlot(long key) {
            int idx = hash(key);
            int firstDeleted = -1;
            while (states[idx] != 0) {
                if (states[idx] == 1 && keys[idx] == key) return idx;
                if (states[idx] == 2 && firstDeleted == -1) firstDeleted = idx;
                idx = (idx + 1) & mask;
            }
            return firstDeleted != -1 ? firstDeleted : idx;
        }

        private void resize() {
            int newCapacity = keys.length << 1;
            long[] oldKeys = keys;
            PathNode[] oldValues = values;
            int[] oldStates = states;

            keys = new long[newCapacity];
            values = new PathNode[newCapacity];
            states = new int[newCapacity];
            mask = newCapacity - 1;
            size = 0;

            for (int i = 0; i < oldStates.length; i++) {
                if (oldStates[i] == 1) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /**
     * Binary min-heap for the A* open set. Ordered by
     * estimatedTotalCost (f-cost).
     */
    private static class BinaryHeap {
        private PathNode[] heap;
        private int size;

        BinaryHeap() {
            this(256);
        }

        BinaryHeap(int initialCapacity) {
            this.heap = new PathNode[initialCapacity];
            this.size = 0;
        }

        void insert(PathNode node) {
            if (size >= heap.length) {
                heap = Arrays.copyOf(heap, heap.length * 2);
            }
            heap[size] = node;
            node.heapIndex = size;
            siftUp(size);
            size++;
        }

        PathNode pop() {
            if (size == 0) throw new IllegalStateException("Heap is empty");
            PathNode result = heap[0];
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                heap[0].heapIndex = 0;
                siftDown(0);
            }
            heap[size] = null;
            result.heapIndex = -1;
            return result;
        }

        void decreaseKey(PathNode node) {
            siftUp(node.heapIndex);
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            Arrays.fill(heap, 0, size, null);
            size = 0;
        }

        private void siftUp(int idx) {
            while (idx > 0) {
                int parent = (idx - 1) >>> 1;
                if (heap[idx].estimatedTotalCost >= heap[parent].estimatedTotalCost) {
                    break;
                }
                swap(idx, parent);
                idx = parent;
            }
        }

        private void siftDown(int idx) {
            int half = size >>> 1;
            while (idx < half) {
                int child = (idx << 1) + 1;
                int right = child + 1;
                if (right < size && heap[right].estimatedTotalCost < heap[child].estimatedTotalCost) {
                    child = right;
                }
                if (heap[idx].estimatedTotalCost <= heap[child].estimatedTotalCost) {
                    break;
                }
                swap(idx, child);
                idx = child;
            }
        }

        private void swap(int a, int b) {
            PathNode temp = heap[a];
            heap[a] = heap[b];
            heap[b] = temp;
            heap[a].heapIndex = a;
            heap[b].heapIndex = b;
        }
    }
}
