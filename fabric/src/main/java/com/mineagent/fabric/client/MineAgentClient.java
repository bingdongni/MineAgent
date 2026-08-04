package com.mineagent.fabric.client;

import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.PathDebugPayload;
import com.mineagent.api.network.payload.TaskResultPayload;
import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.fabric.network.MineAgentPayloads;
import com.mineagent.fabric.network.UiActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Fabric event and packet adapter for the loader-neutral visual client.
 *
 * <p>All screens, HUD state, and render behavior live in
 * {@link MineAgentClientController}; this class only translates Fabric APIs
 * into that shared contract.
 */
public final class MineAgentClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientKeyBindings.register();

        // Screens send API payloads through this injected Fabric transport.
        MineAgentClientController.setUiActionSender(MineAgentClient::sendUiAction);

        ClientTickEvents.END_CLIENT_TICK.register(
                MineAgentClientController::onClientTick);
        WorldRenderEvents.LAST.register(MineAgentClient::onWorldRender);

        // Render both the compact H-key panel and contextual head labels.
        // The previous Fabric code registered only the labels, leaving the
        // advertised status panel permanently invisible.
        HudRenderCallback.EVENT.register(MineAgentClientController::renderHud);

        registerPacketHandlers();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(MineAgentClientController::requestCompanionSync));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(MineAgentClientController::clearClientState));
    }

    private static void onWorldRender(WorldRenderContext context) {
        if (context.matrixStack() == null || context.consumers() == null
                || context.camera() == null) {
            return;
        }
        MineAgentClientController.renderWorld(
                context.matrixStack(),
                context.consumers(),
                context.camera().getPosition()
        );
    }

    private static void registerPacketHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
                UiActionPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        MineAgentClientController.handleUiAction(
                                new ClientUiActionPayload(
                                        payload.companionId(),
                                        payload.action(),
                                        payload.data()))));

        ClientPlayNetworking.registerGlobalReceiver(
                MineAgentPayloads.TaskResult.TYPE,
                (payload, context) -> context.client().execute(() ->
                        MineAgentClientController.handleTaskResult(
                                new TaskResultPayload(
                                        payload.companionId(),
                                        payload.toolCallId(),
                                        payload.success(),
                                        payload.message()))));

        ClientPlayNetworking.registerGlobalReceiver(
                MineAgentPayloads.PathDebug.TYPE,
                (payload, context) -> context.client().execute(() ->
                        MineAgentClientController.handlePathDebug(
                                new PathDebugPayload(
                                        payload.companionId(),
                                        payload.nodes(),
                                        payload.currentNode(),
                                        payload.status()))));
    }

    private static void sendUiAction(ClientUiActionPayload payload) {
        if (ClientPlayNetworking.canSend(UiActionPayload.TYPE)) {
            ClientPlayNetworking.send(new UiActionPayload(
                    payload.companionId(), payload.action(), payload.data()));
            return;
        }
        throw new IllegalStateException(
                "server did not register MineAgent UI payloads");
    }
}
