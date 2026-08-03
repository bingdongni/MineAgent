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
}
