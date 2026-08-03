package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;

/**
 * Horizontal walk movement - move one block in a cardinal direction.
 * The companion walks from (srcX, srcY, srcZ) to (dstX, srcY, dstZ)
 * where dstX/dstZ is exactly one block away.
 */
public class MovementTraverse extends Movement {

    private final Input tickInput = new Input();

    public MovementTraverse(int srcX, int srcY, int srcZ, int dstX, int dstZ) {
        super(srcX, srcY, srcZ, dstX, srcY, dstZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Check that destination is standable
        if (!MovementHelper.canStandAfterBreaking(ctx, dstX, dstY, dstZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Check for obstacles in the path
        double breakCost = 0;
        // Check if we need to break blocks at destination
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY, dstZ);
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY + 1, dstZ);

        if (breakCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Get walk cost modifier (water/lava penalty)
        double modifier = MovementHelper.getWalkCostModifier(ctx, dstX, dstY, dstZ);

        double supportCost = MovementHelper.costOfSupport(
                ctx, dstX, dstY, dstZ, srcX, srcY, srcZ);
        if (supportCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }
        this.cost = ActionCosts.WALK + breakCost + supportCost + modifier;
        return this.cost;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        if (!ensureSupport(player, dstX, dstY, dstZ)) return tickInput;
        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) {
            return tickInput;
        }
        moveToward(player, dstX, dstZ, tickInput);
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        return isAtDestination(player);
    }

    @Override
    public net.minecraft.core.BlockPos requiredSupportPosition() {
        return new net.minecraft.core.BlockPos(dstX, dstY - 1, dstZ);
    }
}
