package com.mineagent.engine.pathing.astar;

/**
 * Result wrapper for path calculation. Either contains a successfully
 * computed path, or a failure reason explaining why the path could
 * not be found.
 */
public class PathCalcResult {

    /** Reason the path calculation failed. */
    public enum FailureReason {
        /** No path exists within the node limit. */
        NO_PATH,
        /** The goal is impossible to reach (e.g., inside bedrock). */
        GOAL_IMPOSSIBLE,
        /** The search was cancelled (cutoff). */
        CANCELLED,
        /** The start position is invalid (in a wall, etc). */
        START_INVALID,
        /** Not all chunks along the path are loaded. */
        CHUNKS_NOT_LOADED
    }

    private final PathBase path;
    private final FailureReason failureReason;
    private final int nodesExplored;

    private PathCalcResult(PathBase path, FailureReason failureReason, int nodesExplored) {
        this.path = path;
        this.failureReason = failureReason;
        this.nodesExplored = nodesExplored;
    }

    /** Create a successful result. */
    public static PathCalcResult success(PathBase path, int nodesExplored) {
        return new PathCalcResult(path, null, nodesExplored);
    }

    /** Create a failure result. */
    public static PathCalcResult failure(FailureReason reason, int nodesExplored) {
        return new PathCalcResult(null, reason, nodesExplored);
    }

    /** Whether the path calculation succeeded. */
    public boolean foundPath() {
        return path != null;
    }

    /** Get the computed path (only valid if foundPath() is true). */
    public PathBase path() {
        return path;
    }

    /** Get the failure reason (only valid if foundPath() is false). */
    public FailureReason failureReason() {
        return failureReason;
    }

    /** Get the number of nodes explored during the search. */
    public int nodesExplored() {
        return nodesExplored;
    }

    @Override
    public String toString() {
        if (foundPath()) {
            return "PathCalcResult[SUCCESS, " + path + ", nodes=" + nodesExplored + "]";
        }
        return "PathCalcResult[FAILURE: " + failureReason + ", nodes=" + nodesExplored + "]";
    }
}
