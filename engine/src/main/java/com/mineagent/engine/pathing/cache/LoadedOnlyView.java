package com.mineagent.engine.pathing.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Read-only world view that only reads from already-loaded chunks.
 * Never triggers chunk generation - returns a default state for
 * unloaded chunks.
 *
 * <p>This is the primary world access interface for path calculation.
 * It prevents the pathfinder from accidentally loading or generating
 * chunks in unloaded areas.
 */
public class LoadedOnlyView {

    private final Level level;

    public LoadedOnlyView(Level level) {
        this.level = level;
    }

    /**
     * Get the block state at the given position, but only if the
     * containing chunk is already loaded. Returns null for unloaded chunks.
     *
     * @param pos the block position
     * @return the block state, or null if the chunk is not loaded
     */
    public BlockState getBlockState(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockState(pos);
    }

    /**
     * Get the block state at the given coordinates. Returns null
     * if the chunk is not loaded.
     */
    public BlockState getBlockState(int x, int y, int z) {
        return getBlockState(new BlockPos(x, y, z));
    }

    /**
     * Check if the chunk containing the given position is loaded.
     */
    public boolean isLoaded(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    /**
     * Get the underlying level reference.
     */
    public Level level() {
        return level;
    }
}
