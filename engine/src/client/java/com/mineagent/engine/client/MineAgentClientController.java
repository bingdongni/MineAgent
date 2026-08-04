package com.mineagent.engine.client;

import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.PathDebugPayload;
import com.mineagent.api.network.payload.TaskResultPayload;
import com.mineagent.engine.client.render.CompanionLabelRenderer;
import com.mineagent.engine.client.render.CompanionVisionRenderer;
import com.mineagent.engine.client.render.PathDebugRenderer;
import com.mineagent.engine.client.screen.CompanionChatScreen;
import com.mineagent.engine.client.screen.CompanionStatusPanel;
import com.mineagent.engine.client.screen.MineAgentMainMenuScreen;
import com.mineagent.engine.network.handler.ClientPacketHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Shared client state and behavior used by both loader implementations.
 *
 * <p>This class deliberately contains no Fabric or NeoForge references.
 * Platform adapters register their event callbacks and inject the one
 * loader-specific operation, sending a UI payload to the server. This keeps
 * the complete visual client identical on both platforms without putting
 * client classes in the engine's dedicated-server source set.
 */
public final class MineAgentClientController {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /** Engine companion ID -> rendered fake-player GameProfile UUID. */
    private static final Map<UUID, UUID> COMPANION_PLAYER_IDS =
            new ConcurrentHashMap<>();
    private static final Set<UUID> COMPANION_IDS =
            ConcurrentHashMap.newKeySet();

    private static volatile Consumer<ClientUiActionPayload> uiActionSender =
            payload -> System.err.println("[MineAgent] Client network sender is not initialized");
    private static volatile UUID companionId;
    private static volatile boolean companionSpawned;
    private static CompanionChatScreen chatScreen;

    private MineAgentClientController() {}

    /**
     * Install the loader-specific C2S transport during client setup.
     *
     * <p>The injected boundary is intentionally limited to the API payload so
     * shared screens never reference a loader's packet record.
     */
    public static void setUiActionSender(Consumer<ClientUiActionPayload> sender) {
        uiActionSender = sender == null
                ? payload -> System.err.println("[MineAgent] Client network sender is not initialized")
                : sender;
    }

    /** Handle all key presses at the end of a client tick. */
    public static void onClientTick(Minecraft client) {
        if (client == null || client.player == null) return;

        if (MineAgentKeyMappings.OPEN_MENU.consumeClick()) {
            client.setScreen(client.screen instanceof MineAgentMainMenuScreen
                    ? null : new MineAgentMainMenuScreen());
        }

        if (MineAgentKeyMappings.OPEN_CHAT.consumeClick()) {
            if (client.screen instanceof CompanionChatScreen) {
                client.setScreen(null);
            } else {
                if (companionId == null) {
                    // The server is authoritative for both the engine ID and
                    // fake-player UUID; guessing from nearby players can mark
                    // real multiplayer users as companions.
                    sendUiAction(new ClientUiActionPayload(
                            NIL_UUID, "request_companions", ""));
                }
                chatScreen = new CompanionChatScreen(companionId);
                client.setScreen(chatScreen);
            }
        }

        if (MineAgentKeyMappings.TOGGLE_STATUS_HUD.consumeClick()) {
            boolean visible = CompanionStatusPanel.toggleVisible();
            showToggleMessage(client, "Companion panel", visible);
        }
        if (MineAgentKeyMappings.TOGGLE_PATH_DEBUG.consumeClick()) {
            boolean enabled = PathDebugRenderer.toggleEnabled();
            showToggleMessage(client, "Path debug", enabled);
        }
        if (MineAgentKeyMappings.TOGGLE_VISION.consumeClick()) {
            boolean enabled = CompanionVisionRenderer.toggleEnabled();
            showToggleMessage(client, "Companion vision", enabled);
        }
        if (MineAgentKeyMappings.TOGGLE_LABEL.consumeClick()) {
            boolean enabled = CompanionLabelRenderer.toggleEnabled();
            showToggleMessage(client, "Companion labels", enabled);
        }
    }

    /**
     * Render shared world overlays from either loader's world-render event.
     *
     * <p>The supplied buffer source belongs to the loader's current render
     * stage. The adapter remains responsible for flushing it when its event
     * contract requires that.
     */
    public static void renderWorld(PoseStack poseStack,
                                   MultiBufferSource bufferSource,
                                   Vec3 cameraPos) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (poseStack == null || bufferSource == null || cameraPos == null
                || level == null) {
            return;
        }

