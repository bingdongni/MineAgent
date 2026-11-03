package com.mineagent.neoforge;

import com.mineagent.api.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;

import java.util.Optional;
import java.util.UUID;

/**
 * NeoForge implementation of {@link Services.IPlatform}.
 * <p>
 * Provides access to Minecraft server internals through NeoForge's API.
 * Registered at mod construction by {@link MineAgentNeoForge}.
 */
public class NeoForgePlatform implements Services.IPlatform {

    private volatile MinecraftServer server;

    public NeoForgePlatform() {
    }

    /**
     * Set the server reference. Called when the server starts.
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void scheduleOnServer(Runnable task) {
        MinecraftServer current = server;
        if (current != null) {
            current.execute(task);
        }
    }

    @Override
    public Optional<UUID> findPlayerUuid(String name) {
        MinecraftServer current = server;
        if (current == null) return Optional.empty();
        ServerPlayer player = current.getPlayerList().getPlayerByName(name);
        if (player == null) return Optional.empty();
        return Optional.of(player.getUUID());
    }

    @Override
    public boolean isPlayerOnline(UUID uuid) {
        MinecraftServer current = server;
        if (current == null) return false;
        return current.getPlayerList().getPlayer(uuid) != null;
    }

    @Override
    public boolean isClientSide() {
        return FMLLoader.getDist() == Dist.CLIENT;
    }

    @Override
    public long gameTime() {
        MinecraftServer current = server;
        if (current == null) return 0;
        var levels = current.getAllLevels().iterator();
        if (levels.hasNext()) {
            return levels.next().getGameTime();
        }
        return 0;
    }

    @Override
    public com.mineagent.api.platform.CapabilityObservation observeBlockCapabilities(
            com.mineagent.api.entity.AgentPlayer player, int x, int y, int z, String face) {
        var level = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer().level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        var side = net.minecraft.core.Direction.byName(face);
        if (side == null || !level.isInWorldBounds(pos) || !level.isLoaded(pos))
            return com.mineagent.api.platform.CapabilityObservation.unsupported();
        java.util.Map<String, String> fields = new java.util.TreeMap<>();
        java.util.Map<String, String> channels = new java.util.TreeMap<>();
        boolean truncated = false;
        try {
            var energy = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
            channels.put("energy", energy == null ? "unavailable_on_face" : "available");
            if (energy != null) {
                fields.put("energy.amount", Integer.toString(energy.getEnergyStored()));
                fields.put("energy.capacity", Integer.toString(energy.getMaxEnergyStored()));
                fields.put("energy.unit", "FE");
            }
        } catch (RuntimeException failure) { channels.put("energy", "adapter_error"); }
        try {
            var items = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, side);
            channels.put("items", items == null ? "unavailable_on_face" : "available");
            if (items != null) {
                int count = Math.max(0, items.getSlots());
                fields.put("items.slots", Integer.toString(count));
                truncated |= count > 16;
                for (int i = 0; i < Math.min(16, count); i++) {
                    var stack = items.getStackInSlot(i);
                    fields.put("items." + i + ".id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    fields.put("items." + i + ".count", Integer.toString(stack.getCount()));
                }
            }
        } catch (RuntimeException failure) { channels.put("items", "adapter_error"); }
        try {
            var fluids = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, side);
            channels.put("fluids", fluids == null ? "unavailable_on_face" : "available");
            if (fluids != null) {
                int count = Math.max(0, fluids.getTanks());
                fields.put("fluids.tanks", Integer.toString(count));
                fields.put("fluids.unit", "millibuckets");
                truncated |= count > 8;
                for (int i = 0; i < Math.min(8, count); i++) {
                    var stack = fluids.getFluidInTank(i);
                    fields.put("fluids." + i + ".id", net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
                    fields.put("fluids." + i + ".amount", Integer.toString(stack.getAmount()));
                    fields.put("fluids." + i + ".capacity", Integer.toString(fluids.getTankCapacity(i)));
                }
            }
        } catch (RuntimeException failure) { channels.put("fluids", "adapter_error"); }
        return new com.mineagent.api.platform.CapabilityObservation(fields, channels, truncated);
    }

    @Override
    public com.mineagent.api.platform.PortTransferResult transferBlockItems(
            com.mineagent.api.entity.AgentPlayer player, int x, int y, int z, String face,
            int inventorySlot, String itemId, int amount, boolean intoBlock) {
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        var side = net.minecraft.core.Direction.byName(face);
        var key = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (side == null || key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)
                || !sp.level().isLoaded(pos) || inventorySlot < 0 || inventorySlot > 35 || amount < 1 || amount > 64)
            return com.mineagent.api.platform.PortTransferResult.unavailable("Invalid public-port request");
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
        int before = sp.getInventory().getItem(inventorySlot).is(item) ? sp.getInventory().getItem(inventorySlot).getCount() : 0;
        int moved = 0;
        try {
            var port = sp.serverLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, side);
            if (port == null) return com.mineagent.api.platform.PortTransferResult.unavailable("Public item port unavailable on the visible face");
            for (int i = 0; i < Math.min(64, port.getSlots()) && moved < amount; i++) {
                var carried = sp.getInventory().getItem(inventorySlot);
                if (intoBlock) {
                    if (!carried.is(item)) break;
                    int offered = Math.min(amount - moved, carried.getCount());
                    var remainder = port.insertItem(i, carried.copyWithCount(offered), false);
                    int accepted = offered - remainder.getCount();
                    if (accepted < 0 || accepted > offered) throw new IllegalStateException("Invalid public insertion result");
                    carried.shrink(accepted); moved += accepted;
                } else {
                    var offered = port.getStackInSlot(i);
                    if (!offered.is(item) || (!carried.isEmpty()
                            && !net.minecraft.world.item.ItemStack.isSameItemSameComponents(offered, carried))) continue;
                    int room = offered.getMaxStackSize() - carried.getCount();
                    int requested = Math.min(amount - moved, Math.max(0, room));
                    if (requested == 0) break;
                    var extracted = port.extractItem(i, requested, false);
                    if (extracted.isEmpty()) continue;
                    if (!extracted.is(item) || extracted.getCount() > requested) throw new IllegalStateException("Invalid public extraction result");
                    if (carried.isEmpty()) sp.getInventory().setItem(inventorySlot, extracted);
                    else carried.grow(extracted.getCount());
                    moved += extracted.getCount();
                }
            }
            sp.getInventory().setChanged(); sp.containerMenu.broadcastChanges();
            int after = sp.getInventory().getItem(inventorySlot).is(item) ? sp.getInventory().getItem(inventorySlot).getCount() : 0;
            return new com.mineagent.api.platform.PortTransferResult(true,
                    (intoBlock ? before - after : after - before) == moved, moved,
                    moved > 0 ? "Public-port transfer completed" : "No matching items or free capacity on this face");
        } catch (RuntimeException | LinkageError failure) {
            return new com.mineagent.api.platform.PortTransferResult(true, false, moved,
                    "Port adapter failed; inspect both inventories before retrying");
        }
    }
}
