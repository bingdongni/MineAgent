package com.mineagent.engine.pathing.moves;

import com.mineagent.engine.pathing.astar.PathNode;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.task.BlockDigger;
import com.mineagent.engine.behavior.HumanLikeNoise;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.engine.planning.TemporaryBlockLedger;
import net.minecraft.core.BlockPos;

/**
 * Abstract base class for all movement types. A Movement represents a
 * single step in a path — it knows how to calculate whether the move
 * is possible, what it costs, and how to execute it tick-by-tick.
 *
 * <p>Each movement has:
 * <ul>
 *   <li>A source position (where the companion starts)</li>
 *   <li>A destination position (where the companion ends up)</li>
 *   <li>A calculated cost (for A*)</li>
 *   <li>An execution phase (tick-by-tick input control)</li>
 * </ul>
 */
public abstract class Movement {

    protected final int srcX;
    protected final int srcY;
    protected final int srcZ;
    protected final int dstX;
    protected final int dstY;
    protected final int dstZ;
    protected double cost;
    private BlockPos activeBreakTarget;
    private int activeBreakTimeoutTicks = 200;
    private int progressVersion;
    private IntentContract.CleanupMode supportCleanupMode =
            IntentContract.CleanupMode.CONTEXTUAL;

    protected Movement(int srcX, int srcY, int srcZ, int dstX, int dstY, int dstZ) {
        this.srcX = srcX;
        this.srcY = srcY;
        this.srcZ = srcZ;
        this.dstX = dstX;
        this.dstY = dstY;
        this.dstZ = dstZ;
    }

    /**
     * Calculate whether this movement is possible and its cost.
     * Should be called once during path construction.
     *
     * @param ctx the calculation context for world queries
     * @return the cost, or Double.POSITIVE_INFINITY if impossible
     */
    public abstract double calculateCost(CalculationContext ctx);

    /**
     * Get the input state for the current tick of execution.
     *
     * @param player the companion player
     * @return the input to apply this tick, or null if execution is complete
     */
    public abstract Input getTickInput(AgentPlayer player);

    /**
     * Check if this movement's execution is finished.
     *
     * @param player the companion player
     * @return true if the companion has reached the destination
     */
    public abstract boolean isFinished(AgentPlayer player);

    /**
     * Called when this movement fails during execution (e.g., companion
     * fell off a bridge). Subclasses can override to clean up.
     */
    public void onFailure(AgentPlayer player) {
        if (activeBreakTarget != null) {
            // A cancelled edge must release ServerPlayerGameMode's progressive
            // break target or the next action can continue damaging stale terrain.
            BlockDigger.abortBreaking(TaskContext.serverPlayer(player), activeBreakTarget);
            activeBreakTarget = null;
            activeBreakTimeoutTicks = 200;
        }
    }

    /**
     * Clear every occupancy cell before movement input is applied.
     * Planning a finite break cost without executing the vanilla break state
     * machine made paths stop forever at their first real obstruction.
     */
    protected boolean ensureClearance(AgentPlayer player, BlockPos... cells) {
        var sp = TaskContext.serverPlayer(player);
        if (activeBreakTarget != null) {
            if (!BlockHelper.isPassable(sp.level().getBlockState(activeBreakTarget))) {
                return false;
            }
            activeBreakTarget = null;
            markProgress();
        }

        for (BlockPos cell : cells) {
            var state = sp.level().getBlockState(cell);
            if (BlockHelper.isPassable(state)) continue;
            if (BlockHelper.canOpenByHand(state)) {
                sp.setShiftKeyDown(false);
                com.mineagent.engine.act.Interaction.interactBlock(sp, cell,
                        net.minecraft.world.InteractionHand.MAIN_HAND);
                if (BlockHelper.isPassable(sp.level().getBlockState(cell))) {
                    markProgress();
                    continue;
                }
                return false;
            }

            activeBreakTimeoutTicks = BlockDigger.expectedBreakTicks(sp, cell);
            if (BlockDigger.startBreaking(sp, cell)) activeBreakTarget = cell;
            return false;
        }
        return true;
    }

    /** Ensure the destination has real support before walking into it. */
    protected boolean ensureSupport(AgentPlayer player, int x, int y, int z) {
        var sp = TaskContext.serverPlayer(player);
        if (BlockHelper.isClimbable(sp.level().getBlockState(new BlockPos(x, y, z)))) {
            return true;
        }
        BlockPos support = new BlockPos(x, y - 1, z);
        if (BlockHelper.canStandOn(sp.level().getBlockState(support))) return true;
        boolean placed = com.mineagent.engine.act.Placement.placeAnySupportBlock(sp, support);
        if (placed) {
            // Only a verified post-placement world state enters the ledger.
            // That ownership evidence prevents cleanup from mining arbitrary
            // player-built blocks that merely happen to lie on a route.
            TaskContext.temporaryBlocks(player).recordPlaced(support,
                    sp.level().getBlockState(support),
                    TemporaryBlockLedger.Purpose.BRIDGE, supportCleanupMode);
            markProgress();
        }
        return placed;
    }

