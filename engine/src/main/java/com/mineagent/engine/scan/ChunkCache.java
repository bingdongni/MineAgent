package com.mineagent.engine.scan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Cached chunk access for scanning operations — wraps a ServerLevel to
 * cache chunk lookups during a single scan operation. This avoids
 * repeated chunk loading/unloading when scanning a large area.
 *
 * <p>Uses {@link Long2ObjectOpenHashMap} for O(1) chunk lookup by
 * packed chunk position key.
 *
 * <p><b>Lifecycle:</b> Create before a scan, use during the scan, call
 * {@link #invalidate()} after the scan to release cached chunks.
 *
 * <p>This class is NOT thread-safe — use from a single thread only
 * (the server tick thread).
 */
public final class ChunkCache {

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<LevelChunk> chunkCache;

    /**
     * Create a new chunk cache wrapping the given server level.
     *
     * @param level the server level to cache chunk access for
     */
    public ChunkCache(ServerLevel level) {
        this.level = level;
        this.chunkCache = new Long2ObjectOpenHashMap<>(64);
    }

    // ── Block State Lookup ────────────────────────────────────────

    /**
     * Get the block state at the given position, using the chunk cache
     * for efficient repeated lookups.
     *
     * <p>If the chunk at the position is not loaded, returns the
     * air block state as a safe default.
     *
     * @param pos the block position
     * @return the block state at the position, or air if the chunk is not loaded
     */
    public BlockState getBlockState(BlockPos pos) {
        try {
            LevelChunk chunk = getChunk(pos);
            if (chunk == null) {
                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            return chunk.getBlockState(pos);
        } catch (Exception e) {
            System.err.println("[MineAgent] ChunkCache.getBlockState error: " + e.getMessage());
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    /**
     * Get the block state at the given coordinates, using the chunk cache.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return the block state, or air if the chunk is not loaded
     */
    public BlockState getBlockState(int x, int y, int z) {
        return getBlockState(new BlockPos(x, y, z));
    }

    // ── Block Entity Lookup ───────────────────────────────────────

    /**
     * Get the block entity at the given position, using the chunk cache.
     *
     * @param pos the block position
     * @return the block entity, or null if none exists or the chunk is not loaded
     */
    public BlockEntity getBlockEntity(BlockPos pos) {
        try {
            LevelChunk chunk = getChunk(pos);
            if (chunk == null) return null;
            return chunk.getBlockEntity(pos);
        } catch (Exception e) {
            System.err.println("[MineAgent] ChunkCache.getBlockEntity error: " + e.getMessage());
            return null;
        }
    }

    // ── Cache Management ──────────────────────────────────────────

    /**
     * Clear the cache, releasing all cached chunk references.
     *
     * <p>MUST be called after a scan operation to prevent memory leaks.
     * The cache holds strong references to chunks, so failing to
     * invalidate will prevent chunk unloading.
     */
    public void invalidate() {
        chunkCache.clear();
    }

    /**
     * Get the number of chunks currently cached.
     *
     * @return cached chunk count
     */
    public int cachedChunkCount() {
        return chunkCache.size();
    }

    /**
     * Get the underlying server level.
     *
     * @return the server level
     */
    public ServerLevel level() {
        return level;
    }

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Get the chunk at the given block position, using the cache.
     *
     * <p>If the chunk is already cached, returns the cached instance.
     * Otherwise, loads the chunk from the server level and caches it.
     *
     * @param pos a block position within the target chunk
     * @return the chunk, or null if it is not loaded
     */
    private LevelChunk getChunk(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);

        LevelChunk cached = chunkCache.get(chunkKey);
        if (cached != null) return cached;

        // Check if the chunk is loaded before getting it
        if (!level.hasChunk(chunkX, chunkZ)) {
            return null;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        chunkCache.put(chunkKey, chunk);
        return chunk;
    }
}
