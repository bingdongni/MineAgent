package com.mineagent.neoforge.client;

import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.CompanionSetupPayload;
import com.mineagent.api.network.payload.PathDebugPayload;
import com.mineagent.api.network.payload.TaskResultPayload;
import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.neoforge.network.MineAgentNeoForgePayloads;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NeoForge packet conversion at the shared-client boundary.
 *
 * <p>State no longer lives in this loader class. Sending every converted
 * payload through {@link MineAgentClientController} gives NeoForge the same
 * menus, HUD, path rendering, and multi-companion semantics as Fabric.
 */
public final class NeoForgeClientPayloadHandler {

    private NeoForgeClientPayloadHandler() {}

    public static void handleUiAction(MineAgentNeoForgePayloads.UiAction payload) {
        MineAgentClientController.handleUiAction(new ClientUiActionPayload(
                payload.companionId(), payload.action(), payload.data()));
    }

    public static void handleTaskResult(MineAgentNeoForgePayloads.TaskResult payload) {
        MineAgentClientController.handleTaskResult(new TaskResultPayload(
                payload.companionId(), payload.toolCallId(), payload.success(),
                payload.message()));
    }

    public static void handlePathDebug(MineAgentNeoForgePayloads.PathDebug payload) {
        MineAgentClientController.handlePathDebug(new PathDebugPayload(
                payload.companionId(), payload.nodes(), payload.currentNode(),
                payload.status()));
    }

    public static void sendUiAction(ClientUiActionPayload payload) {
        PacketDistributor.sendToServer(new MineAgentNeoForgePayloads.UiAction(
                payload.companionId(), payload.action(), payload.data()));
    }

    public static void sendCompanionSetup(CompanionSetupPayload payload) {
        PacketDistributor.sendToServer(new MineAgentNeoForgePayloads.CompanionSetup(
                payload.name(), payload.providerId(), payload.apiKey(),
                payload.reuseStoredApiKey(), payload.model(), payload.baseUrl(),
                payload.temperature(),
                payload.reasoningEffort(), payload.gameMode()));
    }
}
