package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;

/**
 * Ascend movement - jump up one block in a cardinal direction.
 * The companion moves from (srcX, srcY, srcZ) to (dstX, srcY+1, dstZ).
 * Requires clearance at srcY+2 and dstY+1 for the jump.
 */
public class MovementAscend extends Movement {

    private final Input tickInput = new Input();

    public MovementAscend(int srcX, int srcY, int srcZ, int dstX, int dstZ) {
        super(srcX, srcY, srcZ, dstX, srcY + 1, dstZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Check clearance above source for jump
        if (!MovementHelper.isClearForJump(ctx, srcX, srcY, srcZ)) {
            // A blocked jump cell is legal only when the planner and executor
            // both have dig-through enabled.
            double breakAboveSrc = MovementHelper.costOfBreaking(ctx, srcX, srcY + 2, srcZ);
            if (breakAboveSrc == Double.POSITIVE_INFINITY) {
                this.cost = Double.POSITIVE_INFINITY;
                return this.cost;
            }
        }

        // Check destination is standable
        if (!MovementHelper.canStandAfterBreaking(ctx, dstX, dstY, dstZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Calculate break costs
        double breakCost = 0;
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY, dstZ);
        breakCost += MovementHelper.costOfBreaking(ctx, dstX, dstY + 1, dstZ);
        // Also might need to break the block at the jump target head level
        breakCost += MovementHelper.costOfBreaking(ctx, srcX, srcY + 2, srcZ);

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
        this.cost = ActionCosts.JUMP_UP + breakCost + supportCost + modifier;
        return this.cost;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        if (!ensureSupport(player, dstX, dstY, dstZ)) return tickInput;
        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(srcX, srcY + 2, srcZ),
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) {
            return tickInput;
        }
        moveToward(player, dstX, dstZ, tickInput);
        tickInput.jumping(true);
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        var sp = com.mineagent.engine.task.TaskContext.serverPlayer(player);
        // Passing through the target Y at the jump apex is not completion.
        return (sp.onGround() || sp.isInWater()) && isAtDestination(player);
    }

    @Override
    public net.minecraft.core.BlockPos requiredSupportPosition() {
        return new net.minecraft.core.BlockPos(dstX, dstY - 1, dstZ);
    }
}
