package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;

/**
 * Diagonal movement - move one block diagonally (e.g., NE, NW, SE, SW).
 * The companion walks diagonally, which is slightly more expensive than
 * cardinal walking but shorter than going around.
 *
 * <p>For a diagonal move to be valid, at least one of the two adjacent
 * cardinal positions must be passable (to avoid cutting through corners).
 */
public class MovementDiagonal extends Movement {

    private final Input tickInput = new Input();

    /** The two cardinal neighbors to check for corner-cutting. */
    private final int adj1X, adj1Z;
    private final int adj2X, adj2Z;

    public MovementDiagonal(int srcX, int srcY, int srcZ, int dstX, int dstZ,
                            int adj1X, int adj1Z, int adj2X, int adj2Z) {
        super(srcX, srcY, srcZ, dstX, srcY, dstZ);
        this.adj1X = adj1X;
        this.adj1Z = adj1Z;
        this.adj2X = adj2X;
        this.adj2Z = adj2Z;
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // Check destination is standable
        if (!MovementHelper.canStandAfterBreaking(ctx, dstX, dstY, dstZ)) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Both side columns must be clear. The player is 0.6 blocks wide, so
        // a diagonal segment clips the corner of either occupied cardinal
        // cell; accepting one clear side produces a finite but unexecutable
        // edge that repeatedly times out against the other wall.
        boolean adj1Clear = MovementHelper.canWalkThrough(ctx, adj1X, srcY, adj1Z);
        boolean adj2Clear = MovementHelper.canWalkThrough(ctx, adj2X, srcY, adj2Z);
        if (!adj1Clear || !adj2Clear) {
            // The executor clears destination occupancy, not an arbitrary
            // side corridor. Charging a side break cost here (which also was
            // never added to the result) created an unexecutable corner edge.
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }

        // Break costs at destination
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
        this.cost = ActionCosts.WALK_DIAGONAL + breakCost + supportCost + modifier;
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
