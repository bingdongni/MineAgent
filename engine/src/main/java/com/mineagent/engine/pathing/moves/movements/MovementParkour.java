package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/**
 * Parkour movement — sprint-jump across a gap (2 blocks horizontal).
 * The companion sprints and jumps to cross a 1-block gap.
 */
public class MovementParkour extends Movement {

    private final Input tickInput = new Input();
    private boolean leftGround;

    public MovementParkour(int srcX, int srcY, int srcZ, int dstX, int dstY, int dstZ) {
        super(srcX, srcY, srcZ, dstX, dstY, dstZ);
        this.leftGround = false;
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Check that source and destination are at the same Y (level parkour)
        // or destination is slightly lower
        if (dstY > srcY) {
            // Can't parkour upward
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Check destination is standable
        if (!MovementHelper.canStandOn(ctx, dstX, dstY, dstZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Check that the gap is actually a gap (no bridge already there)
        int midX = (srcX + dstX) / 2;
        int midZ = (srcZ + dstZ) / 2;

        var gapFloor = ctx.getBlockState(midX, srcY - 1, midZ);
        if (gapFloor == null || MovementHelper.canStandOn(ctx, midX, srcY, midZ)) {
            // This movement is specifically a gap jump. If the intermediate
            // cell is walkable, ordinary traversal is both safer and cheaper.
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // A two-block jump crosses the intermediate column and rises above
        // normal walking height. Without these checks A* could emit a finite
        // edge through a wall or a low ceiling that execution can never pass.
        if (!MovementHelper.isClearForJump(ctx, srcX, srcY, srcZ)
                || !MovementHelper.isClearForJump(ctx, midX, srcY, midZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Verify the gap blocks aren't solid (otherwise it's just walking)
        double breakCost = 0;
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY, dstZ);
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY + 1, dstZ);

        if (breakCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        double modifier = MovementHelper.getWalkCostModifier(ctx, dstX, dstY, dstZ);
        this.cost = ActionCosts.SPRINT_JUMP + breakCost + modifier;
        return this.cost;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();

        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) {
            return tickInput;
        }

        // Sprint toward destination
        tickInput.sprinting(true);
        moveToward(player, dstX, dstZ, tickInput);

        // Jump at the edge
        var pos = TaskContext.serverPlayer(player).position();
        double directionX = dstX - srcX;
        double directionZ = dstZ - srcZ;
        double directionLength = Math.sqrt(directionX * directionX
                + directionZ * directionZ);
        double forwardProgress = ((pos.x - (srcX + 0.5)) * directionX
                + (pos.z - (srcZ + 0.5)) * directionZ) / directionLength;

        var sp = TaskContext.serverPlayer(player);
        if (!sp.onGround()) {
            leftGround = true;
        } else if (leftGround) {
            // A jump can be shortened by collision or loss of sprint. Once
            // vanilla confirms that we landed somewhere other than the goal,
            // re-arm the jump instead of walking into the gap forever.
            leftGround = false;
        }

        if (!leftGround && forwardProgress > 0.15 && sp.onGround()) {
            // Keep the jump key held until the following physics tick proves
            // that the player actually left the ground. Marking the jump as
            // complete when merely issuing one input pulse made execution
            // sensitive to tick ordering and could walk straight into a gap.
            tickInput.jumping(true);
        }

        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        var sp = TaskContext.serverPlayer(player);
        return (sp.onGround() || sp.isInWater()) && isAtDestination(player);
    }
}
