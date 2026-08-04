package com.mineagent.engine.pathing.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Block state query helpers for the pathfinding system. Provides
 * static utility methods for checking block properties relevant
 * to movement calculations.
 */
public final class BlockHelper {

    private BlockHelper() {}

    /**
     * Check if a block is air (can walk through).
     */
    public static boolean isAir(BlockState state) {
        return state.isAir();
    }

    /**
     * Check if a block is solid (can stand on, can't walk through).
     */
    @SuppressWarnings("deprecation")
    public static boolean isSolid(BlockState state) {
        return state.isSolid();
    }

    /**
     * Check if a block can be stood on (has a solid top surface).
     */
    @SuppressWarnings("deprecation")
    public static boolean canStandOn(BlockState state) {
        return state != null && !isHazardous(state)
                && (state.isSolid() || state.is(Blocks.SCAFFOLDING));
    }

    /**
     * Check if a block is passable (the player can walk through it).
     * This includes air, tall grass, flowers, etc.
     */
    @SuppressWarnings("deprecation")
    public static boolean isPassable(BlockState state) {
        if (state == null) return false;
        if (state.isAir()) return true;
        if (isClimbable(state)) return true;
        if (isHazardous(state)) return false;
        // Solidity is not collision geometry: fences and closed doors can be
        // non-solid while still blocking the same player physics we execute.
        return state.isPathfindable(
                net.minecraft.world.level.pathfinder.PathComputationType.LAND)
                || state.getCollisionShape(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO).isEmpty();
    }

    /**
     * Check if a block is water.
     */
    public static boolean isWater(BlockState state) {
        return state != null && state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Check if a block is lava.
     */
    public static boolean isLava(BlockState state) {
        return state != null && state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    /**
     * Check if a block is any fluid.
     */
    public static boolean isFluid(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty();
    }

    /**
     * Check if a block is a liquid that harms the player (lava).
     */
    public static boolean isHarmfulFluid(BlockState state) {
        return isLava(state);
    }

    /** Vanilla climbable blocks plus scaffolding's vertical traversal. */
    public static boolean isClimbable(BlockState state) {
        return state != null && (state.is(net.minecraft.tags.BlockTags.CLIMBABLE)
                || state.is(Blocks.SCAFFOLDING));
    }

    /** Blocks and fluids that must not be treated as ordinary walkable space. */
    public static boolean isHazardous(BlockState state) {
        if (state == null) return true;
        return isHarmfulFluid(state)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW);
    }

    /** Whether a closed vanilla passage can be opened by an ordinary hand click. */
    public static boolean canOpenByHand(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.DoorBlock door) {
            return door.type().canOpenByHand()
                    && state.hasProperty(net.minecraft.world.level.block.DoorBlock.OPEN)
                    && !state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
        }
        if (block instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return state.hasProperty(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                    && !state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
        }
        return block instanceof net.minecraft.world.level.block.TrapDoorBlock
                && block != Blocks.IRON_TRAPDOOR
                && state.hasProperty(net.minecraft.world.level.block.TrapDoorBlock.OPEN)
                && !state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN);
    }

    /**
     * Check if a block can be broken by the companion (dig-through).
     * Excludes unbreakable blocks like bedrock and obsidian.
     */
    @SuppressWarnings("deprecation")
    public static boolean isBreakable(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) return false;
        Block block = state.getBlock();
        return block != Blocks.BEDROCK
                && block != Blocks.END_PORTAL
                && block != Blocks.END_PORTAL_FRAME
                && block != Blocks.END_GATEWAY
                && block != Blocks.COMMAND_BLOCK
                && block != Blocks.REPEATING_COMMAND_BLOCK
                && block != Blocks.CHAIN_COMMAND_BLOCK
                && block != Blocks.BARRIER
                && block != Blocks.STRUCTURE_BLOCK
                && block != Blocks.STRUCTURE_VOID
                && block != Blocks.JIGSAW;
    }

    /**
     * Check if a position has a solid block below it (can stand there).
     */
    public static boolean hasSolidBelow(Level level, int x, int y, int z) {
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        return canStandOn(below);
    }

    /**
     * Check if a 2-block-tall space is clear (player can stand there).
     */
    public static boolean isTwoBlockSpaceClear(BlockState feet, BlockState head) {
        return isPassable(feet) && isPassable(head);
    }

    /**
     * Calculate the horizontal distance between two positions.
     */
    public static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculate the 3D distance between two positions.
     */
    public static double distance3D(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate the squared horizontal distance (avoids sqrt).
     */
    public static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Check if the companion can place a block at the given position.
     * The position must be replaceable (air, water, etc).
     */
    public static boolean canPlaceBlockAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getFluidState().is(Fluids.WATER)
                || state.getFluidState().is(Fluids.EMPTY);
    }

    /**
     * Get the position as a center Vec3 for entity distance checks.
     */
    public static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
