package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;

/**
 * Descend movement - step down one block in a cardinal direction.
 * The companion moves from (srcX, srcY, srcZ) to (dstX, srcY-1, dstZ).
 */
public class MovementDescend extends Movement {

    private final Input tickInput = new Input();

    public MovementDescend(int srcX, int srcY, int srcZ, int dstX, int dstZ) {
        super(srcX, srcY, srcZ, dstX, srcY - 1, dstZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Check destination is standable
        if (!MovementHelper.canStandAfterBreaking(ctx, dstX, dstY, dstZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Calculate break costs at destination
        double breakCost = 0;
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY, dstZ);
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY + 1, dstZ);

        if (breakCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        double modifier = MovementHelper.getWalkCostModifier(ctx, dstX, dstY, dstZ);
        double supportCost = MovementHelper.costOfSupport(
                ctx, dstX, dstY, dstZ, srcX, srcY, srcZ);
        if (supportCost == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }
        this.cost = ActionCosts.STEP_DOWN + breakCost + supportCost + modifier;
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
        var sp = com.mineagent.engine.task.TaskContext.serverPlayer(player);
        return (sp.onGround() || sp.isInWater()) && isAtDestination(player);
    }

    @Override
    public net.minecraft.core.BlockPos requiredSupportPosition() {
        return new net.minecraft.core.BlockPos(dstX, dstY - 1, dstZ);
    }
}
