package com.mineagent.engine.pathing.execute;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.astar.PathBase;
import com.mineagent.engine.pathing.moves.Input;
import com.mineagent.engine.pathing.moves.Movement;

/**
 * Executes a path movement-by-movement. Steps through each movement
 * in sequence, providing tick inputs until each movement completes.
 *
 * <p>The executor handles:
 * <ul>
 *   <li>Movement sequencing - advancing to the next movement on completion</li>
 *   <li>Failure detection - detecting when a movement goes wrong</li>
 *   <li>Timeout - giving up on movements that take too long</li>
 * </ul>
 */
public class PathExecutor {

    private final PathBase path;
    private final ExecHarness harness;
    private int currentMovementIndex;
    private int ticksOnCurrentMovement;
    private int movementProgressVersion;
    private boolean finished;
    private boolean failed;

    /** Maximum ticks to spend on a single movement before considering it failed. */
    private static final int MAX_TICKS_PER_MOVEMENT = 200;

    public PathExecutor(AgentPlayer player, PathBase path) {
        this.path = path;
        this.harness = new ExecHarness(player);
        this.currentMovementIndex = 0;
        this.ticksOnCurrentMovement = 0;
        this.movementProgressVersion = 0;
        this.finished = false;
        this.failed = false;
    }

    /**
     * Tick the executor - apply inputs for the current movement.
     */
    public void tick() {
        if (finished || failed) return;
        if (path.isEmpty()) {
            finished = true;
            return;
        }

        Movement current = path.get(currentMovementIndex);

        // Check if current movement is finished
        if (current.isFinished(harness.player())) {
            currentMovementIndex++;
            ticksOnCurrentMovement = 0;

            // Check if we've completed all movements
            if (currentMovementIndex >= path.length()) {
                finished = true;
                harness.clearInputs();
                return;
            }

            current = path.get(currentMovementIndex);
            movementProgressVersion = current.progressVersion();
        }

        // Breaking two occupancy cells or placing bridge support can take
        // several sub-actions. Renew the per-movement timer whenever the
        // movement reports one of those durable world changes.
        if (current.progressVersion() != movementProgressVersion) {
            movementProgressVersion = current.progressVersion();
            ticksOnCurrentMovement = 0;
        }

        // Timeout check
        ticksOnCurrentMovement++;
        int timeout = Math.max(MAX_TICKS_PER_MOVEMENT,
                current.executionTimeoutTicks());
        if (ticksOnCurrentMovement > timeout) {
            failed = true;
            current.onFailure(harness.player());
            harness.clearInputs();
            return;
        }

        // Get and apply tick inputs
        Input input = current.getTickInput(harness.player());
        if (input == null) {
            // A null input violates the movement contract. Use the normal
            // failure cleanup so held movement/mining state cannot leak.
            failed = true;
            current.onFailure(harness.player());
            harness.clearInputs();
            return;
        }
        harness.applyInput(input);
    }

    /** Whether the path execution is finished (all movements completed). */
    public boolean isFinished() {
        return finished;
    }

    /** Whether the path execution failed. */
    public boolean isFailed() {
        return failed;
    }

    /** Get the current movement index. */
    public int currentMovementIndex() {
        return currentMovementIndex;
    }

    /** Get the path being executed. */
    public PathBase path() {
        return path;
    }

    /** Get the exec harness. */
    public ExecHarness harness() {
        return harness;
    }

    /**
     * Force-stop the executor.
     */
    public void stop() {
        if (!finished && !failed && !path.isEmpty()
                && currentMovementIndex < path.length()) {
            path.get(currentMovementIndex).onFailure(harness.player());
        }
        finished = true;
        harness.clearInputs();
    }
}
