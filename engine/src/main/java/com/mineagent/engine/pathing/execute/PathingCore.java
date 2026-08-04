package com.mineagent.engine.pathing.execute;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.astar.*;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.goals.Goal;
import com.mineagent.engine.pathing.moves.CalculationContext;
import com.mineagent.engine.pathing.moves.ChunkLoadedTest;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.api.config.MineAgentConfig;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.pathing.util.BlockHelper;

/**
 * Core state machine for the pathing system. Manages the lifecycle:
 * SEARCH → EXECUTE → (SEGMENT) → SEARCH → ...
 *
 * <p>States:
 * <ul>
 *   <li>IDLE — no pathing task active</li>
 *   <li>SEARCH — calculating a path to the goal</li>
 *   <li>EXECUTE — following the computed path</li>
 *   <li>SEGMENT — path was cut short, re-pathing from current position</li>
 * </ul>
 *
 * <p>When a path fails during execution, the core automatically
 * re-paths from the current position.
 */
public class PathingCore {

    /** The current state of the pathing state machine. */
    public enum State {
        IDLE,
        SEARCH,
        EXECUTE,
        SEGMENT
    }

    /**
     * Maximum number of A* nodes to expand per server tick while
     * searching. Bounding this keeps the server tick responsive —
     * a single tick never spends more than ~1ms on pathfinding, so
     * the world keeps ticking at 20 TPS and the companion never
     * appears to "freeze" while thinking about a path.
     *
     * <p>At ~1µs/node (rough benchmark for this implementation),
     * 2000 nodes ≈ 2ms, leaving plenty of headroom in a 50ms tick.
     */
    private static final int NODES_PER_TICK = 2000;

    /**
     * Hard wall-clock budget (in nanoseconds) for a single search
     * slice. If we hit this before the node budget, we yield early.
     * This protects against pathological cost calculations that would
     * otherwise blow up a single tick.
     */
    private static final long SEARCH_TIME_BUDGET_NS = 3_000_000L; // 3ms

    private final AgentPlayer player;
    private final PathCaches caches;
    private final AStarPathFinder finder;
    private final MineAgentConfig.PathfindingConfig pathConfig;
    private final IntentContract.TerrainPolicy terrainPolicy;

    private State state = State.IDLE;
    private Goal currentGoal;
    private PathExecutor executor;
    private PathCalcResult lastResult;

    /**
     * Whether a sliced search has been initialized for the current
     * goal. Reset whenever a new goal is set or the search completes
     * / is cancelled. When false, {@link #tickSearch()} will call
     * {@link AStarPathFinder#initializeSearch} first; when true it
     * will call {@link AStarPathFinder#continueSearch}.
     */
    private boolean searchInitialized;

    /** Maximum number of re-path attempts before giving up. */
    private static final int MAX_REPATH_ATTEMPTS = 5;

    private int repathAttempts;
    private boolean bridgeDisabledForCurrentGoal;
    private boolean digDisabledForCurrentGoal;
    private String lastFailureDetail;

    public PathingCore(AgentPlayer player, PathCaches caches) {
        this(player, caches, MineAgentConfig.PathfindingConfig.DEFAULTS,
                IntentContract.TerrainPolicy.CONSERVATIVE);
    }

    public PathingCore(AgentPlayer player, PathCaches caches,
                       MineAgentConfig.PathfindingConfig pathConfig) {
        this(player, caches, pathConfig, IntentContract.TerrainPolicy.CONSERVATIVE);
    }

    public PathingCore(AgentPlayer player, PathCaches caches,
                       MineAgentConfig.PathfindingConfig pathConfig,
                       IntentContract.TerrainPolicy terrainPolicy) {
        this.player = player;
        this.caches = caches;
        this.pathConfig = pathConfig != null
                ? pathConfig : MineAgentConfig.PathfindingConfig.DEFAULTS;
        this.terrainPolicy = terrainPolicy == null
                ? IntentContract.TerrainPolicy.CONSERVATIVE : terrainPolicy;
        // Configuration must reach the actual search implementation; keeping
        // it only in PlayerNav made max nodes and parkour toggles cosmetic.
        this.finder = new AStarPathFinder(this.pathConfig.maxSearchNodes(),
                this.pathConfig.allowParkour());
    }

    /**
     * Set a new navigation goal and start searching.
     *
     * @param goal the goal to navigate to
     */
    public void setGoal(Goal goal) {
        if (executor != null) executor.stop();
        if (state == State.SEARCH) finder.cancel();
        this.currentGoal = goal;
        this.repathAttempts = 0;
        this.state = State.SEARCH;
        this.executor = null;
        this.lastResult = null;
        this.searchInitialized = false;
        this.bridgeDisabledForCurrentGoal = false;
        this.digDisabledForCurrentGoal = false;
        this.lastFailureDetail = null;
    }

