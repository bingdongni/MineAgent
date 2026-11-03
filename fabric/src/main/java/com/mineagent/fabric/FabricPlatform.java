package com.mineagent.fabric;

import com.mineagent.api.platform.Services;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * Fabric implementation of {@link Services.IPlatform}.
 * <p>
 * Provides access to Minecraft server internals through Fabric's API.
 * Registered at mod initialization by {@link MineAgentFabric}.
 */
public class FabricPlatform implements Services.IPlatform {

    private final MinecraftServer server;

    public FabricPlatform(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void scheduleOnServer(Runnable task) {
        if (server != null) {
            server.execute(task);
        }
    }

    @Override
    public Optional<UUID> findPlayerUuid(String name) {
        if (server == null) return Optional.empty();
        var player = server.getPlayerList().getPlayerByName(name);
        return player != null ? Optional.of(player.getUUID()) : Optional.empty();
    }

    @Override
    public boolean isPlayerOnline(UUID uuid) {
        if (server == null) return false;
        return server.getPlayerList().getPlayer(uuid) != null;
    }

    @Override
    public boolean isClientSide() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public long gameTime() {
        if (server == null) return 0;
        var levels = server.getAllLevels().iterator();
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
        channels.put("energy", "no_standard_adapter");
        boolean truncated = false;
        try {
            Class<?> api = Class.forName("team.reborn.energy.api.EnergyStorage");
            @SuppressWarnings("unchecked")
            var lookup = (net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup<Object, net.minecraft.core.Direction>) api.getField("SIDED").get(null);
            Object energy = lookup.find(level, pos, side);
            channels.put("energy", energy == null ? "unavailable_on_face" : "available");
            if (energy != null) {
                fields.put("energy.amount", String.valueOf(api.getMethod("getAmount").invoke(energy)));
                fields.put("energy.capacity", String.valueOf(api.getMethod("getCapacity").invoke(energy)));
                fields.put("energy.unit", "team_reborn_energy");
            }
        } catch (ClassNotFoundException absent) {
            // This API is optional; ordinary Fabric installations remain supported.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) {
            channels.put("energy", "adapter_error");
        }
        try {
            var items = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, side);
            channels.put("items", items == null ? "unavailable_on_face" : "available");
            if (items != null) {
                int count = 0;
                for (var view : items) {
                    if (count >= 16) { truncated = true; break; }
                    fields.put("items." + count + ".id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(view.getResource().getItem()).toString());
                    fields.put("items." + count + ".count", Long.toString(view.getAmount()));
                    count++;
                }
                fields.put("items.observed_views", Integer.toString(count));
            }
        } catch (RuntimeException failure) { channels.put("items", "adapter_error"); }
        try {
            var fluids = net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(level, pos, side);
            channels.put("fluids", fluids == null ? "unavailable_on_face" : "available");
            if (fluids != null) {
                fields.put("fluids.unit", "fabric_droplets");
                int count = 0;
                for (var view : fluids) {
                    if (count >= 8) { truncated = true; break; }
                    fields.put("fluids." + count + ".id", net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(view.getResource().getFluid()).toString());
                    fields.put("fluids." + count + ".amount", Long.toString(view.getAmount()));
                    fields.put("fluids." + count + ".capacity", Long.toString(view.getCapacity()));
                    count++;
                }
                fields.put("fluids.observed_views", Integer.toString(count));
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
        var itemKey = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (side == null || itemKey == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemKey)
                || !sp.level().isLoaded(pos) || inventorySlot < 0 || inventorySlot > 35 || amount < 1 || amount > 64)
            return com.mineagent.api.platform.PortTransferResult.unavailable("Invalid public-port request");
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemKey);
        int before = sp.getInventory().getItem(inventorySlot).is(item) ? sp.getInventory().getItem(inventorySlot).getCount() : 0;
        try {
            var port = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(sp.level(), pos, side);
            if (port == null) return com.mineagent.api.platform.PortTransferResult.unavailable("Public item port unavailable on the visible face");
            var slot = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(sp.getInventory(), null).getSlot(inventorySlot);
            long moved;
            try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
                moved = net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil.move(intoBlock ? slot : port,
                        intoBlock ? port : slot, variant -> variant.getItem() == item, amount, transaction);
                if (moved > 0) transaction.commit();
            }
            int after = sp.getInventory().getItem(inventorySlot).is(item) ? sp.getInventory().getItem(inventorySlot).getCount() : 0;
            boolean verified = moved >= 0 && moved <= amount && (intoBlock ? before - after : after - before) == moved;
            sp.containerMenu.broadcastChanges();
            return new com.mineagent.api.platform.PortTransferResult(true, verified, (int)moved,
                    moved > 0 ? "Public-port transaction completed" : "No matching items or free capacity on this face");
        } catch (RuntimeException | LinkageError failure) {
            int after = sp.getInventory().getItem(inventorySlot).is(item) ? sp.getInventory().getItem(inventorySlot).getCount() : 0;
            return new com.mineagent.api.platform.PortTransferResult(true, false,
                    Math.max(0, intoBlock ? before - after : after - before), "Port adapter failed; inspect both inventories before retrying");
        }
    }
}
