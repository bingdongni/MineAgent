package com.mineagent.engine.act;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * Block and entity interaction — provides low-level Minecraft interaction
 * for right-clicking blocks, right-clicking entities, opening containers,
 * using items, and attacking entities.
 *
 * <p>Used by tools (InteractAtTool, InteractEntityTool, MeleeAttackTool,
 * RangedAttackTool) and survival chains.
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class Interaction {

    /** Maximum reach distance for interactions. */
    private static final double REACH_SURVIVAL = 4.5;
    private static final double REACH_CREATIVE = 5.0;

    private Interaction() {}

    // ── Block Interaction ─────────────────────────────────────────

    /**
     * Right-click (interact with) a block at the given position.
     *
     * <p>Uses the player's game mode handler to perform the interaction,
     * which handles all vanilla logic (container opening, item activation,
     * etc.).
     *
     * @param player the server player
     * @param pos    the block position to interact with
     * @param hand   the hand to use (MAIN_HAND or OFF_HAND)
     * @return the interaction result
     */
    public static InteractionResult interactBlock(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        try {
            if (player == null || pos == null || hand == null) return InteractionResult.PASS;

            var level = player.serverLevel();
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) return InteractionResult.PASS;

            // Check reach distance
            if (!isWithinReach(player, pos)) return InteractionResult.FAIL;

            // Create a hit result for the block
            Vec3 hitVec = Vec3.atCenterOf(pos);
            HitResult sight = level.clip(new ClipContext(player.getEyePosition(), hitVec,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (sight.getType() == HitResult.Type.BLOCK
                    && !((BlockHitResult) sight).getBlockPos().equals(pos)) {
                // Direct gameMode calls bypass the packet handler's ray
                // validation. Refuse interactions through an intervening wall.
                return InteractionResult.FAIL;
            }
            Vec3 towardPlayer = player.getEyePosition().subtract(hitVec);
            net.minecraft.core.Direction hitFace =
                    net.minecraft.core.Direction.getNearest(
                            towardPlayer.x, towardPlayer.y, towardPlayer.z);
            BlockHitResult hitResult = new BlockHitResult(
                    hitVec,
                    hitFace,
                    pos,
                    false
            );

            // Fake players have no client camera packet preceding the click.
            // Aim explicitly so nearby real clients see the action directed at
            // the same block that receives the server-side interaction.
            player.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);

            // Use the game mode handler — this handles containers, items, etc.
            ItemStack heldItem = player.getItemInHand(hand);
            InteractionResult result = player.gameMode.useItemOn(
                    player,
                    level,
                    heldItem,
                    hand,
                    hitResult
            );

            if (result == InteractionResult.PASS && !heldItem.isEmpty()) {
                // A client falls back to an untargeted item use when the
                // clicked block does not consume the action. Buckets, food,
                // bows and several modded items implement Item#use rather
                // than BlockItem#useOn; omitting this fallback made the same
                // right click work through CompanionInputDriver but fail via
                // interact_at and every utility that calls this method.
                result = player.gameMode.useItem(player, level, heldItem, hand);
            }

            // InteractionResult owns the swing decision in 1.21.1. Swinging
            // every right click makes charging bows, eating and failed clicks
            // look wrong; omitting it makes doors and usable blocks animate as
            // if they changed by themselves.
            if (result.shouldSwing()) player.swing(hand);

            return result;
        } catch (Exception e) {
            System.err.println("[MineAgent] Interaction.interactBlock error: " + e.getMessage());
            return InteractionResult.FAIL;
        }
    }

    // ── Entity Interaction ────────────────────────────────────────

    /**
     * Right-click (interact with) an entity.
     *
     * <p>Uses the player's interact method which handles all vanilla
     * logic (trading, riding, naming, etc.).
     *
     * @param player the server player
     * @param target the entity to interact with
     * @param hand   the hand to use
     * @return the interaction result
     */
    public static InteractionResult interactEntity(ServerPlayer player, Entity target, InteractionHand hand) {
        try {
            if (player == null || target == null || hand == null) return InteractionResult.PASS;
            if (!target.isAlive() || target == player) return InteractionResult.FAIL;

            // Check reach distance
            double dist = player.distanceToSqr(target);
            double reach = reachDistance(player);
            if (dist > reach * reach) return InteractionResult.FAIL;
            if (!player.hasLineOfSight(target)) return InteractionResult.FAIL;

            // Use the vanilla interact method
            player.lookAt(EntityAnchorArgument.Anchor.EYES, target,
                    EntityAnchorArgument.Anchor.EYES);
            InteractionResult result = player.interactOn(target, hand);
            if (result.shouldSwing()) player.swing(hand);
            return result;
        } catch (Exception e) {
            System.err.println("[MineAgent] Interaction.interactEntity error: " + e.getMessage());
            return InteractionResult.FAIL;
        }
    }

    // ── Container Opening ─────────────────────────────────────────

    /**
     * Open a container block (chest, furnace, barrel, etc.) at the
     * given position.
     *
     * <p>This is a convenience method that performs a right-click
     * interaction on the block and verifies that a container was
     * actually opened.
     *
     * @param player the server player
     * @param pos    the container block position
     * @return true if the container was opened
     */
    public static boolean openContainer(ServerPlayer player, BlockPos pos) {
        try {
            if (player == null || pos == null) return false;

            var level = player.serverLevel();
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) return false;
            if (!isWithinReach(player, pos)) return false;

            // Check if the block has a block entity that is a container
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return false;

            // Verify it's a container type
            boolean isContainer = be instanceof ChestBlockEntity
                    || be instanceof FurnaceBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity;

            if (!isContainer) return false;

            // Interact with the container
            InteractionResult result = interactBlock(player, pos, InteractionHand.MAIN_HAND);
            return result.consumesAction();
        } catch (Exception e) {
            System.err.println("[MineAgent] Interaction.openContainer error: " + e.getMessage());
            return false;
        }
    }

    // ── Item Use ──────────────────────────────────────────────────

    /**
     * Use the held item in the given hand (e.g., eat food, throw
     * snowball, use shield).
     *
     * <p>This performs a "use item" action without targeting a block
     * or entity.
     *
     * @param player the server player
     * @param hand   the hand containing the item to use
     * @return true if the item was successfully used
     */
    public static boolean useItem(ServerPlayer player, InteractionHand hand) {
        try {
            if (player == null || hand == null) return false;

            ItemStack heldItem = player.getItemInHand(hand);
            if (heldItem.isEmpty()) return false;

            // Use the game mode handler for item use
            InteractionResult result = player.gameMode.useItem(
                    player,
                    player.serverLevel(),
                    heldItem,
                    hand
            );

            if (result.shouldSwing()) player.swing(hand);

            return result.consumesAction();
        } catch (Exception e) {
            System.err.println("[MineAgent] Interaction.useItem error: " + e.getMessage());
            return false;
        }
    }

    // ── Attack ────────────────────────────────────────────────────

    /**
     * Left-click (attack) an entity.
     *
     * <p>Performs a melee attack on the target entity, respecting
     * attack cooldown and all vanilla combat mechanics.
     *
     * @param player the server player
     * @param target the entity to attack
     * @return true if the attack was performed
     */
    public static boolean attackEntity(ServerPlayer player, Entity target) {
        try {
            if (player == null || target == null) return false;
            if (!target.isAlive() || target == player) return false;
            if (!target.isAttackable()) return false;

            // Check reach distance (attack range is slightly shorter than interaction)
            double reach = reachDistance(player);
            double dist = player.distanceToSqr(target);
            // Entities have a bounding box, so add some tolerance
            double tolerance = target.getBoundingBox().getXsize() * 0.5;
            double effectiveReach = reach + tolerance;
            if (dist > effectiveReach * effectiveReach) return false;
            if (!player.hasLineOfSight(target)) return false;

            // A packet-driven player has already aimed before the attack
            // packet arrives. Fake players need the equivalent orientation and
            // swing here, otherwise damage is real but observers see a rigid
            // body. ServerPlayer#attack retains vanilla cooldown, enchantment,
            // knockback, durability and statistics behavior.
            player.lookAt(EntityAnchorArgument.Anchor.EYES, target,
                    EntityAnchorArgument.Anchor.EYES);
            player.attack(target);
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        } catch (Exception e) {
            System.err.println("[MineAgent] Interaction.attackEntity error: " + e.getMessage());
            return false;
        }
    }

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Check if a block position is within the player's reach distance.
     *
     * @param player the server player
     * @param pos    the block position to check
     * @return true if within reach
     */
    private static boolean isWithinReach(ServerPlayer player, BlockPos pos) {
        double reach = reachDistance(player);
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq <= reach * reach;
    }

    private static double reachDistance(ServerPlayer player) {
        return player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                ? fakeMode.getReachDistance()
                : (player.isCreative() ? REACH_CREATIVE : REACH_SURVIVAL);
    }
}