    /**
     * Cancel the current pathing task.
     */
    public void cancel() {
        if (state == State.SEARCH) {
            finder.cancel();
        }
        if (executor != null) {
            executor.stop();
        }
        state = State.IDLE;
        currentGoal = null;
        executor = null;
        searchInitialized = false;
        bridgeDisabledForCurrentGoal = false;
        digDisabledForCurrentGoal = false;
        lastFailureDetail = null;
    }

    /**
     * Tick the state machine. Called every server tick.
     */
    public void tick() {
        switch (state) {
            case IDLE -> {}
            case SEARCH -> tickSearch();
            case EXECUTE -> tickExecute();
            case SEGMENT -> tickSegment();
        }
    }

    private void tickSearch() {
        if (currentGoal == null) {
            state = State.IDLE;
            return;
        }

        // On the first tick of a SEARCH, initialize the sliced search.
        if (!searchInitialized) {
            var pos = TaskContext.serverPlayer(player).blockPosition();
            ChunkLoadedTest chunkTest = caches.loadedChunks()::isChunkLoaded;
            CalculationContext ctx = new CalculationContext(
                    caches.level(), caches.worldView(), chunkTest,
                    pathConfig.allowDigThrough()
                            && terrainPolicy.allowBreakingObstacles()
                            && !digDisabledForCurrentGoal,
                    pathConfig.allowBridge() && terrainPolicy.allowBridge()
                            && !bridgeDisabledForCurrentGoal
                            && com.mineagent.engine.act.Placement.hasSupportBlock(
                                    TaskContext.serverPlayer(player)),
                    pathConfig.allowBridge() && terrainPolicy.allowPillar()
                            && !bridgeDisabledForCurrentGoal
                            && com.mineagent.engine.act.Placement.hasSupportBlock(
                                    TaskContext.serverPlayer(player)),
                    pathConfig.allowParkour() && terrainPolicy.allowParkour(),
                    pos.getY(), terrainPolicy.maxUpwardDeviation());

            boolean needsContinue = finder.initializeSearch(
                    pos.getX(), pos.getY(), pos.getZ(),
                    currentGoal, Favoring.NONE, ctx);
            searchInitialized = true;

            // initializeSearch can complete immediately (e.g. already
            // at goal) — in that case grab the result now.
            if (!needsContinue) {
                finishSearch();
                return;
            }
        }

        // Resume the sliced search with this tick's node + time budget.
        long startTime = System.nanoTime();
        int remaining = NODES_PER_TICK;
        boolean stillActive = true;
        while (stillActive && remaining > 0) {
            // Sub-batch so we can also check the time budget.
            int batch = Math.min(remaining, 512);
            long elapsed = System.nanoTime() - startTime;
            if (elapsed >= SEARCH_TIME_BUDGET_NS) {
                // Out of time budget — yield to the tick.
                break;
            }
            stillActive = finder.continueSearch(batch);
            remaining -= batch;
        }

        if (!stillActive) {
            // Search finished this tick — collect the result.
            finishSearch();
        }
        // else: search still in progress; we'll resume next tick. The
        // companion stays in SEARCH state but does NOT freeze — the
        // tick returns quickly and the world keeps simulating.
    }

    /**
     * Collect the result of a completed sliced search and transition
     * to the appropriate next state.
     */
    private void finishSearch() {
        lastResult = finder.searchResult();
        searchInitialized = false;

        if (lastResult != null && lastResult.foundPath()) {
            PathBase path = lastResult.path();
            int requiredSupports = requiredSupportBlocks(path);
            int availableSupports = Math.min(terrainPolicy.maxPlacedBlocks(),
                    com.mineagent.engine.act.Placement.supportBlockCount(
                            TaskContext.serverPlayer(player)));
            if (!bridgeDisabledForCurrentGoal
                    && requiredSupports > availableSupports) {
                // The A* node key does not include remaining inventory. Reject
                // a bridge that cannot be completed before stepping over void
                // or that exceeds the task's terrain-change budget.
                bridgeDisabledForCurrentGoal = true;
                lastFailureDetail = requiredSupports > terrainPolicy.maxPlacedBlocks()
                        ? "PATH_EXCEEDS_PLACEMENT_BUDGET" : "INSUFFICIENT_SUPPORT_BLOCKS";
                lastResult = null;
                state = State.SEARCH;
                searchInitialized = false;
                return;
            }
            int requiredBreaks = requiredObstacleBreaks(path);
            if (!digDisabledForCurrentGoal
                    && requiredBreaks > terrainPolicy.maxBrokenObstacles()) {
                digDisabledForCurrentGoal = true;
                lastFailureDetail = "PATH_EXCEEDS_BREAK_BUDGET";
                lastResult = null;
                state = State.SEARCH;
                searchInitialized = false;
                return;
            }
            // Path movements are created independently of task semantics.
            // Attach the cleanup contract before any movement can place a
            // support, keeping BuildTask output distinct from navigation aid.
            for (var movement : path.movements()) {
                movement.configureSupportCleanup(terrainPolicy.cleanupMode());
            }
            executor = new PathExecutor(player, path);
            state = State.EXECUTE;
        } else {
            // No path found — give up
            state = State.IDLE;
        }
    }

