package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/** One safe jump-and-place vertical pillar edge. */
public class MovementPillar extends Movement {
    private final Input tickInput = new Input();
    private boolean supportPlaced;

    public MovementPillar(int srcX, int srcY, int srcZ) {
        super(srcX, srcY, srcZ, srcX, srcY + 1, srcZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        double supportCost = MovementHelper.costOfSupport(
                ctx, srcX, dstY, srcZ, srcX, srcY, srcZ);
        double feetBreak = MovementHelper.costOfBreaking(ctx, srcX, dstY, srcZ);
        double headBreak = MovementHelper.costOfBreaking(ctx, srcX, dstY + 1, srcZ);
        if (!Double.isFinite(supportCost)
                || !Double.isFinite(feetBreak) || !Double.isFinite(headBreak)) {
            return cost = Double.POSITIVE_INFINITY;
        }
        // costOfSupport already charges PLACE_BLOCK when the old feet cell
        // needs a pillar block. Charging it again made valid pillar routes
        // look twice as expensive and caused A* to choose long detours.
        return cost = supportCost + ActionCosts.JUMP_UP + feetBreak + headBreak;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        var sp = TaskContext.serverPlayer(player);
        var supportPos = new net.minecraft.core.BlockPos(srcX, srcY, srcZ);
        if (!supportPlaced && com.mineagent.engine.pathing.util.BlockHelper.canStandOn(
                sp.level().getBlockState(supportPos))) {
            // Another actor or a delayed server update may have placed the
            // planned support already. Treat the world state as authoritative;
            // retrying Placement forever would stall an otherwise valid edge.
            supportPlaced = true;
            markProgress();
        }
        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) return tickInput;

        if (!supportPlaced) {
            double centerDx = srcX + 0.5 - sp.getX();
            double centerDz = srcZ + 0.5 - sp.getZ();
            if (centerDx * centerDx + centerDz * centerDz > 0.04) {
                moveToward(player, srcX, srcZ, tickInput);
                return tickInput;
            }
            tickInput.jumping(true);
            // Wait until the player's hitbox has cleared the old feet cell.
            if (sp.getY() >= srcY + 1.01) {
                supportPlaced = com.mineagent.engine.act.Placement
                        .placeAnySupportBlock(sp,
                                supportPos);
                if (supportPlaced) markProgress();
            }
        }
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        var sp = TaskContext.serverPlayer(player);
        var support = sp.level().getBlockState(
                new net.minecraft.core.BlockPos(srcX, srcY, srcZ));
        return supportPlaced
                && com.mineagent.engine.pathing.util.BlockHelper.canStandOn(support)
                && sp.onGround() && isAtDestination(player);
    }

    @Override
    public net.minecraft.core.BlockPos requiredSupportPosition() {
        return new net.minecraft.core.BlockPos(srcX, srcY, srcZ);
    }
}
