package com.mineagent.engine.pathing.moves;

import com.mineagent.engine.pathing.util.BlockHelper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Static helper methods for movement calculations. These methods check
 * whether a specific movement is possible given the world state and
 * calculate the associated costs.
 */
public final class MovementHelper {

    private MovementHelper() {}

    /**
     * Check if the companion can walk through a 2-block-tall space
     * at the given position (feet at y, head at y+1).
     */
    public static boolean canWalkThrough(CalculationContext ctx, int x, int y, int z) {
        BlockState feet = ctx.getBlockState(x, y, z);
        BlockState head = ctx.getBlockState(x, y + 1, z);
        if (feet == null || head == null) return false;
        return BlockHelper.isPassable(feet) && BlockHelper.isPassable(head);
    }

    /**
     * Check if the companion can stand on a block at (x, y, z).
     * The block at y-1 must be solid, and the 2-block space at y and y+1
     * must be passable.
     */
    public static boolean canStandOn(CalculationContext ctx, int x, int y, int z) {
        BlockState below = ctx.getBlockState(x, y - 1, z);
        if (below == null) return false;
        if (!BlockHelper.canStandOn(below)) return false;
        return canWalkThrough(ctx, x, y, z);
    }

    /**
     * Check whether a destination becomes standable after clearing only the
     * player's feet/head cells. This is deliberately separate from
     * {@link #canStandOn}: falls and parkour require an already-open landing,
     * while ordinary traversal may use the configured dig-through ability.
     */
    public static boolean canStandAfterBreaking(CalculationContext ctx, int x, int y, int z) {
        BlockState below = ctx.getBlockState(x, y - 1, z);
        BlockState feet = ctx.getBlockState(x, y, z);
        BlockState head = ctx.getBlockState(x, y + 1, z);
        if (below == null || feet == null || head == null) return false;
        boolean hasSupport = BlockHelper.canStandOn(below)
                // A ladder/vine node is supported by the climbable itself;
                // requiring a solid floor makes every elevated segment
                // unreachable from horizontal movement at the top/bottom.
                || BlockHelper.isClimbable(feet)
                || (ctx.allowBridge() && below.canBeReplaced());
        if (!hasSupport) return false;
        return canClear(ctx, feet) && canClear(ctx, head);
    }

