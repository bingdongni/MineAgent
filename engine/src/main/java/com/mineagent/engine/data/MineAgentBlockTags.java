package com.mineagent.engine.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.CompletableFuture;

/**
 * Data generator for MineAgent block tags.
 * <p>
 * Creates tags used by the pathfinding and survival systems:
 * <ul>
 *   <li>{@code mineagent:danger} - blocks that harm the companion
 *       (lava, fire, magma, wither rose, sweet berry bush, cactus)</li>
 *   <li>{@code mineagent:breakable} - blocks the companion can mine</li>
 *   <li>{@code mineagent:passable} - blocks the companion can walk through</li>
 * </ul>
 */
public class MineAgentBlockTags extends TagsProvider<Block> {

    /** Tag for blocks that are dangerous to the companion. */
    public static final TagKey<Block> DANGER = TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mineagent", "danger")
    );

    /** Tag for blocks the companion can break. */
    public static final TagKey<Block> BREAKABLE = TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mineagent", "breakable")
    );

    /** Tag for blocks the companion can walk through (non-solid). */
    public static final TagKey<Block> PASSABLE = TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mineagent", "passable")
    );

    @SuppressWarnings("deprecation")
    public MineAgentBlockTags(PackOutput output,
                              CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, net.minecraft.core.registries.Registries.BLOCK, lookupProvider);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // ── Danger tag: blocks that harm the companion ──────────────
        var danger = tag(DANGER);
        danger.add(key(Blocks.LAVA));
        danger.add(key(Blocks.FIRE));
        danger.add(key(Blocks.SOUL_FIRE));
        danger.add(key(Blocks.MAGMA_BLOCK));
        danger.add(key(Blocks.WITHER_ROSE));
        danger.add(key(Blocks.SWEET_BERRY_BUSH));
        danger.add(key(Blocks.CACTUS));
        danger.add(key(Blocks.CAMPFIRE));
        danger.add(key(Blocks.SOUL_CAMPFIRE));
        danger.add(key(Blocks.POWDER_SNOW));

        // ── Breakable tag: blocks the companion can mine ────────────
        // All vanilla mineable blocks are breakable by the companion.
        // This is a superset that includes tool-specific tags.
        var breakable = tag(BREAKABLE);
        breakable.addTag(BlockTags.MINEABLE_WITH_PICKAXE);
        breakable.addTag(BlockTags.MINEABLE_WITH_AXE);
        breakable.addTag(BlockTags.MINEABLE_WITH_SHOVEL);
        breakable.addTag(BlockTags.MINEABLE_WITH_HOE);
        // MINEABLE_WITH_SWORD doesn't exist in 1.21.1 - swords aren't mineable tools
        // Explicitly add common blocks that aren't in mineable tags
        breakable.add(key(Blocks.TORCH));
        breakable.add(key(Blocks.SOUL_TORCH));
        breakable.add(key(Blocks.REDSTONE_WIRE));
        breakable.add(key(Blocks.REDSTONE_TORCH));
        breakable.add(key(Blocks.TRIPWIRE));
        breakable.add(key(Blocks.TRIPWIRE_HOOK));
        breakable.add(key(Blocks.LEVER));
        breakable.add(key(Blocks.STONE_BUTTON));
        breakable.add(key(Blocks.OAK_BUTTON));
        breakable.add(key(Blocks.REPEATER));
        breakable.add(key(Blocks.COMPARATOR));
        breakable.add(key(Blocks.SCAFFOLDING));

        // ── Passable tag: non-solid blocks the companion can walk through ──
        var passable = tag(PASSABLE);
        passable.addTag(BlockTags.FLOWERS);
        passable.add(key(Blocks.AIR));
        passable.add(key(Blocks.CAVE_AIR));
        passable.add(key(Blocks.VOID_AIR));
        passable.add(key(Blocks.SHORT_GRASS));
        passable.add(key(Blocks.TALL_GRASS));
        passable.add(key(Blocks.FERN));
        passable.add(key(Blocks.LARGE_FERN));
        passable.add(key(Blocks.SEAGRASS));
        passable.add(key(Blocks.TALL_SEAGRASS));
        passable.add(key(Blocks.SNOW));
        passable.add(key(Blocks.COBWEB));
        passable.add(key(Blocks.RAIL));
        passable.add(key(Blocks.POWERED_RAIL));
        passable.add(key(Blocks.DETECTOR_RAIL));
        passable.add(key(Blocks.ACTIVATOR_RAIL));
    }

    private static ResourceKey<Block> key(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getResourceKey(block).orElseThrow();
    }
}
