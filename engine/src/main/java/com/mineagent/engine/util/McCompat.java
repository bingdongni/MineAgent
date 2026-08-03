package com.mineagent.engine.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class McCompat {
    private McCompat() {}

    /** Check if an ItemStack matches the given item ID string (e.g. "minecraft:stone") */
    public static boolean isItem(ItemStack stack, String itemId) {
        if (stack.isEmpty() || itemId == null) return false;
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return false;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null) return false;
        return stack.is(item);
    }

    /** Check if a BlockState matches the given block ID string (e.g. "minecraft:stone") */
    public static boolean isBlock(BlockState state, String blockId) {
        if (state == null || blockId == null) return false;
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return false;
        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) return false;
        return state.is(block);
    }
}
