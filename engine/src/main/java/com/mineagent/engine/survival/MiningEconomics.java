package com.mineagent.engine.survival;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Block mining cost estimation and value assessment.
 * Used by UnstuckChain and other systems to decide whether
 * digging through a block is worth the time cost.
 */
public final class MiningEconomics {

    private MiningEconomics() {}

    // ── Ore value scoring ──────────────────────────────────────────

    /** Base value scores for valuable blocks. */
    private static float rawBlockValue(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return 100.0f;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return 80.0f;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) return 40.0f;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return 25.0f;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) return 30.0f;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) return 20.0f;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) return 15.0f;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) return 10.0f;
        if (block == Blocks.NETHER_GOLD_ORE) return 40.0f;
        if (block == Blocks.NETHER_QUARTZ_ORE) return 15.0f;
        if (block == Blocks.ANCIENT_DEBRIS) return 120.0f;
        if (block == Blocks.SPAWNER) return 50.0f;
        if (block == Blocks.CHEST || block == Blocks.ENDER_CHEST
                || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) return 30.0f;
        // Common blocks are not valuable
        return 0.0f;
    }

    /**
     * Score a block's mining value. Higher = more valuable.
     *
     * @param blockState the block state to evaluate
     * @return value score (0 for common blocks, >0 for ores and valuables)
     */
    public static float blockValue(BlockState blockState) {
        if (blockState == null) return 0.0f;
        return rawBlockValue(blockState.getBlock());
    }

    // ── Dig time estimation ────────────────────────────────────────

    /**
     * Estimate the time (in ticks) to break a block with the given tool.
     * Uses a simplified model based on block hardness and tool tier.
     *
     * @param tool        the tool item stack (may be empty for bare hand)
     * @param blockState  the block to break
     * @return estimated ticks to break the block
     */
    public static float estimateDigTime(ItemStack tool, BlockState blockState) {
        if (blockState == null) return 1.0f;
        if (blockState.isAir()) return 0.0f;

        float hardness = blockState.getDestroySpeed(null, null);
        if (hardness < 0) {
            // Unbreakable block (bedrock, etc.)
            return Float.POSITIVE_INFINITY;
        }
        if (hardness == 0) {
            // Instant-break (torches, flowers, etc.)
            return 1.0f;
        }

        // Base dig time: hardness * 1.5 seconds * 20 tps (no tool bonus)
        float baseTicks = hardness * 30.0f;

        // Tool speed multiplier
        float speedMultiplier = 1.0f;
        if (tool != null && !tool.isEmpty() && tool.getItem() instanceof TieredItem tiered) {
            speedMultiplier = Math.max(1.0f, tiered.getTier().getSpeed() * 1.5f);

            // Check if tool is the correct type for this block
            if (tool.isCorrectToolForDrops(blockState)) {
                speedMultiplier *= 1.5f;
            }
        }

        // Without proper tool, hardness penalty applies
        if (tool == null || tool.isEmpty() || !tool.isCorrectToolForDrops(blockState)) {
            speedMultiplier = Math.max(0.3f, speedMultiplier * 0.2f);
        }

        return Math.max(1.0f, baseTicks / speedMultiplier);
    }

    // ── Decision: should we dig through? ───────────────────────────

    /**
     * Decide whether it's worth digging through a block to reach a goal.
     *
     * <p>The decision considers:
     * <ul>
     *   <li>Time cost to break the block</li>
     *   <li>Distance savings vs going around</li>
     *   <li>Whether the block itself has value (ores!)</li>
     * </ul>
     *
     * @param tool           the tool to use (may be empty)
     * @param blockState     the block in the way
     * @param distanceToGoal straight-line distance to the goal in blocks
     * @return true if digging through is the better choice
     */
    public static boolean shouldDigThrough(ItemStack tool, BlockState blockState,
                                            double distanceToGoal) {
        if (blockState == null || blockState.isAir()) return true;

        float digTime = estimateDigTime(tool, blockState);

        // Unbreakable — never dig
        if (digTime == Float.POSITIVE_INFINITY) return false;

        // Instant or very fast blocks — always dig
        if (digTime <= 6.0f) return true;

        // Valuable blocks — always dig (free loot!)
        if (blockValue(blockState) > 0) return true;

        // Estimate detour cost: going around adds ~3-5 blocks of distance
        // Walking 1 block takes ~3-4 ticks at walking speed
        float detourTicks = (float) distanceToGoal * 2.0f * 4.0f;

        // Dig through if it's faster than the detour
        return digTime < detourTicks;
    }
}
