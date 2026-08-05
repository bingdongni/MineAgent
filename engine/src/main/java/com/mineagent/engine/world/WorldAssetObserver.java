package com.mineagent.engine.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Converts live Minecraft stacks into generic asset observations. */
public final class WorldAssetObserver {
    private record InteractionTarget(WorldAssetIndex.Position position, long gameTick) {}
    private static final ConcurrentHashMap<UUID, InteractionTarget> INTERACTION_TARGETS =
            new ConcurrentHashMap<>();

    private WorldAssetObserver() {}

    public static WorldAssetIndex.Position position(
            net.minecraft.server.level.ServerPlayer player) {
        var pos = player.blockPosition();
        return new WorldAssetIndex.Position(player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ());
    }

    public static List<WorldAssetIndex.ItemObservation> inventory(
            net.minecraft.server.level.ServerPlayer player) {
        List<WorldAssetIndex.ItemObservation> result = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) result.add(item(slot, stack));
        }
        return result;
    }

    public static WorldAssetIndex.ItemObservation item(int slot, ItemStack stack) {
        String id = stack == null || stack.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int maxDurability = stack != null && stack.isDamageableItem()
                ? stack.getMaxDamage() : 0;
        int durability = maxDurability == 0 ? 0
                : Math.max(0, maxDurability - stack.getDamageValue());
        return new WorldAssetIndex.ItemObservation(slot, id,
                stack == null ? 0 : stack.getCount(), durability, maxDurability,
                capabilities(stack), quality(stack));
    }

    /**
     * Record only non-player slots from an open menu. Their location remains
     * transient because a generic modded menu does not necessarily expose a
     * block coordinate; explicit inspect_block_storage upgrades the same facts
     * to durable positioned storage.
     */
    public static String observeOpenMenu(WorldAssetIndex index,
                                         net.minecraft.server.level.ServerPlayer player,
                                         AbstractContainerMenu menu) {
        if (index == null || player == null || menu == null
                || menu == player.inventoryMenu) return null;
        WorldAssetIndex.Position storagePosition = durableMenuPosition(player, menu);
        boolean durableLocation = storagePosition != null;
        if (storagePosition == null) storagePosition = position(player);
        String containerId = durableLocation ? storagePosition.compact()
                : "open-menu:" + menu.getClass().getName() + ":" + menu.containerId;
        List<WorldAssetIndex.ItemObservation> contents = new ArrayList<>();
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            var slot = menu.getSlot(menuSlot);
            if (slot.container == player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) contents.add(item(menuSlot, stack));
        }
        index.observeContainer(containerId, storagePosition, contents, durableLocation,
                player.level().getGameTime());
        return containerId;
    }

    /** Associate the menu opened by a verified block use with its world cell. */
    public static void rememberInteractionTarget(
            net.minecraft.server.level.ServerPlayer player,
            net.minecraft.core.BlockPos target) {
        if (player == null || target == null) return;
        INTERACTION_TARGETS.put(player.getUUID(), new InteractionTarget(
                new WorldAssetIndex.Position(
                        player.level().dimension().location().toString(),
                        target.getX(), target.getY(), target.getZ()),
                player.level().getGameTime()));
    }

    public static void forgetPlayer(net.minecraft.server.level.ServerPlayer player) {
        if (player != null) INTERACTION_TARGETS.remove(player.getUUID());
    }

    private static WorldAssetIndex.Position durableMenuPosition(
            net.minecraft.server.level.ServerPlayer player,
            AbstractContainerMenu menu) {
        // Many vanilla and modded menus expose their BlockEntity directly as
        // the slot backing container. Prefer that authoritative association.
        for (var slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            if (slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                var pos = be.getBlockPos();
                return new WorldAssetIndex.Position(
                        player.level().dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ());
            }
        }

        // Composite containers (for example a double chest) may hide the
        // BlockEntity behind a wrapper. The immediately preceding accepted
        // block interaction is still grounded evidence, but expire it quickly
        // so an unrelated later menu cannot inherit the wrong coordinate.
        InteractionTarget remembered = INTERACTION_TARGETS.get(player.getUUID());
        if (remembered == null) return null;
        long age = player.level().getGameTime() - remembered.gameTick();
        if (age < 0L || age > 200L
                || !remembered.position().dimension().equals(
                        player.level().dimension().location().toString())) return null;
        return remembered.position();
    }

    /**
     * Capabilities come from registry tags and data components, so properly
     * tagged modded items participate without a list of concrete item IDs.
     */
    public static Set<String> capabilities(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Set.of();
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        capabilities.add("item");
        if (stack.getItem() instanceof BlockItem) capabilities.add("place_block");
        if (stack.is(ItemTags.AXES)) capabilities.add("tool:axe");
        if (stack.is(ItemTags.PICKAXES)) capabilities.add("tool:pickaxe");
        if (stack.is(ItemTags.SHOVELS)) capabilities.add("tool:shovel");
        if (stack.is(ItemTags.HOES)) capabilities.add("tool:hoe");
        if (stack.is(ItemTags.SWORDS)) {
            capabilities.add("tool:sword");
            capabilities.add("weapon:melee");
        }
        if (stack.has(DataComponents.TOOL)) capabilities.add("tool");
        if (stack.has(DataComponents.FOOD)) capabilities.add("food");

        // Derive armor roles from data-pack tags instead of concrete item
        // classes. This works for modded armor that follows conventional tags
        // and avoids depending on loader-specific equipment APIs.
        stack.getTags().map(tag -> tag.location().getPath()).forEach(path -> {
            if (path.endsWith("head_armor") || path.endsWith("helmets")) {
                capabilities.add("armor:head");
            } else if (path.endsWith("chest_armor") || path.endsWith("chestplates")) {
                capabilities.add("armor:chest");
            } else if (path.endsWith("leg_armor") || path.endsWith("leggings")) {
                capabilities.add("armor:legs");
            } else if (path.endsWith("foot_armor") || path.endsWith("boots")) {
                capabilities.add("armor:feet");
            }
        });
        if (attackDamage(stack) > 0.0) capabilities.add("weapon:melee");
        return Set.copyOf(capabilities);
    }

    /** Capabilities safe for substituting one durable tool/equipment item. */
    public static Set<String> substitutionCapabilities(ItemStack stack) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String capability : capabilities(stack)) {
            if (capability.startsWith("tool:") || capability.startsWith("armor:")) {
                result.add(capability);
            }
        }
        // A purpose-specific tool or armor slot must match that purpose. Only
        // fall back to generic melee equivalence for items that expose no more
        // precise substitutable capability; otherwise a sword could wrongly
        // block crafting an axe merely because both have attack damage.
        if (result.isEmpty() && capabilities(stack).contains("weapon:melee")) {
            result.add("weapon:melee");
        }
        return Set.copyOf(result);
    }

    /**
     * A monotonic comparison signal, not a universal utility score. It is used
     * only when two assets expose the same substitutable capability.
     */
    public static double quality(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        double durability = stack.isDamageableItem() ? stack.getMaxDamage() : 0.0;
        double damage = attackDamage(stack) * 100.0;
        double enchantmentEvidence = stack.isEnchanted() ? 25.0 : 0.0;
        return durability + damage + enchantmentEvidence;
    }

    private static double attackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double result = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND)
                    && entry.attribute().is(Attributes.ATTACK_DAMAGE)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                result += entry.modifier().amount();
            }
        }
        return result;
    }
}
