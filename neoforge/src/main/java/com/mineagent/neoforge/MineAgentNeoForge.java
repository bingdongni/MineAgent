package com.mineagent.neoforge;

import com.mineagent.api.platform.Services;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.network.MineAgentNetwork;
import com.mineagent.tools.ToolRegistration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge mod entry point.
 * <p>
 * Handles mod lifecycle events and registers:
 * <ul>
 *   <li>Platform implementation</li>
 *   <li>Engine initialization</li>
 *   <li>Tool registration</li>
 *   <li>Server tick events</li>
 *   <li>Player join/leave events</li>
 *   <li>Commands</li>
 *   <li>Network payload types</li>
 * </ul>
 */
@Mod("mineagent")
public class MineAgentNeoForge {

    private static final String MOD_ID = "mineagent";
    private static NeoForgePlatform platform;

    public MineAgentNeoForge(IEventBus modBus) {
        // Register mod lifecycle events
        modBus.addListener(this::onCommonSetup);

        // Register game events on the NeoForge event bus
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLeave);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    /**
     * Common setup — runs after mod construction, before server start.
     * Initialize engine, register tools, register network payloads.
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Create and register the platform
            platform = new NeoForgePlatform();
            Services.register(platform);

            // Initialize engine (LLM providers, etc.) with config directory
            MineAgentEngine.init(FMLPaths.CONFIGDIR.get());

            // Register tools
            ToolRegistration.registerAll();

            // Register network payload types
            MineAgentNetwork.registerPayloadTypes();

            System.out.println("[MineAgent] NeoForge module initialized");
        });
    }

    /**
     * Server tick — drives the priority auction and task processing.
     */
    private void onServerTick(ServerTickEvent.Post event) {
        MineAgentEngine.onServerTick(event.getServer());
    }

    /**
     * Player join — update platform server reference and handle lifecycle.
     */
    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            // Update the platform's server reference
            if (platform != null) {
                platform.setServer(serverPlayer.getServer());
            }
            MineAgentEngine.onPlayerJoin(serverPlayer);
        }
    }

    /**
     * Player leave — handle companion cleanup if the owner leaves.
     */
    private void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            MineAgentEngine.onPlayerLeave(serverPlayer);
        }
    }

    /**
     * Register commands — /mineagent spawn, /mineagent remove.
     */
    private void onRegisterCommands(RegisterCommandsEvent event) {
        // Note: RegisterCommandsEvent.getServer() is not available in NeoForge 1.21.1
        // The server reference is set via onPlayerJoin instead
        MineAgentEngine.registerCommands(event.getDispatcher());
    }
}
