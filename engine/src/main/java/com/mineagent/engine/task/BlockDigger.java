package com.mineagent.engine.task;

import com.mineagent.engine.act.BlockTargeting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;

/**
 * Executes vanilla block breaking and selects a suitable real inventory tool.
 */
public final class BlockDigger {

    /** Machine-readable reason for accepting or rejecting a break request. */
    public enum BreakStatus {
        READY,
        INVALID_ARGUMENT,
        OUT_OF_WORLD,
        OUTSIDE_WORLD_BORDER,
        AIR,
        UNBREAKABLE,
        OUT_OF_REACH,
        OCCLUDED,
        WORLD_INTERACTION_DENIED,
        GAME_MODE_RESTRICTED,
        VANILLA_START_REJECTED
    }

    /**
     * Authoritative preflight evidence. Only the two explicit policy statuses
     * indicate protection/restriction; geometry failures must never be
     * described to the LLM as a protected region.
     */
    public record BreakAssessment(BreakStatus status, String detail,
                                  BlockHitResult hit) {
        public boolean allowed() { return status == BreakStatus.READY; }

        public String diagnostic() {
            String code = status.name().toLowerCase(Locale.ROOT);
            return "break_status=" + code + (detail == null || detail.isBlank()
                    ? "" : " detail=" + detail);
        }
    }

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
        return startBreakingDetailed(player, pos).allowed();
    }

    /**
     * Start a break and retain the exact rejection evidence for tasks and the
     * LLM. This keeps low-level reach/visibility correction in the executor
     * instead of asking the model to guess whether a region is protected.
     */
    public static BreakAssessment startBreakingDetailed(ServerPlayer player, BlockPos pos) {
        BreakAssessment assessment = assessBreak(player, pos);
        if (!assessment.allowed()) return assessment;
        BlockState state = player.level().getBlockState(pos);
        prepareBestTool(player, state);
        BlockHitResult hit = assessment.hit();
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        player.gameMode.handleBlockBreakAction(pos,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                hit.getDirection(),
                player.serverLevel().getMaxBuildHeight(), 0);

        if (!player.level().getBlockState(pos).isAir()
                && player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode
                && !fakeMode.isAutomaticallyDestroying(pos)) {
            return new BreakAssessment(BreakStatus.VANILLA_START_REJECTED,
                    "server game mode did not accept START_DESTROY_BLOCK", hit);
        }
        return assessment;
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
        BreakAssessment assessment = assessBreak(player, pos);
        if (!assessment.allowed()) {
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
        return assessBreak(player, pos).allowed();
    }

    /**
     * Diagnose every independent break precondition in a stable order. The
     * visible-point solver samples actual outline faces, matching where a
     * human client can aim rather than requiring the block centre to be clear.
     */
    public static BreakAssessment assessBreak(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return rejected(BreakStatus.INVALID_ARGUMENT, "player or target is null");
        }
        var level = player.serverLevel();
        if (!level.isInWorldBounds(pos)) {
            return rejected(BreakStatus.OUT_OF_WORLD,
                    "target=" + pos.toShortString());
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return rejected(BreakStatus.OUTSIDE_WORLD_BORDER,
                    "target=" + pos.toShortString());
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return rejected(BreakStatus.AIR, "target already contains air");
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (!Float.isFinite(hardness) || hardness < 0.0f) {
            return rejected(BreakStatus.UNBREAKABLE, "hardness=" + hardness);
        }

        double reach = BlockTargeting.interactionReach(player);
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Match vanilla's one-block packet tolerance. The visible hit itself
        // is still constrained to the configured reach by BlockTargeting.
        if (!player.canInteractWithBlock(pos, 1.0) && distance > reach) {
            return rejected(BreakStatus.OUT_OF_REACH,
                    String.format(Locale.ROOT, "distance=%.2f reach=%.2f", distance, reach));
        }
        if (!level.mayInteract(player, pos)) {
            return rejected(BreakStatus.WORLD_INTERACTION_DENIED,
                    "server mayInteract denied target=" + pos.toShortString());
        }
        if (player.blockActionRestricted(level, pos,
                player.gameMode.getGameModeForPlayer())) {
            return rejected(BreakStatus.GAME_MODE_RESTRICTED,
                    "game mode restricted target=" + pos.toShortString());
        }

        var hit = BlockTargeting.findVisibleHit(player, pos, reach);
        if (hit.isEmpty()) {
            BlockPos blocker = BlockTargeting.centreRayBlocker(player, pos);
            String detail = blocker == null
                    ? "no target outline face is visible within reach"
                    : "no target outline face is visible; centre_ray_first_hit="
                    + blocker.toShortString();
            return rejected(BreakStatus.OCCLUDED, detail);
        }
        return new BreakAssessment(BreakStatus.READY,
                "aim=" + format(hit.get().getLocation())
                        + " face=" + hit.get().getDirection().getName(), hit.get());
    }

    private static BreakAssessment rejected(BreakStatus status, String detail) {
        return new BreakAssessment(status, detail, null);
    }

    private static String format(net.minecraft.world.phys.Vec3 point) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                point.x, point.y, point.z);
    }

    /** Empty hand is usable; only a nearly broken damageable tool is unsafe. */
    public static int toolDurability(ServerPlayer player) {
        var item = player.getMainHandItem();
        if (item.isEmpty() || !item.isDamageableItem()) return Integer.MAX_VALUE;
        return item.getMaxDamage() - item.getDamageValue();
    }
}
