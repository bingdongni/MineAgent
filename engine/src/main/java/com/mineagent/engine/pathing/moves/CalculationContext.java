package com.mineagent.engine.pathing.moves;

import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.pathing.cache.LoadedOnlyView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Cached world state for path calculation. Provides block state lookups
 * with caching to avoid repeated world access during A* search.
 *
 * <p>Uses a {@link LoadedOnlyView} to ensure we only read from loaded
 * chunks and never trigger chunk generation.
 */
public class CalculationContext {

    private final LoadedOnlyView worldView;
    private final ChunkLoadedTest chunkLoadedTest;
    private final Level level;
    private final boolean allowDigThrough;
    private final boolean allowBridge;
    private final boolean allowPillar;
    private final boolean allowParkour;
    private final int startY;
    private final int maxUpwardDeviation;
    private final Set<ExcludedEdge> excludedEdges;

    /**
     * A directed movement edge that failed during execution for the current
     * goal. Keeping this in the calculation context makes a replan materially
     * different: A* cannot immediately select the same proven-bad action.
     */
    public record ExcludedEdge(int sourceX, int sourceY, int sourceZ,
                               int destinationX, int destinationY, int destinationZ) {}

    public CalculationContext(Level level, LoadedOnlyView worldView, ChunkLoadedTest chunkLoadedTest) {
        this(level, worldView, chunkLoadedTest, false, false, false, false,
                0, Integer.MAX_VALUE, Set.of());
    }

    public CalculationContext(Level level, LoadedOnlyView worldView,
                              ChunkLoadedTest chunkLoadedTest,
                              boolean allowDigThrough, boolean allowBridge) {
        this(level, worldView, chunkLoadedTest, allowDigThrough, allowBridge,
                false, false, 0, Integer.MAX_VALUE, Set.of());
    }

    public CalculationContext(Level level, LoadedOnlyView worldView,
                              ChunkLoadedTest chunkLoadedTest,
                              boolean allowDigThrough, boolean allowBridge,
                              boolean allowPillar, boolean allowParkour,
                              int startY, int maxUpwardDeviation) {
        this(level, worldView, chunkLoadedTest, allowDigThrough, allowBridge,
                allowPillar, allowParkour, startY, maxUpwardDeviation, Set.of());
    }

    public CalculationContext(Level level, LoadedOnlyView worldView,
                              ChunkLoadedTest chunkLoadedTest,
                              boolean allowDigThrough, boolean allowBridge,
                              boolean allowPillar, boolean allowParkour,
                              int startY, int maxUpwardDeviation,
                              Set<ExcludedEdge> excludedEdges) {
        this.level = level;
        this.worldView = worldView;
        this.chunkLoadedTest = chunkLoadedTest;
        this.allowDigThrough = allowDigThrough;
        this.allowBridge = allowBridge;
        this.allowPillar = allowPillar;
        this.allowParkour = allowParkour;
        this.startY = startY;
        this.maxUpwardDeviation = Math.max(0, maxUpwardDeviation);
        this.excludedEdges = excludedEdges == null || excludedEdges.isEmpty()
                ? Set.of() : Set.copyOf(excludedEdges);
    }

    /**
     * Get the block state at the given position. Returns null if the
     * chunk is not loaded.
     */
    public BlockState getBlockState(int x, int y, int z) {
        if (!chunkLoadedTest.isChunkLoaded(x, z)) {
            return null;
        }
        return worldView.getBlockState(new BlockPos(x, y, z));
    }

    /** Check if a chunk is loaded. */
    public boolean isChunkLoaded(int x, int z) {
        return chunkLoadedTest.isChunkLoaded(x, z);
    }

    /** Get the underlying level (for advanced queries). */
    public Level level() {
        return level;
    }

    /** Get the world view. */
    public LoadedOnlyView worldView() {
        return worldView;
    }

    public boolean allowDigThrough() {
        return allowDigThrough;
    }

    public boolean allowBridge() {
        return allowBridge;
    }

    public boolean allowPillar() {
        return allowPillar;
    }

    public boolean allowParkour() {
        return allowParkour;
    }

    /**
     * A task may permit ordinary terrain changes while still forbidding a
     * route that climbs far above its semantic target (for example, chopping
     * a ground-level tree). This is a hard search bound, not a utility weight.
     */
    public boolean isWithinVerticalPolicy(int y) {
        return y <= (long) startY + maxUpwardDeviation;
    }

    /** Return true when execution evidence says this exact edge is unusable. */
    public boolean isEdgeExcluded(int sourceX, int sourceY, int sourceZ,
                                  int destinationX, int destinationY, int destinationZ) {
        return excludedEdges.contains(new ExcludedEdge(sourceX, sourceY, sourceZ,
                destinationX, destinationY, destinationZ));
    }
}
