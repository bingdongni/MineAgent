package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.*;
import com.mineagent.engine.task.TaskContext;

/**
 * Pillar movement — build straight up by placing blocks below and jumping.
 * The companion places a block at feet level, jumps on top of it, and
 * repeats until reaching the target Y level.
 */
public class MovementPillar extends Movement {

    private final Input tickInput = new Input();
    private boolean supportPlaced;

    public MovementPillar(int srcX, int srcY, int srcZ) {
        super(srcX, srcY, srcZ, srcX, srcY + 1, srcZ);
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        // A pillar edge raises the feet by exactly one block. The new support
        // occupies the old feet cell, while the destination feet/head cells
        // must be clear (or legally breakable) before jumping.
        double supportCost = MovementHelper.costOfSupport(
                ctx, srcX, dstY, srcZ, srcX, srcY, srcZ);
        double feetBreak = MovementHelper.costOfBreaking(ctx, srcX, dstY, srcZ);
        double headBreak = MovementHelper.costOfBreaking(ctx, srcX, dstY + 1, srcZ);
        if (supportCost == Double.POSITIVE_INFINITY
                || feetBreak == Double.POSITIVE_INFINITY
                || headBreak == Double.POSITIVE_INFINITY) {
            this.cost = Double.POSITIVE_INFINITY;
            return this.cost;
        }
        this.cost = ActionCosts.PLACE_BLOCK + ActionCosts.JUMP_UP
                + feetBreak + headBreak;
        return this.cost;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        var sp = TaskContext.serverPlayer(player);

        if (!ensureClearance(player,
                new net.minecraft.core.BlockPos(dstX, dstY, dstZ),
                new net.minecraft.core.BlockPos(dstX, dstY + 1, dstZ))) {
            return tickInput;
        }

        if (!supportPlaced) {
            // Do not materialize a block through the player's hitbox. Wait
            // until the jump has lifted its feet close to the old cell's top,
            // then place a real inventory block and consume exactly one item.
            double centerDx = (srcX + 0.5) - sp.getX();
            double centerDz = (srcZ + 0.5) - sp.getZ();
            if (centerDx * centerDx + centerDz * centerDz > 0.04) {
                // Center before jumping so the newly placed pillar remains
                // under the complete player hitbox instead of only one edge.
                moveToward(player, srcX, srcZ, tickInput);
                return tickInput;
            }
            tickInput.jumping(true);
            if (sp.getY() >= srcY + 1.01) {
                var support = new net.minecraft.core.BlockPos(srcX, srcY, srcZ);
                supportPlaced = com.mineagent.engine.act.Placement
                        .placeAnySupportBlock(sp, support);
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
        // Merely crossing y=dstY at the jump apex is not completion. Advancing
        // the path there used to leave the player unsupported in mid-air.
        return supportPlaced
                && com.mineagent.engine.pathing.util.BlockHelper.canStandOn(support)
                && sp.onGround() && isAtDestination(player);
    }

    @Override
    public net.minecraft.core.BlockPos requiredSupportPosition() {
        return new net.minecraft.core.BlockPos(srcX, srcY, srcZ);
    }
}
