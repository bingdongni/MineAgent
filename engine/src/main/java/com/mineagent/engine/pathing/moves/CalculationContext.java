package com.mineagent.engine.pathing.moves;

import com.mineagent.engine.pathing.cache.LoadedOnlyView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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

    public CalculationContext(Level level, LoadedOnlyView worldView, ChunkLoadedTest chunkLoadedTest) {
        this(level, worldView, chunkLoadedTest, false, false);
    }

    public CalculationContext(Level level, LoadedOnlyView worldView,
                              ChunkLoadedTest chunkLoadedTest,
                              boolean allowDigThrough, boolean allowBridge) {
        this.level = level;
        this.worldView = worldView;
        this.chunkLoadedTest = chunkLoadedTest;
        this.allowDigThrough = allowDigThrough;
        this.allowBridge = allowBridge;
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
}
