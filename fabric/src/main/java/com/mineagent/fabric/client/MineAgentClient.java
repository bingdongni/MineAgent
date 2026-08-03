package com.mineagent.fabric.client;

import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.TaskResultPayload;
import com.mineagent.fabric.client.render.CompanionLabelRenderer;
import com.mineagent.fabric.client.render.CompanionVisionRenderer;
import com.mineagent.fabric.client.render.PathDebugRenderer;
import com.mineagent.fabric.client.screen.CompanionChatScreen;
import com.mineagent.fabric.client.screen.CompanionStatusPanel;
import com.mineagent.fabric.client.screen.MineAgentMainMenuScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Client-side event bus registration for MineAgent.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register key bindings ({@link ClientKeyBindings})</li>
 *   <li>Register HUD render callback ({@link CompanionStatusPanel})</li>
 *   <li>Register world render callback ({@link PathDebugRenderer})</li>
 *   <li>Handle key press events each client tick</li>
 *   <li>Handle incoming server-to-client packets</li>
 * </ul>
 *
 * <p>Implements {@link ClientModInitializer} so Fabric auto-calls
 * {@code onInitializeClient} during mod loading.
 */
public class MineAgentClient implements ClientModInitializer {

    /** The currently open companion chat screen, or null. */
    private static CompanionChatScreen chatScreen;

    /** The primary companion UUID (first spawned). Kept for backward
     *  compatibility with {@link #getCompanionId()}. */
    private static UUID companionId;

    /** All known companion UUIDs (supports multi-companion mode, max 3).
     *  Updated from server {@code companion_spawned}/{@code companion_despawned}
     *  pushes so a despawn of one companion no longer wipes the state of
     *  the others. */
    private static final java.util.Set<UUID> companionIds =
            new java.util.LinkedHashSet<>();

    /** Routing companion ID -> fake ServerPlayer entity UUID. The former is
     * random per companion instance; the latter comes from its GameProfile. */
    private static final java.util.Map<UUID, UUID> companionPlayerIds =
            new java.util.HashMap<>();

    /** Whether at least one companion is currently spawned on the server. */
    private static boolean companionSpawned;

    @Override
    public void onInitializeClient() {
        // 1. Register key bindings
        ClientKeyBindings.register();

        // 2. Register client tick event - handles key presses
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // 3. World render callback — renders path debug, companion vision,
        //    and floating name labels above each companion.
        //    Vanilla-style heart + food icons are rendered separately in the
        //    HUD pass (see #4) so we can use GuiGraphics to draw the actual
        //    vanilla icons.png textures — exactly matching the player's HUD.
        WorldRenderEvents.LAST.register(this::onWorldRender);

        // 4. HUD render callback — draws vanilla heart/food icons above each
        //    companion's head. We project the 3D head position to 2D screen
        //    coordinates and blit the vanilla icons there.
        HudRenderCallback.EVENT.register(CompanionLabelRenderer::renderHud);
        // H toggles this legacy panel. It previously changed only a boolean
        // because no render callback was registered, so the key appeared dead.
        HudRenderCallback.EVENT.register(CompanionStatusPanel::render);

        // 4. Register client-side packet handlers
        registerPacketHandlers();

        // Static UI state survives world/server changes in the same client
        // process. Clear authoritative IDs on disconnect so the next server
        // cannot inherit stale companion identities.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                clearSessionState());
    }

