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
}
