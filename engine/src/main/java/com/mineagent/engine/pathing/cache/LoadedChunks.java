package com.mineagent.engine.pathing.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Checks whether chunks are loaded without triggering generation.
 * Uses the server chunk manager to query loaded chunk status only.
 */
public class LoadedChunks {

    private final ServerLevel level;

    public LoadedChunks(ServerLevel level) {
        this.level = level;
    }

    /**
     * Check if the chunk containing the given block position is loaded.
     * Does NOT trigger chunk generation.
     *
     * @param x block X
     * @param z block Z
     * @return true if the chunk is fully loaded
     */
    public boolean isChunkLoaded(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk != null;
    }

    /**
     * Check if the chunk at the given chunk coordinates is loaded.
     */
    public boolean isChunkLoadedAt(int chunkX, int chunkZ) {
        ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk != null;
    }

    /**
     * Get the server level.
     */
    public ServerLevel level() {
        return level;
    }
}
