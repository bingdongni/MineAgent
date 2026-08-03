package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.movement.MoveToTool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Executes a movement task - navigates the companion to a target position
 * using A* pathfinding. Supports four goal modes:
 * <ul>
 *   <li>xz - walk to horizontal coordinates at current Y</li>
 *   <li>xzy - walk to exact block position</li>
 *   <li>y - climb/descend to altitude</li>
 *   <li>block - walk adjacent to a block</li>
 * </ul>
 *
 * <p><b>Progress Lease</b> (borrowed from numen):
 * Uses {@link TaskLease} instead of a single hard deadline. The lease
 * renews when the companion makes progress (moves &gt; 1 block), so
 * healthy long journeys don't time out. The lease stops renewing after
 * a grace period without progress, catching genuinely stuck tasks.
 */
public class MoveToTask extends CompanionTask<MoveToTool.MoveToTaskRecord> {

    private PlayerNav nav;
    private volatile boolean goalReached;
    private volatile boolean navFailed;
    private String failReason;

    /** Progress lease — replaces the single hard deadline. */
    private final TaskLease lease = new TaskLease();

    /** Last recorded position — used to detect progress. */
    private Vec3 lastProgressPos = null;

    /** Near-retry: if navigation fails, retry once with a relaxed goal. */
    private boolean hasRetried = false;
    private static final int NEAR_RETRY_RADIUS = 3;

    public MoveToTask(AgentPlayer player, MoveToTool.MoveToTaskRecord record) {
        super(player, record);
    }

    @Override
    public void onStart() {
        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);

        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                goalReached = true;
            }

            @Override
            public void onNavigationFailed(String reason) {
                navFailed = true;
                failReason = reason;
            }
        });

        // Set goal based on mode
        // Use safe defaults for null y/z (shouldn't happen after MoveToTool
        // validation, but defensive against LLM-provided bad data)
        ServerPlayer sp0 = TaskContext.serverPlayer(player);
        int safeY = record.y != null ? record.y : sp0.blockPosition().getY();
        int safeZ = record.z != null ? record.z : sp0.blockPosition().getZ();

        switch (record.goalMode) {
            case "xz" -> nav.navigateToXZ(record.x, safeZ);
            case "xzy" -> nav.navigateTo(record.x, safeY, safeZ);
            case "y" -> nav.navigateToYLevel(safeY);
            case "block" -> nav.navigateToBlock(record.x, safeY, safeZ);
            default -> {
                navFailed = true;
                failReason = "Invalid goal_mode: " + record.goalMode;
            }
        }

        // Start the progress lease
        ServerPlayer sp = TaskContext.serverPlayer(player);
        long gameTime = sp.level().getGameTime();
        lease.start(gameTime);
        lastProgressPos = sp.position();
    }

    @Override
    public TaskState onTick() {
        if (navFailed) {
            // Near-retry: if we haven't retried yet, retry with a relaxed goal
            if (!hasRetried) {
                hasRetried = true;
                navFailed = false;
                failReason = null;
                goalReached = false;

                // Retry with a relaxed goal (within NEAR_RETRY_RADIUS blocks).
                // Use the same safe-default logic as onStart() for null y/z
                // (xz mode has y=null; retrying with null would NPE in GoalNear).
                ServerPlayer sp = TaskContext.serverPlayer(player);
                startRetry(sp);

                System.out.println("[MineAgent] MoveTo retrying with relaxed goal "
                        + "(radius=" + NEAR_RETRY_RADIUS + ")");

                // Reset lease for the retry
                long gameTime = sp.level().getGameTime();
                lease.start(gameTime);
                lastProgressPos = sp.position();
                return TaskState.RUNNING;
            }
            return TaskState.FAILED;
        }
        if (goalReached) return TaskState.SUCCESS;

        // Tick the navigation
        nav.tick();

        // Re-check after tick (listener may have fired)
        if (goalReached) return TaskState.SUCCESS;
        if (navFailed) {
            // Same retry logic as above
            if (!hasRetried) {
                hasRetried = true;
                navFailed = false;
                failReason = null;
                ServerPlayer sp = TaskContext.serverPlayer(player);
                startRetry(sp);
                long gameTime = sp.level().getGameTime();
                lease.start(gameTime);
                lastProgressPos = sp.position();
                return TaskState.RUNNING;
            }
            return TaskState.FAILED;
        }

        // Progress lease management
        ServerPlayer sp = TaskContext.serverPlayer(player);
        long gameTime = sp.level().getGameTime();

        // Detect progress in 3D. Horizontal-only distance made goal_mode=y
        // climbs look permanently stuck even while the companion was moving
        // upward/downward along a valid path, so their lease never renewed.
        Vec3 currentPos = sp.position();
        if (lastProgressPos != null && currentPos.distanceTo(lastProgressPos) > 1.0) {
            lease.onProgress(gameTime);
            lastProgressPos = currentPos;
        }

        // Tick the lease (checks grace period)
        lease.tick(gameTime);

        // Check if the lease has expired
        if (lease.isExpired(gameTime)) {
            String reason = lease.expirationReason(gameTime);
            System.out.println("[MineAgent] MoveTo lease expired: " + reason);
            nav.cancel();
            TaskContext.inputDriver(player).clear();
            return TaskState.FAILED;
        }

        return TaskState.RUNNING;
    }

    private void startRetry(ServerPlayer sp) {
        int retryY = record.y != null ? record.y : sp.blockPosition().getY();
        int retryZ = record.z != null ? record.z : sp.blockPosition().getZ();
        switch (record.goalMode) {
            case "xz" -> nav.navigateToGoal(
                    new com.mineagent.engine.pathing.goals.GoalNearXZ(
                            record.x, retryZ, NEAR_RETRY_RADIUS));
            case "y" -> nav.navigateToYLevel(retryY);
            case "xzy", "block" -> nav.navigateNear(
                    record.x, retryY, retryZ, NEAR_RETRY_RADIUS);
            default -> {
                navFailed = true;
                failReason = "Invalid goal_mode: " + record.goalMode;
            }
        }
    }

    @Override
    public void onInterrupt() {
        if (nav != null) {
            nav.cancel();
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected String successMessage() {
        var pos = TaskContext.serverPlayer(player).blockPosition();
        return "Arrived at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    @Override
    protected String timeoutMessage() {
        var pos = TaskContext.serverPlayer(player).blockPosition();
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        String reason = lease.expirationReason(gameTime);
        return "Navigation timed out at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + ") heading for mode=" + record.goalMode
                + " target=(" + record.x + ", " + (record.y != null ? record.y : "null")
                + ", " + (record.z != null ? record.z : "null") + ")"
                + " reason=" + reason;
    }

    @Override
    protected String failureMessage() {
        String yStr = record.y != null ? record.y.toString() : "null";
        String zStr = record.z != null ? record.z.toString() : "null";
        if (failReason != null) {
            return "Navigation failed: " + failReason
                    + " (mode=" + record.goalMode
                    + " target=(" + record.x + ", " + yStr + ", " + zStr + "))";
        }
        return "Navigation failed (mode=" + record.goalMode
                + " target=(" + record.x + ", " + yStr + ", " + zStr + "))";
    }
}