        PathDebugRenderer.render(poseStack, bufferSource, cameraPos,
                level.getGameTime());

        List<Player> companions = findAllCompanions(level);
        if (companions.isEmpty()) return;

        Player primary = companions.getFirst();
        // Entity state is fresher than periodic status packets while the fake
        // player is inside the client's tracking range.
        CompanionStatusPanel.updateAll(
                primary.getHealth(),
                primary.getMaxHealth(),
                primary.getFoodData().getFoodLevel(),
                primary.getAirSupply(),
                primary.getMaxAirSupply(),
                CompanionStatusPanel.getCurrentTask(),
                primary.getX(),
                primary.getY(),
                primary.getZ()
        );
        CompanionVisionRenderer.render(poseStack, bufferSource, cameraPos, primary);
    }

    /** Render both the compact corner panel and contextual head labels. */
    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (graphics == null || deltaTracker == null) return;
        // The old Fabric adapter only rendered labels, so H toggled an
        // invisible panel. Rendering both here makes the binding functional
        // and gives NeoForge the exact same HUD.
        CompanionStatusPanel.render(graphics, deltaTracker);
        CompanionLabelRenderer.renderHud(graphics, deltaTracker);
    }

    /** Apply a server UI action on the Minecraft client thread. */
    public static void handleUiAction(ClientUiActionPayload payload) {
        if (payload == null) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> handleUiAction(payload));
            return;
        }

        switch (payload.action()) {
            case "companion_chat" -> {
                String message = payload.data() == null ? "" : payload.data();
                if (chatScreen != null && client.screen == chatScreen) {
                    chatScreen.receiveCompanionMessage(message);
                } else if (client.player != null && !message.isBlank()) {
                    // Never discard speech just because the dedicated chat
                    // screen is closed; vanilla chat is the non-modal fallback.
                    client.player.displayClientMessage(Component.literal(
                            "[MineAgent] " + message), false);
                }
            }
            case "companion_status" -> parseAndApplyStatus(payload.data());
            case "companion_spawned" -> handleCompanionSpawned(payload);
            case "companion_despawned" -> handleCompanionDespawned(payload.companionId());
            case "companion_task" -> {
                String task = payload.data() == null || payload.data().isBlank()
                        ? "Idle" : payload.data();
                CompanionStatusPanel.setCurrentTask(task);
                if (chatScreen != null) {
                    chatScreen.updateCurrentAction(task);
                }
            }
            default -> {
                // Forward compatibility: older clients safely ignore new UI
                // actions instead of disconnecting from a newer server.
            }
        }
    }

    /** Store and present a task result through the shared client handler. */
    public static void handleTaskResult(TaskResultPayload payload) {
        if (payload == null) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> handleTaskResult(payload));
            return;
        }
        ClientPacketHandler.onTaskResult(client, payload);
    }

    /** Store path telemetry and update the visible renderer atomically. */
    public static void handlePathDebug(PathDebugPayload payload) {
        if (payload == null) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> handlePathDebug(payload));
            return;
        }

        ClientPacketHandler.onPathDebug(client, payload);
        List<BlockPos> blocks = payload.pathNodes().stream()
                .map(node -> BlockPos.containing(node[0], node[1], node[2]))
                .toList();
        if ("failed".equals(payload.pathStatus())) {
            long gameTime = client.level == null ? 0L : client.level.getGameTime();
            PathDebugRenderer.setFailedPath(payload.companionId(), blocks, gameTime);
        } else if (blocks.isEmpty()) {
            PathDebugRenderer.clearCompanion(payload.companionId());
        } else {
            PathDebugRenderer.setPathWithProgress(
                    payload.companionId(), blocks, payload.currentNode());
        }
    }

    /** Send one shared UI action using the active loader's transport. */
    public static void sendUiAction(ClientUiActionPayload payload) {
        if (payload == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal(
                        "MineAgent is not connected to a server")
                        .withStyle(ChatFormatting.RED), false);
            }
            return;
        }
        try {
            uiActionSender.accept(payload);
        } catch (RuntimeException transportFailure) {
            System.err.println("[MineAgent] Failed to send UI action '"
                    + payload.action() + "': " + transportFailure.getMessage());
        }
    }

    /**
     * Ask the authoritative server to replay owned companion IDs after login.
     *
     * <p>This closes a race where a restored fake player can already exist
     * before the client UI registers its state, leaving labels and menus empty
     * until the chat screen is opened manually.
     */
    public static void requestCompanionSync() {
        sendUiAction(new ClientUiActionPayload(
                NIL_UUID, "request_companions", ""));
    }

    /**
     * Clear world-scoped data on disconnect while retaining the platform
     * transport installed for the life of the game process.
     */
    public static void clearClientState() {
        chatScreen = null;
        companionId = null;
        COMPANION_IDS.clear();
        COMPANION_PLAYER_IDS.clear();
        companionSpawned = false;
        CompanionStatusPanel.setSpawned(false);
        CompanionStatusPanel.setCurrentTask("Idle");
        PathDebugRenderer.clearAll();
        ClientPacketHandler.clearAll();
    }

    public static UUID getCompanionId() {
        return companionId;
    }

    public static boolean isCompanionSpawned() {
        return companionSpawned;
    }

    /** True only for a fake-player UUID explicitly supplied by the server. */
    public static boolean isCompanionPlayer(UUID playerUuid) {
        return playerUuid != null && COMPANION_PLAYER_IDS.containsValue(playerUuid);
    }

    public static CompanionChatScreen getChatScreen() {
        return chatScreen;
    }

    private static void handleCompanionSpawned(ClientUiActionPayload payload) {
        companionSpawned = true;
        COMPANION_IDS.add(payload.companionId());
        try {
            COMPANION_PLAYER_IDS.put(payload.companionId(),
                    UUID.fromString(payload.data()));
        } catch (IllegalArgumentException | NullPointerException invalidPlayerId) {
            // Do not infer identity from arbitrary client entities. A later
            // request_companions response can repair malformed legacy data.
            COMPANION_PLAYER_IDS.remove(payload.companionId());
        }
        if (companionId == null) {
            companionId = payload.companionId();
        }
        CompanionStatusPanel.setSpawned(true);
        if (chatScreen != null) {
            chatScreen.setCompanionId(companionId);
        }
    }

    private static void handleCompanionDespawned(UUID removedId) {
        if (removedId == null) return;
        COMPANION_IDS.remove(removedId);
        COMPANION_PLAYER_IDS.remove(removedId);
        ClientPacketHandler.clearCompanion(removedId);
        PathDebugRenderer.clearCompanion(removedId);

        if (COMPANION_IDS.isEmpty()) {
            companionSpawned = false;
            companionId = null;
            CompanionStatusPanel.setSpawned(false);
            PathDebugRenderer.clearAll();
        } else if (removedId.equals(companionId)) {
            companionId = COMPANION_IDS.iterator().next();
        }
        if (chatScreen != null) {
            chatScreen.setCompanionId(companionId);
        }
    }

    private static void parseAndApplyStatus(String data) {
        if (data == null) return;
        try {
            String[] parts = data.split(",", 9);
            if (parts.length != 9) return;

            CompanionStatusPanel.updateAll(
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    parts[5].isBlank() ? "Idle" : parts[5],
                    Double.parseDouble(parts[6]),
                    Double.parseDouble(parts[7]),
                    Double.parseDouble(parts[8])
            );
        } catch (NumberFormatException malformedStatus) {
            // A malformed optional status update must not disconnect or crash
            // the client; the tracked entity remains the fallback data source.
        }
    }

    private static List<Player> findAllCompanions(ClientLevel level) {
        Minecraft client = Minecraft.getInstance();
        List<Player> companions = new ArrayList<>();
        for (var entity : level.entitiesForRendering()) {
            if (entity instanceof Player player && entity != client.player
                    && isCompanionPlayer(player.getUUID())) {
                companions.add(player);
            }
        }
        return companions;
    }

    private static void showToggleMessage(Minecraft client, String label,
                                          boolean enabled) {
        if (client.player == null) return;
        client.player.displayClientMessage(
                Component.literal(label + ": ")
                        .append(Component.literal(enabled ? "ON" : "OFF")
                                .withStyle(enabled
                                        ? ChatFormatting.GREEN
                                        : ChatFormatting.GRAY)),
                true
        );
    }
}
