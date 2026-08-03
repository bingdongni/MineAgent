package com.mineagent.engine.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks blocks using vanilla server game mode mechanics.
 * Used by mining and building tasks to destroy blocks.
 *
 * <p>Before breaking, {@link #selectBestTool} automatically switches
 * the companion's held slot to the fastest suitable tool in the
 * hotbar (or swaps one in from the main inventory). This ensures
 * correct drops and maximum break speed without requiring the LLM
 * to explicitly call {@code equip_item} before every mining action.
 */
public final class BlockDigger {

    private BlockDigger() {}

    /**
     * Attempt to break a block at the given position. Automatically
     * selects the best tool from inventory before breaking.
     *
     * @param player the companion's ServerPlayer
     * @param pos    the block position to break
     * @return true if the block was successfully broken
     */
    public static boolean breakBlock(ServerPlayer player, BlockPos pos) {
        if (!startBreaking(player, pos)) return false;
        return player.level().getBlockState(pos).isAir();
    }

    /**
     * Start (or continue) vanilla's server-side block-breaking state machine.
     *
     * <p>Calling {@code gameMode.destroyBlock()} directly made every task an
     * instant miner even when {@code instantBreak=false}. START_DESTROY_BLOCK
     * records the target and lets {@code ServerPlayerGameMode.tick()} apply
     * hardness, tool speed, effects, exhaustion, drops, and durability. The
     * fake game mode still completes immediately when instant break is enabled.
     */
    public static boolean startBreaking(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !canBreak(player, pos)) return false;
        BlockState state = player.level().getBlockState(pos);
        prepareBestTool(player, state);
        player.gameMode.handleBlockBreakAction(pos,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                net.minecraft.core.Direction.UP,
                player.serverLevel().getMaxBuildHeight(), 0);
        return true;
    }

    /** Abort a progressive break so cancellation cannot leave gameMode busy. */
    public static void abortBreaking(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return;
        player.gameMode.handleBlockBreakAction(pos,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                net.minecraft.core.Direction.UP,
                player.serverLevel().getMaxBuildHeight(), 0);
    }

    /**
     * Auto-select the best tool for the target block.
     *
     * <p>Strategy (lowest cost first):
     * <ol>
     *   <li>Scan the 9 hotbar slots — if a better tool is found,
     *       just switch {@code inv.selected} (zero inventory mutation).</li>
     *   <li>If no suitable tool is in the hotbar, scan the main
     *       inventory (slots 9-35) and <b>swap</b> the best tool into
     *       the current hotbar slot (preserves inventory layout).</li>
     * </ol>
     *
     * <p>"Best" is determined by:
     * <ol>
     *   <li>{@link ItemStack#isCorrectToolForDrops} — a correct tool
     *       always beats an incorrect one (stone drops nothing with
     *       a bare hand).</li>
     *   <li>{@link ItemStack#getDestroySpeed} — among tools with the
     *       same correctness, the faster one wins.</li>
     * </ol>
     *
     * <p>If the current held item is already the best option, no
     * switch happens — this avoids unnecessary slot changes.
     */
    public static void prepareBestTool(ServerPlayer player, BlockState state) {
        var inv = player.getInventory();
        int currentSlot = inv.selected;
        ItemStack currentStack = inv.getItem(currentSlot);
        boolean currentUsable = hasSafeDurability(currentStack);

        // Phase 1: scan hotbar (slots 0-8) — switch selection only
        // Empty hand is the baseline so a nearly broken selected tool cannot
        // win simply because it was held before the mining action started.
        int bestSlot = currentUsable ? currentSlot : -1;
        float bestSpeed = currentUsable && !currentStack.isEmpty()
                ? currentStack.getDestroySpeed(state) : 1.0f;
        boolean bestCorrect = currentUsable && !currentStack.isEmpty()
                && currentStack.isCorrectToolForDrops(state);

        int limit = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !hasSafeDurability(stack)) continue;

            float speed = stack.getDestroySpeed(state);
            boolean correct = stack.isCorrectToolForDrops(state);

            if (correct && !bestCorrect) {
                // Correct tool found — always prefer over incorrect
                bestSlot = i;
                bestSpeed = speed;
                bestCorrect = true;
            } else if (correct == bestCorrect && speed > bestSpeed) {
                // Same correctness tier but faster
                bestSlot = i;
                bestSpeed = speed;
            }
        }

        if (bestSlot >= 0 && bestSlot < 9 && bestSlot != currentSlot) {
            inv.selected = bestSlot;
            inv.setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }

        // Phase 2: if current tool is not correct, try to find one
        // in the main inventory (slots 9-35) and swap it in.
        if (bestSlot >= 9) {
            // A real swap preserves both stacks and cannot duplicate items.
            ItemStack temp = inv.getItem(currentSlot);
            inv.setItem(currentSlot, inv.getItem(bestSlot));
            inv.setItem(bestSlot, temp);
            TaskContext.syncInventory(player);
            return;
        }

        if (bestSlot < 0 && !currentUsable) {
            // Use a real empty hotbar slot when no tool beats bare hands.
            for (int i = 0; i < Math.min(9, inv.getContainerSize()); i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.selected = i;
                    inv.setChanged();
                    player.inventoryMenu.broadcastChanges();
                    return;
                }
            }
        }
    }

    private static boolean hasSafeDurability(ItemStack stack) {
        return !stack.isDamageableItem()
                || stack.getMaxDamage() - stack.getDamageValue() > 1;
    }

    /**
     * Estimate a local timeout from vanilla's per-tick destroy progress.
     * Fixed limits incorrectly reject hard but legitimate blocks such as
     * obsidian even while the server-side break state is advancing normally.
     */
    public static int expectedBreakTicks(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !canBreak(player, pos)) {
            return Integer.MAX_VALUE;
        }
        BlockState state = player.level().getBlockState(pos);
        prepareBestTool(player, state);
        float progress = state.getDestroyProgress(player, player.level(), pos);
        if (!Float.isFinite(progress) || progress <= 0.0f) {
            return Integer.MAX_VALUE;
        }
        long vanillaTicks = (long) Math.ceil(1.0d / progress);
        long margin = Math.max(40L, vanillaTicks / 2L);
        return (int) Math.min(12_000L, vanillaTicks + margin);
    }

    /**
     * Check if the companion can break the block at the given position.
     * Verifies the block exists, is not air, and is within reach distance.
     *
     * @param player the companion's ServerPlayer
     * @param pos    the block position to check
     * @return true if the block can be broken
     */
    public static boolean canBreak(ServerPlayer player, BlockPos pos) {
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getDestroySpeed(player.level(), pos) < 0) return false;

        // Use eye-to-center distance, matching vanilla's interaction check.
        // blockPosition().distSqr used integer feet coordinates and produced
        // false accept/reject results near the edge of reach.
        double reach = player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                ? fakeMode.getReachDistance() : player.blockInteractionRange();
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > reach * reach) return false;

        // Calling ServerPlayerGameMode directly skips the packet listener's
        // hit validation. Require the requested block to be the first solid
        // block on the eye-to-center ray so mining tasks and path clearance
        // cannot destroy terrain through an intervening wall.
        var sight = player.level().clip(new net.minecraft.world.level.ClipContext(
                player.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return sight.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && ((net.minecraft.world.phys.BlockHitResult) sight)
                        .getBlockPos().equals(pos);
    }

    /**
     * Get the tool durability remaining for the companion's current main hand item.
     *
     * @param player the companion's ServerPlayer
     * @return remaining durability, or Integer.MAX_VALUE if not a damageable item
     */
    public static int toolDurability(ServerPlayer player) {
        var item = player.getMainHandItem();
        // Empty hand is not a nearly-broken tool. Returning zero caused every
        // mining task without a held tool to abort before it could mine dirt,
        // wood, or select a suitable tool from another slot.
        if (item.isEmpty()) return Integer.MAX_VALUE;
        if (!item.isDamageableItem()) return Integer.MAX_VALUE;
        return item.getMaxDamage() - item.getDamageValue();
    }
}