    /**
     * Called at the end of every client tick.
     * Checks key bindings and opens/toggles UI elements.
     */
    private void onClientTick(Minecraft mc) {
        if (mc.player == null) return;

        // --- M key: Open MineAgent control panel (main menu) ---
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.OPEN_MENU)) {
            if (mc.screen instanceof MineAgentMainMenuScreen) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new MineAgentMainMenuScreen());
            }
        }

        // --- C key: Open companion chat screen ---
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.OPEN_CHAT)) {
            if (mc.screen instanceof CompanionChatScreen) {
                mc.setScreen(null);
            } else {
                chatScreen = new CompanionChatScreen(companionId);
                mc.setScreen(chatScreen);
                System.out.println("[MineAgent] Opening chat screen, companionId="
                        + companionId);
            }
        }

        // --- H key: Toggle status HUD (legacy top-right panel, kept for compat) ---
        // The primary status display is now the floating label above each
        // companion (toggled with N). H toggles the old panel for users who
        // still want the corner display.
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.TOGGLE_STATUS_HUD)) {
            boolean nowVisible = CompanionStatusPanel.toggleVisible();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§bCompanion Panel: " + (nowVisible ? "§aON" : "§cOFF")
                                + " §7(head labels: N key)"),
                        true
                );
            }
        }

        // --- P key: Toggle path debug render ---
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.TOGGLE_PATH_DEBUG)) {
            boolean nowEnabled = PathDebugRenderer.toggleEnabled();
            if (mc.player != null) {
                // Path data now arrives through the dedicated S2C payload, so
                // the toggle can report its real state without a stale warning.
                String msg = nowEnabled ? "Path Debug: ON" : "Path Debug: OFF";
                mc.player.displayClientMessage(Component.literal(msg), true);
            }
        }

        // --- V key: Toggle companion vision render ---
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.TOGGLE_VISION)) {
            boolean nowEnabled = CompanionVisionRenderer.toggleEnabled();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§bCompanion Vision: " + (nowEnabled ? "§aON" : "§cOFF")),
                        true
                );
            }
        }

        // --- N key: Toggle companion label ---
        if (ClientKeyBindings.wasPressed(ClientKeyBindings.TOGGLE_LABEL)) {
            boolean nowEnabled = CompanionLabelRenderer.toggleEnabled();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§bCompanion Label: " + (nowEnabled ? "§aON" : "§cOFF")),
                        true
                );
            }
        }
    }

    /**
     * Called during world rendering (after entities, before post-processing).
     * Renders path debug, companion vision, and companion labels.
     *
     * <p>Multi-companion support: We iterate over ALL non-local players in the
     * client world (up to 3 AI companions can coexist) and render a floating
     * label above each one. This replaces the old single-companion HUD panel
     * that occupied a large area in the top-right corner.
     */
    private void onWorldRender(WorldRenderContext context) {
        if (context.worldRenderer() == null) return;

        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;

        long gameTime = clientLevel.getGameTime();
        Vec3 cameraPos = context.camera().getPosition();

        // Render path debug lines
        PathDebugRenderer.render(context.matrixStack(), context.consumers(), cameraPos, gameTime);

        // Find ALL companion player entities in the client world.
        // (Previously we only found one; now we render a label above each.)
        java.util.List<Player> companions = findAllCompanions(clientLevel);

        // Keep the legacy panel up to date for backward compatibility
        // (some code paths still read CompanionStatusPanel.getCurrentTask()).
        // The panel itself is no longer rendered on the HUD — see
        // shouldRenderStatusPanel() below.
        if (!companions.isEmpty()) {
            Player primary = companions.get(0);
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
        }

        // Render companion vision (LOS ray, target highlight, vision cone)
        // Only for the primary companion (vision rendering is expensive).
        if (!companions.isEmpty()) {
            CompanionVisionRenderer.render(context.matrixStack(), context.consumers(),
                    cameraPos, companions.get(0));
        }

        // Floating labels (vanilla hearts + food icons + name) are rendered
        // in the HUD pass via CompanionLabelRenderer.renderHud(), not here,
        // because we need GuiGraphics to blit the vanilla icons.png texture.
    }

    /**
     * Find ALL companion player entities in the client world.
     *
     * <p>Only UUIDs announced by the server are companions. Treating every
     * non-local Player as AI corrupts labels and routing on multiplayer servers.
     */
    private java.util.List<Player> findAllCompanions(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<Player> result = new java.util.ArrayList<>();
        for (var entity : level.entitiesForRendering()) {
            if (entity instanceof Player player && entity != mc.player
                    && isKnownCompanion(player.getUUID())) {
                result.add(player);
            }
        }
        return result;
    }

    /**
     * Register handlers for server-to-client packets.
     *
     * <p>Handles:
     * <ul>
     *   <li>{@link TaskResultPayload} - companion task results forwarded to chat</li>
     *   <li>Companion status updates - health, food, position, etc.</li>
     *   <li>Chat messages from companion</li>
     * </ul>
     */
    private void registerPacketHandlers() {
        // Server→Client UI action pushes (companion chat, task updates,
        // spawn/despawn notifications). Handled on the client thread.
        ClientPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.UiActionPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        handleServerUiAction(new ClientUiActionPayload(
                                payload.companionId(), payload.action(), payload.data()))));
        ClientPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.MineAgentPayloads.TaskResult.TYPE,
                (payload, context) -> context.client().execute(() ->
                        com.mineagent.engine.network.handler.ClientPacketHandler.onTaskResult(
                                context.client(),
                                new com.mineagent.api.network.payload.TaskResultPayload(
                                        payload.companionId(), payload.toolCallId(),
                                        payload.success(), payload.message()))));
        ClientPlayNetworking.registerGlobalReceiver(
                com.mineagent.fabric.network.MineAgentPayloads.PathDebug.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    var apiPayload = new com.mineagent.api.network.payload.PathDebugPayload(
                            payload.companionId(), payload.nodes(),
                            payload.currentNode(), payload.status());
                    com.mineagent.engine.network.handler.ClientPacketHandler.onPathDebug(
                            context.client(), apiPayload);
                    java.util.List<net.minecraft.core.BlockPos> blocks = payload.nodes().stream()
                            .map(node -> net.minecraft.core.BlockPos.containing(
                                    node[0], node[1], node[2]))
                            .toList();
                    if ("failed".equals(payload.status())) {
                        long time = context.client().level != null
                                ? context.client().level.getGameTime() : 0L;
                        PathDebugRenderer.setFailedPath(payload.companionId(), blocks, time);
                    } else if (blocks.isEmpty()) {
                        PathDebugRenderer.clearCompanion(payload.companionId());
                    } else {
                        PathDebugRenderer.setPathWithProgress(
                                payload.companionId(), blocks, payload.currentNode());
                    }
                }));
    }

    /**
     * Handle a UI action received from the server.
     * Server sends these to update client-side state (companion status,
     * chat messages, spawn/despawn events).
     *
     * @param payload the action from the server
     */
    private void handleServerUiAction(ClientUiActionPayload payload) {
        switch (payload.action()) {
            case "companion_chat" -> {
                // Companion sent a chat message
                if (chatScreen != null && Minecraft.getInstance().screen == chatScreen
                        && payload.companionId().equals(chatScreen.getCompanionId())) {
                    // Each chat screen is routed to one companion. Without
                    // this ID check, a sibling's reply appeared in the wrong
                    // conversation and could be mistaken for task progress.
                    chatScreen.receiveCompanionMessage(payload.data());
                }
            }

            case "companion_status" -> {
                // Parse status update: "hp,maxHp,food,air,maxAir,task,x,y,z"
                if (payload.companionId().equals(companionId)) {
                    parseAndApplyStatus(payload.data());
                }
            }

            case "companion_spawned" -> {
                companionSpawned = true;
                companionIds.add(payload.companionId());
                try {
                    // Server data carries the fake player's profile UUID;
                    // payload.companionId remains the command-routing ID.
                    companionPlayerIds.put(payload.companionId(),
                            UUID.fromString(payload.data()));
                } catch (RuntimeException invalidEntityId) {
                    System.err.println("[MineAgent] Invalid companion entity UUID: "
                            + payload.data());
                }
                // Primary companion = first one we see (matches engine semantics)
                if (companionId == null) {
                    companionId = payload.companionId();
                }
                CompanionStatusPanel.setSpawned(true);
                if (chatScreen != null) {
                    // Spawning a sibling must not silently retarget an open
                    // chat away from the established primary companion.
                    chatScreen.setCompanionId(companionId);
                }
            }

            case "companion_despawned" -> {
                // Only remove the specific companion that was despawned.
                // Previously this wiped ALL state, so despawning companion #2
                // also hid companion #1 from the UI.
                companionIds.remove(payload.companionId());
                companionPlayerIds.remove(payload.companionId());
                // Task/path payload caches are keyed independently from the
                // visible companion set. Remove only this companion's entries
                // so reconnect/spawn cycles cannot surface stale results while
                // active siblings retain their own debug state.
                com.mineagent.engine.network.handler.ClientPacketHandler
                        .clearCompanion(payload.companionId());
                PathDebugRenderer.clearCompanion(payload.companionId());
                if (companionIds.isEmpty()) {
                    companionSpawned = false;
                    companionId = null;
                    CompanionStatusPanel.setSpawned(false);
                    if (chatScreen != null) {
                        chatScreen.setCompanionId(null);
                    }
                    PathDebugRenderer.clearAll();
                } else if (payload.companionId().equals(companionId)) {
                    // Primary despawned — promote the next available companion
                    companionId = companionIds.iterator().next();
                    if (chatScreen != null) {
                        chatScreen.setCompanionId(companionId);
                    }
                }
            }

            case "companion_task" -> {
                // The legacy status panel is single-companion state. Only the
                // primary companion may update it; a sibling task must not
                // overwrite the label rendered for the primary body.
                if (payload.companionId().equals(companionId)) {
                    CompanionStatusPanel.setCurrentTask(payload.data());
                }
                if (chatScreen != null
                        && payload.companionId().equals(chatScreen.getCompanionId())) {
                    chatScreen.updateCurrentAction(payload.data());
                }
            }

            default -> {
                // Unknown action - ignore
            }
        }
    }

    /**
     * Parse a comma-separated status string from the server and apply it
     * to the CompanionStatusPanel.
     *
     * <p>Format: {@code "hp,maxHp,food,air,maxAir,task,x,y,z"}
     *
     * @param data the status data string
     */
    private void parseAndApplyStatus(String data) {
        try {
            String[] parts = data.split(",", 9);
            if (parts.length < 9) return;

            float hp = Float.parseFloat(parts[0]);
            float maxHp = Float.parseFloat(parts[1]);
            int food = Integer.parseInt(parts[2]);
            int air = Integer.parseInt(parts[3]);
            int maxAir = Integer.parseInt(parts[4]);
            String task = parts[5];
            double x = Double.parseDouble(parts[6]);
            double y = Double.parseDouble(parts[7]);
            double z = Double.parseDouble(parts[8]);

            CompanionStatusPanel.updateAll(hp, maxHp, food, air, maxAir, task, x, y, z);
        } catch (NumberFormatException e) {
            // Malformed status data - ignore
        }
    }

    // --- Public API for sending client actions to server ---

    /**
     * Send a UI action payload to the server.
     * Called from screens and UI components that need to communicate
     * with the server-side companion manager.
     *
     * @param payload the action to send
     */
    public static void sendUiAction(ClientUiActionPayload payload) {
        if (payload == null) return;
        if (ClientPlayNetworking.canSend(com.mineagent.fabric.network.UiActionPayload.TYPE)) {
            ClientPlayNetworking.send(new com.mineagent.fabric.network.UiActionPayload(
                    payload.companionId(), payload.action(), payload.data()));
        } else {
            System.err.println("[MineAgent] Cannot send UI action '" + payload.action()
                    + "' — not connected to a server");
        }
    }

    // --- Accessors ---

    /** Get the current companion ID, or null if no companion. */
    public static UUID getCompanionId() {
        return companionId;
    }

    /** Check if a companion is spawned. */
    public static boolean isCompanionSpawned() {
        return companionSpawned;
    }

    /** Whether the server explicitly announced this player as a companion. */
    public static boolean isKnownCompanion(UUID playerId) {
        return playerId != null && companionPlayerIds.containsValue(playerId);
    }

    /** Get the current chat screen instance, or null. */
    public static CompanionChatScreen getChatScreen() {
        return chatScreen;
    }

    private static void clearSessionState() {
        chatScreen = null;
        companionId = null;
        companionIds.clear();
        companionPlayerIds.clear();
        companionSpawned = false;
        CompanionStatusPanel.setSpawned(false);
        CompanionStatusPanel.setCurrentTask("Idle");
        PathDebugRenderer.clearAll();
        com.mineagent.engine.network.handler.ClientPacketHandler.clearAll();
    }
}
