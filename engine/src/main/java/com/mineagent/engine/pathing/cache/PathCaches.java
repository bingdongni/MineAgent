package com.mineagent.engine.pathing.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Cache manager for navigation views. Creates and holds references
 * to the LoadedOnlyView and LoadedChunks instances used by the
 * pathfinding system.
 *
 * <p>Call {@link #invalidate()} when the world state changes
 * significantly (e.g., chunk load/unload events) to force
 * recalculation of cached data.
 */
public class PathCaches {

    private final LoadedOnlyView worldView;
    private final LoadedChunks loadedChunks;
    private volatile long lastInvalidationTime;

    public PathCaches(ServerLevel level) {
        this.worldView = new LoadedOnlyView(level);
        this.loadedChunks = new LoadedChunks(level);
        this.lastInvalidationTime = System.currentTimeMillis();
    }

    /** Get the read-only world view for path calculation. */
    public LoadedOnlyView worldView() {
        return worldView;
    }

    /** Get the loaded chunks checker. */
    public LoadedChunks loadedChunks() {
        return loadedChunks;
    }

    /** Get the server level. */
    public ServerLevel level() {
        return loadedChunks.level();
    }

    /**
     * Invalidate caches. Called when significant world changes occur
     * (chunks loading/unloading, block changes in the path area).
     */
    public void invalidate() {
        this.lastInvalidationTime = System.currentTimeMillis();
    }

    /** Get the timestamp of the last invalidation. */
    public long lastInvalidationTime() {
        return lastInvalidationTime;
    }

    /**
     * Check if the caches have been invalidated since the given timestamp.
     */
    public boolean isStaleSince(long timestamp) {
        return lastInvalidationTime > timestamp;
    }
}
