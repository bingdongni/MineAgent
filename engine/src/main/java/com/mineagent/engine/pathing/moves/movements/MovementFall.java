package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/** A deliberate non-damaging fall to an exact, validated landing cell. */
public class MovementFall extends Movement {
    private final Input tickInput = new Input();
    private int fallDistance;

    public MovementFall(int srcX, int srcY, int srcZ, int dstX, int dstY, int dstZ) {
        super(srcX, srcY, srcZ, dstX, dstY, dstZ);
        fallDistance = srcY - dstY;
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        int groundY = MovementHelper.findGroundBelow(
                ctx, dstX, srcY - 1, dstZ, ctx.level().getMinBuildHeight());
        if (groundY == Integer.MIN_VALUE || groundY != dstY) {
            return cost = Double.POSITIVE_INFINITY;
        }
        fallDistance = srcY - groundY;
        // MLG is an emergency survival chain with inventory/aim validation.
        // A generic path edge cannot promise it, so never plan damaging falls.
        if (fallDistance > 3
                || !MovementHelper.canFallThrough(ctx, dstX, srcY, dstZ, groundY)) {
            return cost = Double.POSITIVE_INFINITY;
        }
        double breakCost = MovementHelper.costOfBreaking(ctx, dstX, groundY, dstZ)
                + MovementHelper.costOfBreaking(ctx, dstX, groundY + 1, dstZ);
        if (!Double.isFinite(breakCost)) return cost = Double.POSITIVE_INFINITY;
        return cost = ActionCosts.fallCost(fallDistance)
                + breakCost + MovementHelper.getWalkCostModifier(ctx, dstX, groundY, dstZ);
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        var sp = TaskContext.serverPlayer(player);
        for (int y = dstY; y <= srcY + 1; y++) {
            if (!com.mineagent.engine.pathing.util.BlockHelper.isPassable(
                    sp.level().getBlockState(new net.minecraft.core.BlockPos(dstX, y, dstZ)))) {
                // Never mine a fall shaft after stepping off: lower cells may
                // be out of reach and a partial break would leave the body in
                // an uncontrolled fall. The edge times out and replans safely.
                return tickInput;
            }
        }
        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) return tickInput;
        if (sp.blockPosition().getY() > dstY) {
            moveToward(player, dstX, dstZ, tickInput);
        }
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        var sp = TaskContext.serverPlayer(player);
        return (sp.onGround() || sp.isInWater()) && isAtDestination(player);
    }
}
