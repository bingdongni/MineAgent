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
}
