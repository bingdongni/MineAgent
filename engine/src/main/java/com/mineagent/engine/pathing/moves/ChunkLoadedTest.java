package com.mineagent.engine.pathing.moves;

/**
 * Interface for checking whether a chunk at given coordinates is loaded.
 * Used during path calculation to avoid generating new chunks.
 */
@FunctionalInterface
public interface ChunkLoadedTest {

    /**
     * Check if the chunk containing the given block coordinates is loaded.
     *
     * @param x block X
     * @param z block Z
     * @return true if the chunk is loaded in the world
     */
    boolean isChunkLoaded(int x, int z);
}
