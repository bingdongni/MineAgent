package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/**
 * Fall movement — drop down 2+ blocks in a cardinal direction.
 * The companion walks off a ledge and falls to the ground below.
 * Deliberate damaging falls are rejected during cost calculation; emergency
 * falls are handled independently by the survival MLG chain.
 */
public class MovementFall extends Movement {

    private final Input tickInput = new Input();
    private int fallDistance;

    public MovementFall(int srcX, int srcY, int srcZ, int dstX, int dstY, int dstZ) {
        super(srcX, srcY, srcZ, dstX, dstY, dstZ);
        this.fallDistance = srcY - dstY;
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Find the actual ground level below the destination column
        int groundY = MovementHelper.findGroundBelow(ctx, dstX, srcY - 1, dstZ, ctx.level().getMinBuildHeight());
        if (groundY == Integer.MIN_VALUE) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // A* probes several candidate Y levels. Only the node matching the
        // actual landing height is valid; accepting every probe made the path
        // node disagree with this movement's immutable destination at runtime.
        if (groundY != dstY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        this.fallDistance = srcY - groundY;
        boolean damagingFall = fallDistance > 3;

        // Planned falls must be independently executable. The movement layer
        // has no inventory-aware bucket selection or reliable target hit, so a
        // deliberate damaging fall cannot safely promise an MLG placement.
        if (damagingFall) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Check that the fall path is clear
        if (!MovementHelper.canFallThrough(ctx, dstX, srcY, dstZ, groundY)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Check destination head clearance
        double breakCost = 0;
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, groundY, dstZ);
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, groundY + 1, dstZ);

        if (breakCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        double baseCost = ActionCosts.fallCost(fallDistance);
        double modifier = MovementHelper.getWalkCostModifier(ctx, dstX, groundY, dstZ);

        this.cost = baseCost + breakCost + modifier;
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

        // While still above, move toward the fall point
        if (TaskContext.serverPlayer(player).blockPosition().getY() > dstY) {
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