    /** Cost of an optional support block below a planned destination. */
    public static double costOfSupport(CalculationContext ctx, int x, int y, int z) {
        return costOfSupport(ctx, x, y, z, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    /**
     * Cost of support placement with the predecessor node as a virtual anchor.
     * This lets A* model a straight multi-block bridge without pretending a
     * diagonal or disconnected block can be placed in mid-air.
     */
    public static double costOfSupport(CalculationContext ctx, int x, int y, int z,
                                       int srcX, int srcY, int srcZ) {
        BlockState below = ctx.getBlockState(x, y - 1, z);
        if (below == null) return Double.POSITIVE_INFINITY;
        if (BlockHelper.canStandOn(below)) return 0;
        if (!ctx.allowBridge() || !below.canBeReplaced()) return Double.POSITIVE_INFINITY;

        int supportY = y - 1;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockState neighbor = ctx.getBlockState(x + direction.getStepX(),
                    supportY + direction.getStepY(), z + direction.getStepZ());
            if (neighbor != null && !neighbor.canBeReplaced()
                    && !neighbor.getCollisionShape(
                            net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                            net.minecraft.core.BlockPos.ZERO).isEmpty()) {
                return ActionCosts.PLACE_BLOCK;
            }
        }

        long sourceSupportDistance = Math.abs((long) x - srcX)
                + Math.abs((long) supportY - (srcY - 1L))
                + Math.abs((long) z - srcZ);
        return sourceSupportDistance == 1L
                ? ActionCosts.PLACE_BLOCK : Double.POSITIVE_INFINITY;
    }

    private static boolean canClear(CalculationContext ctx, BlockState state) {
        return BlockHelper.isPassable(state)
                || BlockHelper.canOpenByHand(state)
                || (ctx.allowDigThrough() && BlockHelper.isBreakable(state));
    }

    /**
     * Check if the companion can stand on the block without checking
     * the 2-block-tall clearance (used for fall targets).
     */
    public static boolean hasGroundAt(CalculationContext ctx, int x, int y, int z) {
        BlockState below = ctx.getBlockState(x, y - 1, z);
        if (below == null) return false;
        return BlockHelper.canStandOn(below);
    }

    /**
     * Calculate the cost of breaking a block at the given position.
     * Returns the break cost if the block is breakable, or
     * Double.POSITIVE_INFINITY if it cannot be broken.
     */
    public static double costOfBreaking(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.getBlockState(x, y, z);
        if (state == null) return Double.POSITIVE_INFINITY;
        if (BlockHelper.isPassable(state)) return 0;
        // Wooden doors, trapdoors and fence gates need a real interaction,
        // but do not require dig-through permission or destroy the structure.
        if (BlockHelper.canOpenByHand(state)) return 1.0d;
        if (!ctx.allowDigThrough()) return Double.POSITIVE_INFINITY;
        if (!BlockHelper.isBreakable(state)) return Double.POSITIVE_INFINITY;
        return ActionCosts.BREAK_BLOCK;
    }

    /**
     * Calculate the cost of placing a block at the given position.
     * Returns the place cost if placement is possible, or
     * Double.POSITIVE_INFINITY if not.
     */
    public static double costOfPlacing(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.getBlockState(x, y, z);
        if (state == null) return Double.POSITIVE_INFINITY;
        if (BlockHelper.isAir(state) || BlockHelper.isWater(state)) {
            return ActionCosts.PLACE_BLOCK;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Check if the space above the given position is clear for jumping.
     * Need 3-block clearance: feet, head, and jump space.
     */
    public static boolean isClearForJump(CalculationContext ctx, int x, int y, int z) {
        BlockState feet = ctx.getBlockState(x, y, z);
        BlockState head = ctx.getBlockState(x, y + 1, z);
        BlockState jump = ctx.getBlockState(x, y + 2, z);
        if (feet == null || head == null || jump == null) return false;
        return BlockHelper.isPassable(feet) && BlockHelper.isPassable(head)
                && BlockHelper.isPassable(jump);
    }

    /**
     * Check if the companion can fall from (x, y, z) to (x, targetY, z).
     * All blocks between y and targetY+1 must be passable, and there
     * must be solid ground at targetY-1.
     */
    public static boolean canFallThrough(CalculationContext ctx, int x, int y, int z, int targetY) {
        // The union of a standing player's swept volume spans landing feet at
        // targetY through head height y+1 while stepping into the fall column.
        // Omitting y+1 planned drops whose entry was blocked at head height.
        for (int cy = targetY; cy <= y + 1; cy++) {
            BlockState state = ctx.getBlockState(x, cy, z);
            if (state == null) return false;
            if (!BlockHelper.isPassable(state)) return false;
        }
        return hasGroundAt(ctx, x, targetY, z);
    }

    /**
     * Find the ground level below the given position by searching
     * downward. Returns the Y where the player would stand, or
     * Integer.MIN_VALUE if no ground is found within the search range.
     */
    public static int findGroundBelow(CalculationContext ctx, int x, int y, int z, int minY) {
        for (int cy = y; cy >= minY; cy--) {
            BlockState below = ctx.getBlockState(x, cy - 1, z);
            if (below == null) continue;
            if (BlockHelper.canStandOn(below)) {
                // Check if the space above is clear
                BlockState feet = ctx.getBlockState(x, cy, z);
                BlockState head = ctx.getBlockState(x, cy + 1, z);
                if (feet != null && head != null
                        && BlockHelper.isPassable(feet) && BlockHelper.isPassable(head)) {
                    return cy;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Check if water is present at the given position (for MLG checks).
     */
    public static boolean isWaterAt(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.getBlockState(x, y, z);
        return state != null && BlockHelper.isWater(state);
    }

    /**
     * Check if lava is at the given position.
     */
    public static boolean isLavaAt(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.getBlockState(x, y, z);
        return state != null && BlockHelper.isLava(state);
    }

    /**
     * Get the walk cost modifier for a position. Water and lava add
     * extra cost penalties.
     */
    public static double getWalkCostModifier(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.getBlockState(x, y, z);
        if (state == null) return Double.POSITIVE_INFINITY;
        if (BlockHelper.isLava(state)) return ActionCosts.LAVA;
        if (BlockHelper.isWater(state)) return ActionCosts.SWIM;
        return 0;
    }

    /** Check whether a vertical edge is a continuous climbable segment. */
    public static boolean canClimb(CalculationContext ctx, int x, int fromY,
                                   int z, int toY) {
        if (Math.abs(toY - fromY) != 1) return false;
        BlockState from = ctx.getBlockState(x, fromY, z);
        BlockState to = ctx.getBlockState(x, toY, z);
        BlockState destinationHead = ctx.getBlockState(x, toY + 1, z);
        if (from == null || to == null || destinationHead == null) return false;
        // Requiring both segment cells avoids inventing a mid-air node one
        // block beyond the physical top/bottom of a ladder or vine.
        return BlockHelper.isClimbable(from)
                && BlockHelper.isClimbable(to)
                && BlockHelper.isPassable(destinationHead);
    }
}
