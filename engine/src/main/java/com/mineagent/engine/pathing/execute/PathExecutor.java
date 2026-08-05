package com.mineagent.engine.pathing.execute;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.astar.PathBase;
import com.mineagent.engine.pathing.moves.Input;
import com.mineagent.engine.pathing.moves.Movement;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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
    private int ticksWithoutProgress;
    private double bestDistanceToDestination;
    private double lastReportedDistanceToDestination;
    private long progressVersion;
    private boolean finished;
    private boolean failed;
    private Failure failure;

    /** Absolute bound for ordinary movement, even if collision jitter occurs. */
    private static final int MAX_TICKS_PER_MOVEMENT = 200;

    /** A walking edge with no measurable approach for 2.5 seconds is stuck. */
    private static final int MAX_STAGNANT_TICKS = 50;
    private static final double MIN_PROGRESS_DISTANCE = 0.04;
    private static final double REPORT_PROGRESS_DISTANCE = 0.25;

    public enum FailureReason {
        STALLED,
        TIMEOUT,
        NULL_INPUT
    }

    /** Immutable executor evidence consumed by replanning and task status. */
    public record Failure(FailureReason reason, String movementType,
                          int movementIndex, BlockPos source, BlockPos destination,
                          BlockPos playerPosition, int ticksOnMovement,
                          int ticksWithoutProgress) {
        public String describe() {
            return reason.name() + " movement=" + movementType
                    + " index=" + movementIndex
                    + " edge=" + source.toShortString() + "->" + destination.toShortString()
                    + " player=" + playerPosition.toShortString()
                    + " movement_ticks=" + ticksOnMovement
                    + " stagnant_ticks=" + ticksWithoutProgress;
        }
    }

    public PathExecutor(AgentPlayer player, PathBase path) {
        this.path = path;
        this.harness = new ExecHarness(player);
        this.currentMovementIndex = 0;
        this.ticksOnCurrentMovement = 0;
        this.movementProgressVersion = 0;
        this.ticksWithoutProgress = 0;
        this.progressVersion = 0L;
        this.finished = false;
        this.failed = false;
        this.failure = null;
        if (!path.isEmpty()) resetMovementProgress(path.get(0));
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
            ticksWithoutProgress = 0;
            progressVersion++;

            // Check if we've completed all movements
            if (currentMovementIndex >= path.length()) {
                finished = true;
                harness.clearInputs();
                return;
            }

            current = path.get(currentMovementIndex);
            movementProgressVersion = current.progressVersion();
            resetMovementProgress(current);
        }

        // Breaking two occupancy cells or placing bridge support can take
        // several sub-actions. Renew the per-movement timer whenever the
        // movement reports one of those durable world changes.
        if (current.progressVersion() != movementProgressVersion) {
            movementProgressVersion = current.progressVersion();
            ticksOnCurrentMovement = 0;
            ticksWithoutProgress = 0;
            progressVersion++;
        }

        updatePhysicalProgress(current);

        // Breaking an occupancy block is intentionally stationary and has a
        // hardness-derived timeout. All other actions must make measurable
        // progress toward their destination or fail quickly and replan.
        if (!current.hasActiveWorldAction()
                && ticksWithoutProgress > MAX_STAGNANT_TICKS) {
            fail(current, FailureReason.STALLED);
            return;
        }

        // Timeout check
        ticksOnCurrentMovement++;
        int timeout = Math.max(MAX_TICKS_PER_MOVEMENT,
                current.executionTimeoutTicks());
        if (ticksOnCurrentMovement > timeout) {
            fail(current, FailureReason.TIMEOUT);
            return;
        }

        // Get and apply tick inputs
        Input input = current.getTickInput(harness.player());
        if (input == null) {
            // A null input violates the movement contract. Use the normal
            // failure cleanup so held movement/mining state cannot leak.
            fail(current, FailureReason.NULL_INPUT);
            return;
        }
        harness.applyInput(input);
    }

    private void updatePhysicalProgress(Movement movement) {
        Vec3 position = TaskContext.serverPlayer(harness.player()).position();
        double distance = distanceToDestination(position, movement);
        if (distance + MIN_PROGRESS_DISTANCE < bestDistanceToDestination) {
            bestDistanceToDestination = distance;
            ticksWithoutProgress = 0;
            if (distance + REPORT_PROGRESS_DISTANCE < lastReportedDistanceToDestination) {
                lastReportedDistanceToDestination = distance;
                progressVersion++;
            }
        } else if (!movement.hasActiveWorldAction()) {
            ticksWithoutProgress++;
        }
    }

    private void resetMovementProgress(Movement movement) {
        Vec3 position = TaskContext.serverPlayer(harness.player()).position();
        bestDistanceToDestination = distanceToDestination(position, movement);
        lastReportedDistanceToDestination = bestDistanceToDestination;
    }

    private static double distanceToDestination(Vec3 position, Movement movement) {
        double dx = movement.dstX() + 0.5 - position.x;
        double dy = movement.dstY() - position.y;
        double dz = movement.dstZ() + 0.5 - position.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void fail(Movement movement, FailureReason reason) {
        failed = true;
        BlockPos playerPosition = TaskContext.serverPlayer(harness.player()).blockPosition();
        failure = new Failure(reason, movement.getClass().getSimpleName(),
                currentMovementIndex,
                new BlockPos(movement.srcX(), movement.srcY(), movement.srcZ()),
                new BlockPos(movement.dstX(), movement.dstY(), movement.dstZ()),
                playerPosition.immutable(), ticksOnCurrentMovement, ticksWithoutProgress);
        movement.onFailure(harness.player());
        harness.clearInputs();
    }

    /** Whether the path execution is finished (all movements completed). */
    public boolean isFinished() {
        return finished;
    }

    /** Whether the path execution failed. */
    public boolean isFailed() {
        return failed;
    }

    /** Structured reason for a failed edge, or null while execution is healthy. */
    public Failure failure() { return failure; }

    /** Monotonic executor progress used by truthful task snapshots. */
    public long progressVersion() { return progressVersion; }

    public int ticksOnCurrentMovement() { return ticksOnCurrentMovement; }

    public int ticksWithoutProgress() { return ticksWithoutProgress; }

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
