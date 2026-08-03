package com.mineagent.engine.act;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.material.Fluids;

/**
 * Block placing logic — provides low-level Minecraft interaction for
 * placing blocks. Used by tools (BuildTool) and movement maneuvers
 * (PlaceManeuver, MovementPillar, MovementTraverse bridging).
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class Placement {

    /** Prefer the normal ground face, then walls, and only then a ceiling. */
    private static final Direction[] SUPPORT_SEARCH_ORDER = {
            Direction.DOWN,
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
            Direction.UP
    };

    /** Maximum reach distance for placing blocks. */
    private static final double REACH_SURVIVAL = 4.5;
    private static final double REACH_CREATIVE = 5.0;

    private Placement() {}

    /** Result of a verified vanilla placement attempt. */
    public record Attempt(boolean placed, String reason) {
        private static Attempt success() { return new Attempt(true, "placed"); }
        private static Attempt failure(String reason) { return new Attempt(false, reason); }
    }

    // ── Primary API ───────────────────────────────────────────────

    /**
     * Place a block at the given position.
     *
     * <p>The method checks:
     * <ul>
     *   <li>Position is valid for placement (replaceable)</li>
     *   <li>Player has the block in inventory (or is in creative)</li>
     *   <li>Position is within reach distance</li>
     * </ul>
     *
     * <p>In survival mode, one block is consumed from the player's inventory.
     *
     * @param player the server player placing the block
     * @param pos    the target position
     * @param state  the block state to place
     * @return true if the block was successfully placed
     */
    public static boolean placeBlock(ServerPlayer player, BlockPos pos, BlockState state) {
        try {
            if (player == null || pos == null || state == null) return false;

            ServerLevel level = player.serverLevel();

            if (pos.getY() < level.getMinBuildHeight()
                    || pos.getY() >= level.getMaxBuildHeight()
                    || !level.getWorldBorder().isWithinBounds(pos)) {
                return false;
            }

            // Check if the position is valid for placement
            if (!canPlaceAt(level, pos, state)) return false;
            if (!hasPlacementAnchor(level, pos)) return false;
            // Direct support placement is intentionally deterministic for
            // bridge/pillar execution, but it must still obey vanilla world
            // survival and entity-collision constraints.
            if (!state.canSurvive(level, pos)
                    || !level.isUnobstructed(state, pos, CollisionContext.of(player))) {
                return false;
            }

            // Check reach distance
            if (!isWithinReach(player, pos)) return false;
            if (!level.mayInteract(player, pos)
                    || player.blockActionRestricted(
                            level, pos, player.gameMode.getGameModeForPlayer())) {
                return false;
            }

            Block block = state.getBlock();
            InteractionHand placementHand = InteractionHand.MAIN_HAND;

            // Check inventory and consume in survival mode
            if (!player.isCreative()) {
                int inventorySlot = findBlockInventorySlot(player, block);
                if (inventorySlot < 0) return false;
                placementHand = prepareBlockInHand(player, inventorySlot);
            }

            // Direct setBlock is reserved for path scaffolding where a normal
            // useItemOn ray cannot address the cell below/in front of a moving
            // player reliably. Reproduce the observable parts a client would
            // otherwise supply: aim at the target while visibly holding the
            // consumed block, then swing and play the vanilla placement sound.
            player.lookAt(EntityAnchorArgument.Anchor.EYES, pos.getCenter());
            boolean placed = level.setBlock(pos, state, Block.UPDATE_ALL);

            if (placed && !player.isCreative()) {
                // Consume one block from inventory
                consumeBlockFromInventory(player, block);
                // setBlock bypasses the normal menu interaction path, so no
                // vanilla packet handler marks the fake player's inventory.
                player.getInventory().setChanged();
                player.inventoryMenu.broadcastChanges();
                if (player.containerMenu != player.inventoryMenu) {
                    player.containerMenu.broadcastChanges();
                }
            }

            if (placed) {
                player.swing(placementHand);
                var sound = state.getSoundType();
                level.playSound(null, pos, sound.getPlaceSound(),
                        SoundSource.BLOCKS,
                        (sound.getVolume() + 1.0f) / 2.0f,
                        sound.getPitch() * 0.8f);
            }

            return placed;
        } catch (Exception e) {
            System.err.println("[MineAgent] Placement.placeBlock error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Place any inventory block suitable as a walking support.
     * Falling blocks are excluded because a bridge block placed over a void
     * would immediately fall and leave the planned edge physically absent.
     */
    public static boolean placeAnySupportBlock(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!isCarriedInventorySlot(i)) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) continue;
            Block block = item.getBlock();
            BlockState state = block.defaultBlockState();
            if (!isSafeSupportMaterial(block, state)) continue;
            if (placeBlock(player, pos, state)) return true;
        }
        return false;
    }

    /**
     * Place the block currently held in {@code hand} at an exact world cell.
     *
     * <p>This uses BlockItem/useItemOn rather than setBlock, so orientation,
     * entity collision, item consumption, sounds, criteria and mod hooks all
     * retain vanilla behavior. Success is based on the postcondition at
     * {@code target}; an accepted method call alone is not evidence that the
     * requested world change happened.
     */
    public static Attempt placeHeldBlock(ServerPlayer player, BlockPos target,
                                         InteractionHand hand) {
        if (player == null || target == null || hand == null) {
            return Attempt.failure("invalid placement arguments");
        }

        ServerLevel level = player.serverLevel();
        if (target.getY() < level.getMinBuildHeight()
                || target.getY() >= level.getMaxBuildHeight()) {
            return Attempt.failure("target is outside build height");
        }
        if (!level.getWorldBorder().isWithinBounds(target)) {
            return Attempt.failure("target is outside the world border");
        }
        if (!isWithinReach(player, target)) {
            return Attempt.failure("target is outside interaction reach");
        }
        if (!level.mayInteract(player, target)
                || player.blockActionRestricted(
                        level, target, player.gameMode.getGameModeForPlayer())) {
            return Attempt.failure("target is protected from interaction");
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            return Attempt.failure("selected hand does not hold a block item");
        }
        Block expectedBlock = blockItem.getBlock();
        BlockState existing = level.getBlockState(target);
        if (existing.is(expectedBlock)) return Attempt.success();
        if (!existing.isAir() && !existing.canBeReplaced()) {
            return Attempt.failure("target contains a non-replaceable block");
        }

        String lastFailure = "no visible solid support face";
        for (Direction fromTarget : SUPPORT_SEARCH_ORDER) {
            BlockPos support = target.relative(fromTarget);
            BlockState supportState = level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()
                    || supportState.getCollisionShape(level, support).isEmpty()) {
                continue;
            }
            if (!player.canInteractWithBlock(support, 1.0)) {
                lastFailure = "support face is outside interaction reach";
                continue;
            }

            Direction face = fromTarget.getOpposite();
            Vec3 faceCenter = Vec3.atCenterOf(support).add(
                    face.getStepX() * 0.5,
                    face.getStepY() * 0.5,
                    face.getStepZ() * 0.5);

            // A ray ending exactly on an AABB boundary may be reported as MISS
            // because the endpoint is excluded by floating-point clipping.
            // Trace a tiny distance into the support, while keeping the actual
            // click location on its face as a real client packet would.
            Vec3 rayEnd = faceCenter.subtract(
                    face.getStepX() / 64.0,
                    face.getStepY() / 64.0,
                    face.getStepZ() / 64.0);
            var sight = level.clip(new net.minecraft.world.level.ClipContext(
                    player.getEyePosition(), rayEnd,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (sight.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                    || !((net.minecraft.world.phys.BlockHitResult) sight)
                            .getBlockPos().equals(support)) {
                lastFailure = "support face is occluded";
                continue;
            }

            player.lookAt(EntityAnchorArgument.Anchor.EYES, faceCenter);
            var hit = new net.minecraft.world.phys.BlockHitResult(
                    faceCenter, face, support, false);
            boolean wasSneaking = player.isShiftKeyDown();
            InteractionResult result;
            player.setShiftKeyDown(true);
            try {
                result = player.gameMode.useItemOn(
                        player, level, player.getItemInHand(hand), hand, hit);
            } finally {
                player.setShiftKeyDown(wasSneaking);
            }

            if (result.shouldSwing()) player.swing(hand);
            if (level.getBlockState(target).is(expectedBlock)) {
                com.mineagent.engine.task.TaskContext.syncInventory(player);
                return Attempt.success();
            }

            lastFailure = "vanilla useItemOn returned " + result
                    + " without placing the requested block";
            if (result.consumesAction()) break;
        }
        return Attempt.failure(lastFailure);
    }

    private static boolean hasPlacementAnchor(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.canBeReplaced()
                    && !neighborState.getCollisionShape(level, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True when bridge execution has at least one safe support material. */
    public static boolean hasSupportBlock(ServerPlayer player) {
        return supportBlockCount(player) > 0;
    }

    /** Count all safe support items available to the current planned path. */
    public static int supportBlockCount(ServerPlayer player) {
        if (player == null) return 0;
        var inv = player.getInventory();
        long count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!isCarriedInventorySlot(i)) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) continue;
            Block block = item.getBlock();
            if (isSafeSupportMaterial(block, block.defaultBlockState())) {
                count += stack.getCount();
                if (count >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) count;
    }

    /**
     * Check if a block state can be placed at the given position.
     *
     * <p>A position is valid for placement if the existing block is
     * replaceable (air, water, tall grass, etc.).
     *
     * @param level the server level
     * @param pos   the target position
     * @param state the block state to place (unused for validation, but kept for API consistency)
     * @return true if the position is valid for placement
     */
    public static boolean canPlaceAt(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            if (level == null || pos == null) return false;

            BlockState existing = level.getBlockState(pos);

            // Air is always replaceable
            if (existing.isAir()) return true;

            // Fluids are replaceable (water, lava)
            if (!existing.getFluidState().isEmpty()) return true;

            // Some blocks are replaceable (tall grass, snow layers, etc.)
            if (existing.canBeReplaced()) return true;

            return false;
        } catch (Exception e) {
            System.err.println("[MineAgent] Placement.canPlaceAt error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the appropriate block state for placement, considering
     * contextual placement rules (direction, neighboring blocks, etc.).
     *
     * <p>For blocks with directional placement (stairs, slabs, etc.),
     * this returns the state with the correct orientation based on
     * the player's facing direction and the placement face.
     *
     * @param block  the block to place
     * @param player the server player (used for facing direction)
     * @param pos    the target position
     * @param face   the face the block is being placed against
     * @return the block state for placement, or the default state if
     *         contextual placement is not supported
     */
    public static BlockState getPlacementState(Block block, ServerPlayer player,
                                                BlockPos pos, Direction face) {
        try {
            if (block == null) return null;

            // For blocks with a state definition, try to get contextual placement
            // Most simple blocks just use default state
            BlockState defaultState = block.defaultBlockState();

            // Directional blocks: orient based on player facing
            // Stairs, furnaces, observers, etc. use horizontal facing
            var properties = defaultState.getProperties();

            // Check if the block has a FACING property (horizontal)
            for (var prop : properties) {
                if (prop.getName().equals("facing") && prop.getValueClass() == Direction.class) {
                    @SuppressWarnings("unchecked")
                    net.minecraft.world.level.block.state.properties.DirectionProperty facingProp =
                            (net.minecraft.world.level.block.state.properties.DirectionProperty) prop;
                    Direction playerFacing = player.getDirection();
                    if (facingProp.getPossibleValues().contains(playerFacing)) {
                        return defaultState.setValue(facingProp, playerFacing);
                    }
                }

                // Check for HORIZONTAL_FACING (furnaces, etc.)
                if (prop.getName().equals("facing") && prop.getValueClass() == Direction.class) {
                    @SuppressWarnings("unchecked")
                    net.minecraft.world.level.block.state.properties.DirectionProperty facingProp =
                            (net.minecraft.world.level.block.state.properties.DirectionProperty) prop;
                    Direction horizontalFacing = player.getDirection();
                    // Some blocks only accept horizontal directions
                    if (facingProp.getPossibleValues().contains(horizontalFacing)) {
                        return defaultState.setValue(facingProp, horizontalFacing);
                    }
                }
            }

            return defaultState;
        } catch (Exception e) {
            System.err.println("[MineAgent] Placement.getPlacementState error: " + e.getMessage());
            return block != null ? block.defaultBlockState() : null;
        }
    }

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Check if the player has the given block in their inventory.
     *
     * @param player the server player
     * @param block  the block to check for
     * @return true if at least one is present
     */
    private static int findBlockInventorySlot(ServerPlayer player, Block block) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!isCarriedInventorySlot(i)) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Make the material used by direct path placement visible in a real hand.
     * Main-inventory materials are swapped into the selected hotbar slot; the
     * previous selected stack is preserved, so this cannot duplicate or delete
     * inventory contents. Slot 40 is already the offhand and needs no swap.
     */
    private static InteractionHand prepareBlockInHand(ServerPlayer player, int slot) {
        var inv = player.getInventory();
        if (slot == 40) return InteractionHand.OFF_HAND;

        if (slot < 9) {
            inv.selected = slot;
        } else {
            int selected = inv.selected;
            ItemStack previous = inv.getItem(selected);
            inv.setItem(selected, inv.getItem(slot));
            inv.setItem(slot, previous);
        }
        inv.setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
        return InteractionHand.MAIN_HAND;
    }

    /**
     * Consume one block from the player's inventory.
     *
     * <p>Searches the inventory for the matching block item and
     * shrinks the stack by 1.
     *
     * @param player the server player
     * @param block  the block to consume
     */
    private static void consumeBlockFromInventory(ServerPlayer player, Block block) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!isCarriedInventorySlot(i)) continue;
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                return;
            }
        }
    }

    /**
     * Check if a block position is within the player's reach distance.
     *
     * @param player the server player
     * @param pos    the block position to check
     * @return true if within reach
     */
    private static boolean isWithinReach(ServerPlayer player, BlockPos pos) {
        double reach = player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                ? fakeMode.getReachDistance()
                : (player.isCreative() ? REACH_CREATIVE : REACH_SURVIVAL);
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq <= reach * reach;
    }

    private static boolean isCarriedInventorySlot(int slot) {
        // Inventory also exposes armor at 36-39. Path construction must never
        // tear down equipped gear to obtain a bridge material; offhand 40 is a
        // normal carried slot and is safe to consume.
        return slot >= 0 && (slot < 36 || slot == 40);
    }

    private static boolean isSafeSupportMaterial(Block block, BlockState state) {
        if (block instanceof net.minecraft.world.level.block.FallingBlock
                || block instanceof net.minecraft.world.level.block.EntityBlock) {
            return false;
        }
        // Direct setBlock is used only for structural movement support. Limit
        // it to full collision cubes so slabs, fences, interactive block
        // entities, and orientation-sensitive blocks do not create a support
        // whose actual standing height/state disagrees with the A* node.
        return state.isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
