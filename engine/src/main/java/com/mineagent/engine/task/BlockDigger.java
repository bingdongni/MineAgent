package com.mineagent.engine.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Executes vanilla block breaking and selects a suitable real inventory tool.
 */
public final class BlockDigger {

    private BlockDigger() {}

    /**
     * Compatibility entry point. Progressive blocks usually remain present
     * after the first call, so callers that need completion must keep ticking.
     */
    public static boolean breakBlock(ServerPlayer player, BlockPos pos) {
        if (!startBreaking(player, pos)) return false;
        return player.level().getBlockState(pos).isAir();
    }

    /**
     * Start or continue vanilla's progressive block-breaking state machine.
     * Direct destroyBlock calls bypass hardness, animation, exhaustion, tool
     * durability and normal timing, which makes a fake player an instant miner.
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

    /** Abort a progressive break so cancellation cannot damage a stale target. */
    public static void abortBreaking(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return;
        player.gameMode.handleBlockBreakAction(pos,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                net.minecraft.core.Direction.UP,
                player.serverLevel().getMaxBuildHeight(), 0);
    }

    /**
     * Select the fastest safe tool. Inventory moves are swaps, never copies,
     * and every mutation is broadcast so clients see the held item change.
     */
    public static void prepareBestTool(ServerPlayer player, BlockState state) {
        var inventory = player.getInventory();
        int currentSlot = inventory.selected;
        ItemStack current = inventory.getItem(currentSlot);
        boolean currentUsable = hasSafeDurability(current);

        int bestSlot = currentUsable ? currentSlot : -1;
        float bestSpeed = currentUsable && !current.isEmpty()
                ? current.getDestroySpeed(state) : 1.0f;
        boolean bestCorrect = currentUsable && !current.isEmpty()
                && current.isCorrectToolForDrops(state);

        int limit = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate.isEmpty() || !hasSafeDurability(candidate)) continue;
            float speed = candidate.getDestroySpeed(state);
            boolean correct = candidate.isCorrectToolForDrops(state);
            if ((correct && !bestCorrect) || (correct == bestCorrect && speed > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = speed;
                bestCorrect = correct;
            }
        }

        if (bestSlot >= 0 && bestSlot < 9 && bestSlot != currentSlot) {
            inventory.selected = bestSlot;
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
            return;
        }

        if (bestSlot >= 9) {
            ItemStack displaced = inventory.getItem(currentSlot);
            inventory.setItem(currentSlot, inventory.getItem(bestSlot));
            inventory.setItem(bestSlot, displaced);
            TaskContext.syncInventory(player);
            return;
        }

        if (bestSlot < 0 && !currentUsable) {
            for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.selected = slot;
                    inventory.setChanged();
                    player.inventoryMenu.broadcastChanges();
                    return;
                }
            }
        }
    }

    private static boolean hasSafeDurability(ItemStack stack) {
        return stack != null && (!stack.isDamageableItem()
                || stack.getMaxDamage() - stack.getDamageValue() > 1);
    }

    /** Derive a bounded timeout from vanilla destroy progress. */
    public static int expectedBreakTicks(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !canBreak(player, pos)) {
            return Integer.MAX_VALUE;
        }
        BlockState state = player.level().getBlockState(pos);
        prepareBestTool(player, state);
        float progress = state.getDestroyProgress(player, player.level(), pos);
        if (!Float.isFinite(progress) || progress <= 0.0f) return Integer.MAX_VALUE;
        long vanillaTicks = (long) Math.ceil(1.0d / progress);
        long margin = Math.max(40L, vanillaTicks / 2L);
        return (int) Math.min(12_000L, vanillaTicks + margin);
    }

    /** Validate reach and line of sight before bypassing the packet listener. */
    public static boolean canBreak(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        BlockState state = player.level().getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(player.level(), pos) < 0) return false;

        double reach = player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                ? fakeMode.getReachDistance() : player.blockInteractionRange();
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        if (dx * dx + dy * dy + dz * dz > reach * reach) return false;

        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(
                player.getEyePosition(), net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos().equals(pos);
    }

    /** Empty hand is usable; only a nearly broken damageable tool is unsafe. */
    public static int toolDurability(ServerPlayer player) {
        var item = player.getMainHandItem();
        if (item.isEmpty() || !item.isDamageableItem()) return Integer.MAX_VALUE;
        return item.getMaxDamage() - item.getDamageValue();
    }
}
