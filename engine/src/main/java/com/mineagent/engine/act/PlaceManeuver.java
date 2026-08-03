package com.mineagent.engine.act;

import com.mineagent.api.entity.InputDriver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Complex placement maneuvers — higher-level block placement operations
 * used by movement execution (MovementPillar, MovementTraverse) and
 * survival chains.
 *
 * <p>These methods coordinate multiple low-level actions (place + jump,
 * sneak + place + walk) to perform building tasks like pillaring up
 * or bridging across gaps.
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class PlaceManeuver {

    /** Maximum number of blocks to place in a single tower/bridge call. */
    private static final int MAX_BLOCKS_PER_CALL = 64;

    private PlaceManeuver() {}

    // ── Scaffold / Below / Above ──────────────────────────────────

    /**
     * Place a scaffold block beneath the player (for bridging).
     * Uses scaffolding block if available, otherwise dirt/cobblestone.
     *
     * @param player the server player
     * @return true if a block was placed below
     */
    public static boolean placeScaffoldBelow(ServerPlayer player) {
        try {
            if (player == null) return false;

            BlockPos below = player.blockPosition().below();
            Block scaffold = Blocks.SCAFFOLDING;

            // Try scaffolding first. canPlaceAt alone only describes the world
            // cell; it says nothing about inventory. The old branch returned
            // false immediately when the cell was valid but no scaffolding was
            // carried, so the documented dirt fallback was dead in the common
            // case.
            BlockState state = scaffold.defaultBlockState();
            if (Placement.placeBlock(player, below, state)) return true;

            // Fallback to dirt
            return placeBlockBelow(player, Blocks.DIRT);
        } catch (Exception e) {
            System.err.println("[MineAgent] PlaceManeuver.placeScaffoldBelow error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Place a block at feet level - 1 (one block below the player's feet).
     *
     * @param player the server player
     * @param block  the block type to place
     * @return true if the block was placed
     */
    public static boolean placeBlockBelow(ServerPlayer player, Block block) {
        try {
            if (player == null || block == null) return false;

            BlockPos below = player.blockPosition().below();
            BlockState state = Placement.getPlacementState(block, player, below, Direction.UP);
            if (state == null) state = block.defaultBlockState();

            return Placement.placeBlock(player, below, state);
        } catch (Exception e) {
            System.err.println("[MineAgent] PlaceManeuver.placeBlockBelow error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Place a block at head level + 1 (one block above the player's head).
     *
     * @param player the server player
     * @param block  the block type to place
     * @return true if the block was placed
     */
    public static boolean placeBlockAbove(ServerPlayer player, Block block) {
        try {
            if (player == null || block == null) return false;

            // Head is at feet + 1, so above head is feet + 2
            BlockPos above = player.blockPosition().above(2);
            BlockState state = Placement.getPlacementState(block, player, above, Direction.DOWN);
            if (state == null) state = block.defaultBlockState();

            return Placement.placeBlock(player, above, state);
        } catch (Exception e) {
            System.err.println("[MineAgent] PlaceManeuver.placeBlockAbove error: " + e.getMessage());
            return false;
        }
    }

    // ── Tower Up (Pillar) ─────────────────────────────────────────

    /**
     * Pillar up — place a block below and jump on top of it, repeating
     * until the player has risen by one block. Used by MovementPillar
     * execution.
     *
     * <p>The sequence per block is:
     * <ol>
     *   <li>Place block at feet - 1</li>
     *   <li>Jump</li>
     *   <li>Wait for landing</li>
     * </ol>
     *
     * <p>This method performs a single step (one block placement + jump).
     * The caller should invoke this repeatedly in a tick loop.
     *
     * @param player the server player
     * @param input  the input driver for jump control
     * @return true if a block was placed this tick
     */
    public static boolean towerUp(ServerPlayer player, InputDriver input) {
        try {
            if (player == null || input == null) return false;

            BlockPos below = player.blockPosition().below();
            var level = player.serverLevel();
            BlockState existingBelow = level.getBlockState(below);

            // If there's already a solid block below, just jump
            if (existingBelow.isSolidRender(level, below)) {
                input.setJumping(true);
                return true;
            }

            // Place a block below
            boolean placed = placeBlockBelow(player, Blocks.COBBLESTONE);
            if (!placed) {
                // Try dirt as fallback
                placed = placeBlockBelow(player, Blocks.DIRT);
            }

            if (placed) {
                input.setJumping(true);
            }

            return placed;
        } catch (Exception e) {
            System.err.println("[MineAgent] PlaceManeuver.towerUp error: " + e.getMessage());
            return false;
        }
    }

    // ── Bridge Forward ────────────────────────────────────────────

    /**
     * Bridge across a gap — sneak to the edge, place a block beneath,
     * and walk forward. Used by MovementTraverse when bridging is needed.
     *
     * <p>The sequence per block is:
     * <ol>
     *   <li>Enable sneak (prevent falling off edge)</li>
     *   <li>Place block at feet - 1 (the edge position)</li>
     *   <li>Walk forward one block</li>
     *   <li>Repeat</li>
     * </ol>
     *
     * <p>This method performs a single step of the bridge maneuver.
     * The caller should invoke this in a tick loop, advancing forward
     * each time a block is placed.
     *
     * @param player the server player
     * @param input  the input driver for sneak/walk control
     * @param dir    the horizontal direction to bridge towards
     * @return true if a block was placed this tick
     */
    public static boolean bridgeForward(ServerPlayer player, InputDriver input, Direction dir) {
        try {
            if (player == null || input == null || dir == null) return false;

            // Must be a horizontal direction
            if (dir.getAxis() == Direction.Axis.Y) return false;

            // Enable sneaking to prevent falling off the edge
            input.setSneaking(true);

            // Calculate the block position to place: one below the position
            // in front of the player (in the bridging direction)
            BlockPos playerPos = player.blockPosition();
            BlockPos ahead = playerPos.relative(dir);
            BlockPos placePos = ahead.below();

            var level = player.serverLevel();
            BlockState existing = level.getBlockState(placePos);

            // If there's already a solid block there, just walk forward
            if (existing.isSolidRender(level, placePos)) {
                setMovementInput(input, dir);
                return true;
            }

            // Place a block at the edge position
            Block block = Blocks.COBBLESTONE;
            BlockState state = Placement.getPlacementState(block, player, placePos, Direction.UP);
            if (state == null) state = block.defaultBlockState();

            boolean placed = Placement.placeBlock(player, placePos, state);
            if (!placed) {
                // Fallback to dirt
                block = Blocks.DIRT;
                state = Placement.getPlacementState(block, player, placePos, Direction.UP);
                if (state == null) state = block.defaultBlockState();
                placed = Placement.placeBlock(player, placePos, state);
            }

            if (placed) {
                // Walk forward in the bridging direction
                setMovementInput(input, dir);
            }

            return placed;
        } catch (Exception e) {
            System.err.println("[MineAgent] PlaceManeuver.bridgeForward error: " + e.getMessage());
            return false;
        }
    }

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Set movement input for walking in a cardinal direction.
     *
     * @param input the input driver
     * @param dir   the horizontal direction
     */
    private static void setMovementInput(InputDriver input, Direction dir) {
        input.setSneaking(true); // Keep sneaking during bridge
        switch (dir) {
            case NORTH -> input.setForward(1.0f);
            case SOUTH -> input.setForward(-1.0f);
            case WEST -> input.setStrafe(1.0f);
            case EAST -> input.setStrafe(-1.0f);
            default -> {} // Should not happen (filtered above)
        }
    }
}
