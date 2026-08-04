package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/** Sprint-jumps across one cardinal gap into a validated landing cell. */
public class MovementParkour extends Movement {
    private final Input tickInput = new Input();
    private boolean leftGround;

    public MovementParkour(int srcX, int srcY, int srcZ,
                           int dstX, int dstY, int dstZ) {
        super(srcX, srcY, srcZ, dstX, dstY, dstZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        if (dstY > srcY || !MovementHelper.canStandOn(ctx, dstX, dstY, dstZ)) {
            return cost = Double.POSITIVE_INFINITY;
        }
        int midX = (srcX + dstX) / 2;
        int midZ = (srcZ + dstZ) / 2;
        if (ctx.getBlockState(midX, srcY - 1, midZ) == null
                || MovementHelper.canStandOn(ctx, midX, srcY, midZ)
                || !MovementHelper.isClearForJump(ctx, srcX, srcY, srcZ)
                || !MovementHelper.isClearForJump(ctx, midX, srcY, midZ)) {
            return cost = Double.POSITIVE_INFINITY;
        }
        double breakCost = MovementHelper.costOfBreaking(ctx, dstX, dstY, dstZ)
                + MovementHelper.costOfBreaking(ctx, dstX, dstY + 1, dstZ);
        if (!Double.isFinite(breakCost)) return cost = Double.POSITIVE_INFINITY;
        return cost = ActionCosts.SPRINT_JUMP + breakCost
                + MovementHelper.getWalkCostModifier(ctx, dstX, dstY, dstZ);
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) return tickInput;

        tickInput.sprinting(true);
        moveToward(player, dstX, dstZ, tickInput);
        var sp = TaskContext.serverPlayer(player);
        var pos = sp.position();
        double directionX = dstX - srcX;
        double directionZ = dstZ - srcZ;
        double directionLength = Math.sqrt(directionX * directionX + directionZ * directionZ);
        double forwardProgress = ((pos.x - (srcX + 0.5)) * directionX
                + (pos.z - (srcZ + 0.5)) * directionZ) / directionLength;
        if (!sp.onGround()) leftGround = true;
        else if (leftGround) leftGround = false;
        // Hold jump until physics confirms takeoff; a one-tick pulse is order-dependent.
        if (!leftGround && forwardProgress > 0.15 && sp.onGround()) tickInput.jumping(true);
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        var sp = TaskContext.serverPlayer(player);
        return (sp.onGround() || sp.isInWater()) && isAtDestination(player);
    }
}