    private void tickExecute() {
        if (executor == null) {
            state = State.IDLE;
            return;
        }

        executor.tick();

        if (executor.isFinished()) {
            // Path completed — check if we reached the goal
            if (currentGoal != null) {
                var pos = TaskContext.serverPlayer(player).blockPosition();
                if (currentGoal.isInGoal(pos.getX(), pos.getY(), pos.getZ())) {
                    state = State.IDLE;
                    return;
                }
                // If path ended but goal not reached, try re-pathing
                if (lastResult.path() instanceof CutoffPath) {
                    state = State.SEGMENT;
                    return;
                }
            }
            state = State.IDLE;
        } else if (executor.isFailed()) {
            // Movement failed — try re-pathing
            repathAttempts++;
            if (repathAttempts < MAX_REPATH_ATTEMPTS) {
                state = State.SEARCH;
                executor = null;
                searchInitialized = false;
            } else {
                state = State.IDLE;
            }
        }
    }

    private void tickSegment() {
        // Re-path from current position to the same goal
        repathAttempts++;
        if (repathAttempts < MAX_REPATH_ATTEMPTS) {
            state = State.SEARCH;
            executor = null;
            searchInitialized = false;
        } else {
            state = State.IDLE;
        }
    }

    private int requiredSupportBlocks(PathBase path) {
        java.util.Set<net.minecraft.core.BlockPos> missing = new java.util.HashSet<>();
        var level = TaskContext.serverPlayer(player).level();
        for (var movement : path.movements()) {
            var support = movement.requiredSupportPosition();
            if (support != null
                    && !com.mineagent.engine.pathing.util.BlockHelper.isClimbable(
                    level.getBlockState(new net.minecraft.core.BlockPos(
                            movement.dstX(), movement.dstY(), movement.dstZ())))
                    && !com.mineagent.engine.pathing.util.BlockHelper.canStandOn(
                    level.getBlockState(support))) {
                missing.add(support.immutable());
            }
        }
        return missing.size();
    }

    private int requiredObstacleBreaks(PathBase path) {
        java.util.Set<net.minecraft.core.BlockPos> obstacles = new java.util.HashSet<>();
        var level = TaskContext.serverPlayer(player).level();
        for (var movement : path.movements()) {
            addBreakObstacle(level, obstacles, new net.minecraft.core.BlockPos(
                    movement.dstX(), movement.dstY(), movement.dstZ()));
            addBreakObstacle(level, obstacles, new net.minecraft.core.BlockPos(
                    movement.dstX(), movement.dstY() + 1, movement.dstZ()));
            if (movement.dstY() > movement.srcY()) {
                addBreakObstacle(level, obstacles, new net.minecraft.core.BlockPos(
                        movement.srcX(), movement.srcY() + 2, movement.srcZ()));
            }
        }
        return obstacles.size();
    }

    private static void addBreakObstacle(net.minecraft.world.level.Level level,
                                         java.util.Set<net.minecraft.core.BlockPos> result,
                                         net.minecraft.core.BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!BlockHelper.isPassable(state) && !BlockHelper.canOpenByHand(state)) {
            result.add(pos.immutable());
        }
    }

    /** Get the current state. */
    public State state() { return state; }

    /** Whether the pathing system is currently active. */
    public boolean isActive() { return state != State.IDLE; }

    /** Get the current goal. */
    public Goal currentGoal() { return currentGoal; }

    /** Get the current path executor (if any). */
    public PathExecutor executor() { return executor; }

    /** Get the last path calculation result. */
    public PathCalcResult lastResult() { return lastResult; }

    /** More specific policy/resource failure when available. */
    public String lastFailureDetail() { return lastFailureDetail; }

    /** Get the caches. */
    public PathCaches caches() { return caches; }
}
