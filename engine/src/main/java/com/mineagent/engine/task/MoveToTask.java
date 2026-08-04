package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
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
public class MoveToTask extends CompanionTask<MoveToTool.MoveToTaskRecord>
        implements IntentAwareTask {

    private PlayerNav nav;
    private volatile boolean goalReached;
    private volatile boolean navFailed;
    private String failReason;

    /** Progress lease — replaces the single hard deadline. */
    private final TaskLease lease = new TaskLease();

    /** Last recorded position — used to detect progress. */
    private Vec3 lastProgressPos = null;

    public MoveToTask(AgentPlayer player, MoveToTool.MoveToTaskRecord record) {
        super(player, record);
    }

    @Override
    public void onStart() {
        PathCaches caches = TaskContext.navCaches(player);
        goalReached = false;
        navFailed = false;
        failReason = null;
        nav = new PlayerNav(player, caches, intentContract().terrainPolicy());

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
            // PathingCore already performs bounded replanning. Relaxing an
            // exact xzy/block goal to a three-block radius here reported
            // success at a position that did not satisfy the tool request.
            return TaskState.FAILED;
        }
        if (goalReached) return TaskState.SUCCESS;

        // Tick the navigation
        nav.tick();

        // Re-check after tick (listener may have fired)
        if (goalReached) return TaskState.SUCCESS;
        if (navFailed) {
            return TaskState.FAILED;
        }

        // Progress lease management
        ServerPlayer sp = TaskContext.serverPlayer(player);
        long gameTime = sp.level().getGameTime();

        // Vertical-only goals must renew while climbing as well as walking.
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
            failReason = "Navigation lease expired: " + reason;
            return TaskState.FAILED;
        }

        return TaskState.RUNNING;
    }

    @Override
    public void onInterrupt() {
        if (nav != null) {
            nav.cancel();
        }
        TaskContext.inputDriver(player).clear();
    }

    @Override
    public TaskSnapshot snapshot() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        int completed = 0;
        int total = -1;
        String stage = nav == null ? "initializing"
                : nav.state().name().toLowerCase(java.util.Locale.ROOT);
        if (nav != null && nav.core().executor() != null) {
            completed = nav.core().executor().currentMovementIndex();
            total = nav.core().executor().path().length();
        }
        Integer targetY = "xz".equals(record.goalMode) ? null : record.y;
        Integer targetZ = "y".equals(record.goalMode) ? null : record.z;
        Integer targetX = "y".equals(record.goalMode) ? null : record.x;
        long version = ((long) Math.max(0, completed) << 32)
                ^ (goalReached ? 2L : 0L) ^ (navFailed ? 1L : 0L);
        return TaskSnapshot.progress(stage,
                goalReached ? "Navigation goal reached"
                        : navFailed ? "Navigation failed" : "Navigating to requested goal",
                completed, total, targetX, targetY, targetZ,
                navFailed ? failReason : null,
                "player=" + sp.blockPosition(), version);
    }

    @Override
    public IntentContract intentContract() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        int currentY = sp.blockPosition().getY();
        int targetY = record.y == null ? currentY : record.y;
        int horizontalDistance = record.z == null ? 0
                : Math.abs(record.x - sp.blockPosition().getX())
                + Math.abs(record.z - sp.blockPosition().getZ());
        int placementBudget = Math.max(4, Math.min(32, horizontalDistance / 8 + 4));
        IntentContract.TerrainPolicy policy = new IntentContract.TerrainPolicy(
                true, true, true, true, placementBudget, 16,
                Math.max(4, targetY - currentY + 4),
                IntentContract.CleanupMode.CONTEXTUAL);
        return new IntentContract("Reach the requested navigation goal",
                "The executor verifies the exact selected goal predicate",
                "y".equals(record.goalMode) ? null : record.x,
                "xz".equals(record.goalMode) ? null : record.y,
                "y".equals(record.goalMode) ? null : record.z,
                policy, java.util.List.of(
                new IntentContract.Constraint("goal_exactness",
                        IntentContract.ConstraintKind.HARD,
                        "Do not report success unless the requested goal predicate is true",
                        "navigation")));
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
                + " target=(" + record.x + ", " + yStr + ", " + zStr + ")";
    }
}
