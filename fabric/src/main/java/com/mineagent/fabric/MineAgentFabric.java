package com.mineagent.fabric;

import com.mineagent.api.platform.Services;
import com.mineagent.api.task.reflex.ReflexRegistry;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.entity.CompanionLifecycleHandler;
import com.mineagent.engine.network.MineAgentNetwork;
import com.mineagent.tools.ToolRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Fabric mod entry point. Implements {@link ModInitializer}.
 * <p>
 * On initialization, this class:
 * <ol>
 *   <li>Registers the Fabric platform implementation</li>
 *   <li>Initializes the MineAgent engine</li>
 *   <li>Registers built-in tools</li>
 *   <li>Registers event handlers (server tick, player chat, lifecycle)</li>
 *   <li>Registers commands (/mineagent spawn, /mineagent remove)</li>
 *   <li>Registers network payload types</li>
 * </ol>
 */
public class MineAgentFabric implements ModInitializer {

    private static FabricPlatform platform;

    @Override
    public void onInitialize() {
        // 1. Server lifecycle hooks
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            platform = new FabricPlatform(server);
            Services.register(platform);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MineAgentEngine.shutdown();
        });

        // 2. Initialize engine (LLM providers, etc.) with config directory
        MineAgentEngine.init(FabricLoader.getInstance().getConfigDir());

        // 3. Register tools
        ToolRegistration.registerAll();

        // 4. Register network payloads
        MineAgentNetwork.registerPayloadTypes();

        // 5. Server tick — drives the priority auction and task processing
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            MineAgentEngine.onServerTick(server);
        });

        // 6. Player chat — forward owner messages to their companions
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, session) -> {
            if (sender != null) {
                MineAgentEngine.onPlayerChat(sender, message.decoratedContent().getString());
            }
        });

        // 7. Player join/leave events for companion lifecycle
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Set the world data directory for companion persistence.
            // Use the server's actual world path (saves/<world>/ for single-player,
            // <level-name>/ for dedicated servers) — never guess from gameDir,
            // which leaks companion data across worlds into a ghost directory.
            java.nio.file.Path dataDir = server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
            MineAgentEngine.setWorldDataDir(dataDir);
            MineAgentEngine.restoreCompanions(server);
        });

        // 7a. Player join — auto-restore saved companion
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            MineAgentEngine.onPlayerJoin(handler.getPlayer());
        });

        // 7b. Player leave — save companion state
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MineAgentEngine.onPlayerLeave(handler.getPlayer());
        });

        // 7c. Player death — teleport companion to respawn point
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
                MineAgentEngine.onOwnerDeath(player);
            }
        });

        // 8. Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            MineAgentEngine.registerCommands(dispatcher);
        });

        // 9. Register network payload types + receivers and the S2C push bridge.
        //    Without this the entire custom network layer is dead: the chat
        //    screen's messages would vanish (sendUiAction was a no-op) and
        //    server pushes would never reach the client UI.
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(com.mineagent.fabric.network.UiActionPayload.TYPE,
                        com.mineagent.fabric.network.UiActionPayload.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(com.mineagent.fabric.network.UiActionPayload.TYPE,
                        com.mineagent.fabric.network.UiActionPayload.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(com.mineagent.fabric.network.MineAgentPayloads.ExecuteTool.TYPE,
                        com.mineagent.fabric.network.MineAgentPayloads.ExecuteTool.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(com.mineagent.fabric.network.MineAgentPayloads.CancelTasks.TYPE,
                        com.mineagent.fabric.network.MineAgentPayloads.CancelTasks.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
                .register(com.mineagent.fabric.network.MineAgentPayloads.CompanionSetup.TYPE,
                        com.mineagent.fabric.network.MineAgentPayloads.CompanionSetup.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(com.mineagent.fabric.network.MineAgentPayloads.TaskResult.TYPE,
                        com.mineagent.fabric.network.MineAgentPayloads.TaskResult.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(com.mineagent.fabric.network.MineAgentPayloads.PathDebug.TYPE,
                        com.mineagent.fabric.network.MineAgentPayloads.PathDebug.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.UiActionPayload.TYPE,
                (payload, context) -> {
                    var server = context.server();
                    var player = context.player();
                    server.execute(() ->
                            com.mineagent.engine.network.handler.ServerPacketHandler
                                    .onClientUiAction(server, player,
                                            new com.mineagent.api.network.payload.ClientUiActionPayload(
                                                    payload.companionId(), payload.action(),
                                                    payload.data())));
                });
        ServerPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.MineAgentPayloads.ExecuteTool.TYPE,
                (payload, context) -> context.server().execute(() ->
                        com.mineagent.engine.network.handler.ServerPacketHandler.onExecuteTool(
                                context.server(), context.player(),
                                new com.mineagent.api.network.payload.ExecuteToolPayload(
                                        payload.companionId(), payload.toolCallId(),
                                        payload.toolName(), payload.arguments()))));
        ServerPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.MineAgentPayloads.CancelTasks.TYPE,
                (payload, context) -> context.server().execute(() ->
                        com.mineagent.engine.network.handler.ServerPacketHandler.onCancelTasks(
                                context.server(), context.player(),
                                new com.mineagent.api.network.payload.CancelTasksPayload(
                                        payload.companionId()))));
        ServerPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.MineAgentPayloads.CompanionSetup.TYPE,
                (payload, context) -> context.server().execute(() ->
                        com.mineagent.engine.network.handler.ServerPacketHandler
                                .onCompanionSetup(context.server(), context.player(),
                                        new com.mineagent.api.network.payload.CompanionSetupPayload(
                                                payload.name(), payload.providerId(),
                                                payload.apiKey(), payload.reuseStoredApiKey(),
                                                payload.model(),
                                                payload.baseUrl(), payload.temperature(),
                                                payload.reasoningEffort(),
                                                payload.gameMode()))));

        // Engine → client push bridge (companion_chat / companion_task / ...)
        MineAgentNetwork.setUiActionSender((player, payload) -> {
            if (ServerPlayNetworking.canSend(player,
                    com.mineagent.fabric.network.UiActionPayload.TYPE)) {
                ServerPlayNetworking.send(player,
                        new com.mineagent.fabric.network.UiActionPayload(
                                payload.companionId(), payload.action(), payload.data()));
            }
        });
        MineAgentNetwork.setTaskResultSender((player, payload) -> {
            var type = com.mineagent.fabric.network.MineAgentPayloads.TaskResult.TYPE;
            if (ServerPlayNetworking.canSend(player, type)) {
                ServerPlayNetworking.send(player,
                        new com.mineagent.fabric.network.MineAgentPayloads.TaskResult(
                                payload.companionId(), payload.toolCallId(),
                                payload.success(), payload.message()));
            }
        });
        MineAgentNetwork.setPathDebugSender((player, payload) -> {
            var type = com.mineagent.fabric.network.MineAgentPayloads.PathDebug.TYPE;
            if (ServerPlayNetworking.canSend(player, type)) {
                ServerPlayNetworking.send(player,
                        new com.mineagent.fabric.network.MineAgentPayloads.PathDebug(
                                payload.companionId(), payload.pathNodes(),
                                payload.currentNode(), payload.pathStatus()));
            }
        });

        System.out.println("[MineAgent] Fabric module initialized");
    }
}
