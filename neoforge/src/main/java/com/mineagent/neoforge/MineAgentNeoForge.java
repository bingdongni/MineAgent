package com.mineagent.neoforge;

import com.mineagent.api.platform.Services;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.network.MineAgentNetwork;
import com.mineagent.engine.network.handler.ServerPacketHandler;
import com.mineagent.neoforge.client.NeoForgeClientBootstrap;
import com.mineagent.neoforge.client.NeoForgeClientPayloadHandler;
import com.mineagent.neoforge.network.MineAgentNeoForgePayloads;
import com.mineagent.tools.ToolRegistration;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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

    public static final String MOD_ID = "mineagent";
    private static NeoForgePlatform platform;

    public MineAgentNeoForge(IEventBus modBus) {
        // Register mod lifecycle events
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onRegisterPayloadHandlers);

        // Annotation subscribers in an exploded ModDev class directory are
        // not guaranteed to be discovered the same way as classes in the
        // packaged JAR. Register the physical-client adapter explicitly from
        // the known @Mod entry point. The guarded branch is never evaluated
        // on a dedicated server, so client-only Minecraft classes stay out of
        // the server class-loading path.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeClientBootstrap.register(modBus);
        }

        // Register game events on the NeoForge event bus
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLeave);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
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

            // Bridge loader-neutral engine pushes onto NeoForge's registered
            // wire records. Keeping this at the platform boundary prevents
            // NeoForge classes leaking into the shared engine.
            MineAgentNetwork.setUiActionSender((player, payload) ->
                    PacketDistributor.sendToPlayer(player,
                            new MineAgentNeoForgePayloads.UiAction(
                                    payload.companionId(), payload.action(), payload.data())));
            MineAgentNetwork.setTaskResultSender((player, payload) ->
                    PacketDistributor.sendToPlayer(player,
                            new MineAgentNeoForgePayloads.TaskResult(
                                    payload.companionId(), payload.toolCallId(),
                                    payload.success(), payload.message())));
            MineAgentNetwork.setPathDebugSender((player, payload) ->
                    PacketDistributor.sendToPlayer(player,
                            new MineAgentNeoForgePayloads.PathDebug(
                                    payload.companionId(), payload.pathNodes(),
                                    payload.currentNode(), payload.pathStatus())));

            System.out.println("[MineAgent] NeoForge module initialized");
        });
    }

    /** Register all play-phase payloads before either side accepts a connection. */
    private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playBidirectional(MineAgentNeoForgePayloads.UiAction.TYPE,
                MineAgentNeoForgePayloads.UiAction.CODEC, (payload, context) -> {
                    if (context.flow() == PacketFlow.SERVERBOUND
                            && context.player() instanceof ServerPlayer sender) {
                        ServerPacketHandler.onClientUiAction(sender.getServer(), sender,
                                new com.mineagent.api.network.payload.ClientUiActionPayload(
                                        payload.companionId(), payload.action(), payload.data()));
                    } else if (context.flow() == PacketFlow.CLIENTBOUND) {
                        NeoForgeClientPayloadHandler.handleUiAction(payload);
                    }
                });
        registrar.playToServer(MineAgentNeoForgePayloads.ExecuteTool.TYPE,
                MineAgentNeoForgePayloads.ExecuteTool.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer sender) {
                        ServerPacketHandler.onExecuteTool(sender.getServer(), sender,
                                new com.mineagent.api.network.payload.ExecuteToolPayload(
                                        payload.companionId(), payload.toolCallId(),
                                        payload.toolName(), payload.arguments()));
                    }
                });
        registrar.playToServer(MineAgentNeoForgePayloads.CancelTasks.TYPE,
                MineAgentNeoForgePayloads.CancelTasks.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer sender) {
                        ServerPacketHandler.onCancelTasks(sender.getServer(), sender,
                                new com.mineagent.api.network.payload.CancelTasksPayload(
                                        payload.companionId()));
                    }
                });
        registrar.playToClient(MineAgentNeoForgePayloads.TaskResult.TYPE,
                MineAgentNeoForgePayloads.TaskResult.CODEC,
                (payload, context) -> NeoForgeClientPayloadHandler.handleTaskResult(payload));
        registrar.playToClient(MineAgentNeoForgePayloads.PathDebug.TYPE,
                MineAgentNeoForgePayloads.PathDebug.CODEC,
                (payload, context) -> NeoForgeClientPayloadHandler.handlePathDebug(payload));
    }

    /**
     * Server tick — drives the priority auction and task processing.
     */
    private void onServerTick(ServerTickEvent.Post event) {
        MineAgentEngine.onServerTick(event.getServer());
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (platform != null) platform.setServer(event.getServer());
        // Persistence must be scoped to the actual world, not the game or
        // config directory, otherwise companions leak between save files.
        MineAgentEngine.setWorldDataDir(event.getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data"));
        MineAgentEngine.restoreCompanions(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        MineAgentEngine.shutdown();
        if (platform != null) platform.setServer(null);
    }

    private void onServerChat(ServerChatEvent event) {
        MineAgentEngine.onPlayerChat(event.getPlayer(), event.getRawText());
    }

    private void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MineAgentEngine.onOwnerDeath(player);
        }
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
