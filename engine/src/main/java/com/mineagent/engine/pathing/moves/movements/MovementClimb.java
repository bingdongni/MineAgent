package com.mineagent.engine.pathing.moves.movements;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.pathing.moves.ActionCosts;
import com.mineagent.engine.pathing.moves.CalculationContext;
import com.mineagent.engine.pathing.moves.Input;
import com.mineagent.engine.pathing.moves.Movement;
import com.mineagent.engine.pathing.moves.MovementHelper;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Move one block vertically through a continuous ladder, vine or scaffolding
 * column. Unlike pillaring, climbing consumes no inventory and does not require
 * a solid block beneath every intermediate player node.
 */
public final class MovementClimb extends Movement {

    private final Input tickInput = new Input();
    private final boolean ascending;

    public MovementClimb(int x, int srcY, int z, int dstY) {
        super(x, srcY, z, x, dstY, z);
        this.ascending = dstY > srcY;
    }

    @Override
    public double calculateCost(CalculationContext ctx) {
        if (!MovementHelper.canClimb(ctx, srcX, srcY, srcZ, dstY)) {
            cost = Double.POSITIVE_INFINITY;
            return cost;
        }
        cost = ActionCosts.CLIMB;
        return cost;
    }

    @Override
    public Input getTickInput(AgentPlayer player) {
        tickInput.clear();
        var sp = TaskContext.serverPlayer(player);
        BlockState state = sp.level().getBlockState(sp.blockPosition());
        if (!BlockHelper.isClimbable(state)) {
            // The column changed after planning. A zero input lets the normal
            // movement timeout fail and replan without fabricating placement.
            return tickInput;
        }

        Direction attachment = attachmentDirection(state);
        if (attachment != null) {
            sp.setYRot(yawFor(attachment));
            sp.setXRot(ascending ? -20.0f : 25.0f);
            // Maintain contact with the thin ladder/vine collision plane;
            // jumping at block center alone does not engage climb physics.
            // On descent, forward collision would trigger vanilla's +0.2
            // ladder boost and move upward, so gravity must slide unaided.
            if (ascending) tickInput.forward(0.35f);
        }
        tickInput.jumping(ascending);
        // Scaffolding has a top collision floor; crouching is the vanilla
        // control that deliberately drops through it. Ladders/vines instead
        // descend by gravity and crouch would clamp the player in place.
        tickInput.sneaking(!ascending && state.is(Blocks.SCAFFOLDING));
        return tickInput;
    }

    @Override
    public boolean isFinished(AgentPlayer player) {
        int currentY = TaskContext.serverPlayer(player).blockPosition().getY();
        return ascending ? currentY >= dstY : currentY <= dstY;
    }

    private static Direction attachmentDirection(BlockState state) {
        if (state.hasProperty(LadderBlock.FACING)) {
            // Ladder FACING points away from its support block. Walk toward
            // the opposite side so horizontal collision keeps vanilla's
            // climb boost engaged instead of stepping out of the ladder cell.
            return state.getValue(LadderBlock.FACING).getOpposite();
        }
        if (state.hasProperty(VineBlock.NORTH) && state.getValue(VineBlock.NORTH)) {
            return Direction.NORTH;
        }
        if (state.hasProperty(VineBlock.SOUTH) && state.getValue(VineBlock.SOUTH)) {
            return Direction.SOUTH;
        }
        if (state.hasProperty(VineBlock.WEST) && state.getValue(VineBlock.WEST)) {
            return Direction.WEST;
        }
        if (state.hasProperty(VineBlock.EAST) && state.getValue(VineBlock.EAST)) {
            return Direction.EAST;
        }
        return null;
    }

    private static float yawFor(Direction direction) {
        return switch (direction) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }
}