    /** Configure how supports created by this task should be treated later. */
    public final void configureSupportCleanup(IntentContract.CleanupMode mode) {
        supportCleanupMode = mode == null
                ? IntentContract.CleanupMode.CONTEXTUAL : mode;
    }

    protected final IntentContract.CleanupMode supportCleanupMode() {
        return supportCleanupMode;
    }

    /** Monotonic marker used by PathExecutor to renew timeouts on real progress. */
    public final int progressVersion() { return progressVersion; }

    /** Hard blocks receive a bounded timeout derived from vanilla break speed. */
    public final int executionTimeoutTicks() {
        return activeBreakTarget == null ? 200 : Math.max(200, activeBreakTimeoutTicks);
    }

    protected final void markProgress() { progressVersion++; }

    /** Get the source position. */
    public int srcX() { return srcX; }
    public int srcY() { return srcY; }
    public int srcZ() { return srcZ; }

    /** Get the destination position. */
    public int dstX() { return dstX; }
    public int dstY() { return dstY; }
    public int dstZ() { return dstZ; }

    /** Support block this edge may need to place, or null for no placement. */
    public BlockPos requiredSupportPosition() { return null; }

    /** Get the calculated cost. */
    public double cost() { return cost; }

    /**
     * Check if the companion is roughly at the destination block position.
     */
    protected boolean isAtDestination(AgentPlayer player) {
        var pos = TaskContext.serverPlayer(player).blockPosition();
        return pos.getX() == dstX && pos.getY() == dstY && pos.getZ() == dstZ;
    }

    /**
     * Calculate the direction from the player's current position
     * to the destination and set movement inputs accordingly.
     *
     * <p><b>Human-like movement:</b> A real player turns to face the
     * direction they want to go, then presses W (forward). They don't
     * strafe sideways toward their target like a crab. This method
     * rotates the companion's yaw to face the target, then applies
     * pure forward input. This is the single most important factor in
     * making AI movement look natural instead of robotic.
     */
    protected void moveToward(AgentPlayer player, int targetX, int targetZ, Input input) {
        var sp = TaskContext.serverPlayer(player);
        var pos = sp.position();
        double dx = (targetX + 0.5) - pos.x;
        double dz = (targetZ + 0.5) - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return;

        // Target yaw that faces the destination
        // Minecraft yaw: 0=South(+Z), 90=West(-X), 180=North(-Z), 270=East(+X)
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

        // Smoothly rotate toward the target yaw using human-like turning.
        // Real players turn their view gradually with ease-out near the
        // target and tiny jitter — not a linear clamp like a robot.
        // HumanLikeNoise.adaptiveTurn:
        //   - Full speed when diff > 45° (fast flick toward target)
        //   - Ease-out (sqrt) when diff < 45° (decelerate near target)
        //   - Tiny ±0.4° jitter (human hand isn't perfectly steady)
        float currentYaw = sp.getYRot();
        float yawDiff = wrapAngleDegrees(targetYaw - currentYaw);
        float maxTurnPerTick = 15.0f;
        float turnAmount = HumanLikeNoise.adaptiveTurn(yawDiff, maxTurnPerTick);
        float newYaw = wrapAngleDegrees(currentYaw + turnAmount);
        sp.setYRot(newYaw);
        // Keep head pitch level when walking (don't look up/down while moving)
        sp.setXRot(0);

        // Now compute forward/strafe relative to the NEW yaw.
        // If we're nearly facing the target, go full forward (W key).
        // If we're still turning, apply partial forward so the companion
        // starts walking while turning — just like a real player.
        float yaw = newYaw * ((float) Math.PI / 180f);
        double sin = -Math.sin(yaw);
        double cos = Math.cos(yaw);

        double forward = dx * sin + dz * cos;
        double strafe = dx * cos - dz * sin;
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len > 0.01) forward /= len;

        // Scale forward by how aligned we are with the target direction.
        // When still turning sharply (|yawDiff| > 90°), slow down —
        // a real player wouldn't sprint off in the wrong direction.
        // Add tiny speed jitter (±5%) for human-like imperfection.
        float alignment = Math.max(0, 1.0f - Math.abs(yawDiff) / 90.0f);
        float fwdInput = (float) Math.max(0, Math.min(1, forward * alignment));
        fwdInput = HumanLikeNoise.jitterSpeed(fwdInput);
        input.forward(fwdInput);
        // Path edges are centered one block at a time. Full strafe while the
        // body is still turning moves sideways off narrow bridges and makes
        // the claimed "face then press forward" behavior untrue. Rotation
        // plus forward input is both human-readable and collision-stable.
        input.strafe(0.0f);

        // Sprint when going roughly straight and far from target
        if (alignment > 0.8f && dist > 3.0) {
            input.sprinting(true);
        }
    }

    /** Wrap an angle to the range (-180, 180]. */
    private static float wrapAngleDegrees(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[(" + srcX + "," + srcY + "," + srcZ
                + ") → (" + dstX + "," + dstY + "," + dstZ + ")] cost=" + cost;
    }
}
