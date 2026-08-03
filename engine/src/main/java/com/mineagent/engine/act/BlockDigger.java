package com.mineagent.engine.act;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block breaking logic - provides low-level Minecraft interaction for
 * digging blocks. Used by tools (AutoMineTool, BuildTool) and survival
 * chains (UnstuckChain, MobDefenseChain).
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class BlockDigger {

    /** Maximum reach distance for block interaction (same as vanilla: 4.5 in survival, 5.0 in creative). */
    private static final double REACH_SURVIVAL = 4.5;
    private static final double REACH_CREATIVE = 5.0;

    /** Ticks per second (Minecraft runs at 20 TPS). */
    private static final int TPS = 20;

    private BlockDigger() {}

    // ── Primary API ───────────────────────────────────────────────

    /**
     * Break a block at the given position.
     *
     * <p>The method checks reach distance, calculates break speed
     * based on tool and block hardness, and invokes the server-side
     * game mode handler to perform the break.
     *
     * @param player the server player performing the break
     * @param pos    the block position to break
     * @return true if the block was successfully broken
     */
    public static boolean digBlock(ServerPlayer player, BlockPos pos) {
        try {
            if (player == null || pos == null) return false;

            // This older facade used level.destroyBlock for zero-hardness and
            // creative cases, bypassing ServerPlayerGameMode's tool, stats,
            // restriction and event semantics. Keep its public contract for
            // compatibility, but route every call through the single shared
            // START/tick/STOP implementation used by tasks and path clearance.
            if (!com.mineagent.engine.task.BlockDigger.startBreaking(player, pos)) {
                return false;
            }
            return player.level().getBlockState(pos).isAir();
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockDigger.digBlock error: " + e.getMessage());
            return false;
        }
    }

    // ── Break Speed Calculations ──────────────────────────────────

    /**
     * Calculate the break speed (blocks per second) for the player
     * breaking the given block state.
     *
     * <p>Accounts for:
     * <ul>
     *   <li>Tool efficiency (tiered items get a speed multiplier)</li>
     *   <li>Haste / Mining Fatigue effects</li>
     *   <li>Water mining penalty (5x slower unless aqua affinity)</li>
     *   <li>Airborne penalty (5x slower if not on ground)</li>
     * </ul>
     *
     * @param player the server player
     * @param state  the block state being broken
     * @return break speed in blocks per second, or 0 if unbreakable
     */
    @SuppressWarnings("deprecation")
    public static float getBreakSpeed(ServerPlayer player, BlockState state) {
        try {
            if (player == null || state == null || state.isAir()) return 0;
            if (state.getDestroySpeed(player.level(), player.blockPosition()) < 0) return 0;

            // Creative mode: instant break
            if (player.isCreative()) return Float.MAX_VALUE;

            // Base destroy speed (hardness)
            float hardness = state.getDestroySpeed(player.serverLevel(), player.blockPosition());
            if (hardness == 0) return Float.MAX_VALUE; // Instant-break blocks

            // Get the tool and calculate the dig speed
            ItemStack tool = player.getMainHandItem();
            float digSpeed = 1.0f;

            // Tool contribution
            if (!tool.isEmpty() && tool.getItem() instanceof TieredItem tiered) {
                float speed = tiered.getTier().getSpeed();
                digSpeed = speed;
                // Bonus for correct tool type
                if (tool.isCorrectToolForDrops(state)) {
                    digSpeed += 1.0f; // Vanilla adds 1 for correct tool
                }
            }

            // Haste effect: +20% per level
            var hasteEffect = player.getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED);
            if (hasteEffect != null) {
                digSpeed *= 1.0f + (hasteEffect.getAmplifier() + 1) * 0.2f;
            }

            // Mining fatigue: -30% per level (but never below 0.003)
            var fatigueEffect = player.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
            if (fatigueEffect != null) {
                digSpeed *= (1.0f - (fatigueEffect.getAmplifier() + 1) * 0.3f);
                digSpeed = Math.max(0.003f, digSpeed);
            }

            // Water penalty: 5x slower unless has Aqua Affinity
            if (player.isInWater()) {
                ItemStack helmet = player.getInventory().getArmor(3);
                var enchantmentRegistry = player.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
                var aquaAffinityHolder = enchantmentRegistry.getHolderOrThrow(
                        net.minecraft.world.item.enchantment.Enchantments.AQUA_AFFINITY);
                boolean hasAquaAffinity = helmet.getEnchantments().getLevel(aquaAffinityHolder) > 0;
                if (!hasAquaAffinity) {
                    digSpeed /= 5.0f;
                }
            }

            // Airborne penalty: 5x slower if not on ground (vanilla behavior)
            if (!player.onGround()) {
                digSpeed /= 5.0f;
            }

            // Break speed = digSpeed / hardness (blocks per second at 20 TPS)
            return Math.max(0, digSpeed / hardness);
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockDigger.getBreakSpeed error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Check if the player can instantly break the block (creative-style
     * or zero-hardness blocks).
     *
     * @param player the server player
     * @param state  the block state
     * @return true if the block can be broken in a single tick
     */
    public static boolean canInstantBreak(ServerPlayer player, BlockState state) {
        try {
            if (player == null || state == null) return false;

            // Creative mode: everything is instant
            if (player.isCreative()) return true;

            // Zero hardness blocks (torches, flowers, etc.)
            float hardness = state.getDestroySpeed(player.serverLevel(), player.blockPosition());
            if (hardness == 0) return true;

            return false;
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockDigger.canInstantBreak error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Calculate the number of ticks needed to break the block.
     *
     * <p>Uses the break speed to compute the time, then rounds up
     * to the nearest tick.
     *
     * @param player the server player
     * @param state  the block state being broken
     * @return ticks needed (1 minimum, Integer.MAX_VALUE if unbreakable)
     */
    public static int ticksToBreak(ServerPlayer player, BlockState state) {
        try {
            if (player == null || state == null) return Integer.MAX_VALUE;
            if (state.isAir()) return 0;
            if (canInstantBreak(player, state)) return 1;

            float speed = getBreakSpeed(player, state);
            if (speed <= 0) return Integer.MAX_VALUE;
            if (speed == Float.MAX_VALUE) return 1;

            // speed is in blocks/second, so 1/speed = seconds per block
            // ticks = seconds * TPS
            float seconds = 1.0f / speed;
            int ticks = Math.max(1, Math.round(seconds * TPS));

            return ticks;
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockDigger.ticksToBreak error: " + e.getMessage());
            return Integer.MAX_VALUE;
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
        double reach = player.isCreative() ? REACH_CREATIVE : REACH_SURVIVAL;
        // Use eye position for distance check (as vanilla does)
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq <= reach * reach;
    }
}
