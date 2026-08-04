package com.mineagent.engine.network.handler;

import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.network.payload.CancelTasksPayload;
import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.api.network.payload.ExecuteToolPayload;
import com.mineagent.api.task.reflex.ReflexRegistry;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.network.MineAgentNetwork;
import com.mineagent.engine.entity.CompanionEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles incoming client→server packets.
 * <p>
 * All handler methods are called on the server thread (the platform modules
 * ensure this by scheduling via {@code server.execute()}).
 * <p>
 * Handles:
 * <ul>
 *   <li>{@code onExecuteTool} — dispatch a tool call to the engine</li>
 *   <li>{@code onCancelTasks} — cancel all running tasks for a companion</li>
 *   <li>{@code onClientUiAction} — handle UI actions (open chat, toggle reflex)</li>
 * </ul>
 */
public final class ServerPacketHandler {

    private ServerPacketHandler() {}

    /**
     * Handle an incoming ExecuteToolPayload.
     * <p>
     * Dispatches the tool call to the engine. The tool is looked up by name,
     * and executed with the provided arguments. Results are sent back to the
     * client via the TaskResultPayload.
     *
     * @param server  the Minecraft server
     * @param sender  the player who sent the packet (the companion's owner)
     * @param payload the execute tool payload
     */
    public static void onExecuteTool(MinecraftServer server, ServerPlayer sender,
                                      ExecuteToolPayload payload) {
        // Find the companion
        Optional<MineAgentEngine.CompanionState> companionState =
                MineAgentEngine.getCompanion(payload.companionId());

        if (companionState.isEmpty()) {
            System.err.println("[MineAgent] ExecuteTool: companion not found: "
                    + payload.companionId());
            sendToolResult(sender, payload, false, "Companion not found");
            return;
        }

        CompanionEntity companion = companionState.get().companion;

        // Verify the sender owns this companion
        if (!companion.serverPlayerOwner().getUUID().equals(sender.getUUID())) {
            System.err.println("[MineAgent] ExecuteTool: player "
                    + sender.getName().getString()
                    + " does not own companion " + payload.companionId());
            sendToolResult(sender, payload, false, "Companion not found or not owned");
            return;
        }

        // Look up the tool
        Optional<Tool> toolOpt = ToolRegistry.get(payload.toolName());
        if (toolOpt.isEmpty()) {
            System.err.println("[MineAgent] ExecuteTool: unknown tool: "
                    + payload.toolName());
            sendToolResult(sender, payload, false,
                    "Unknown tool: " + payload.toolName());
            return;
        }

        Tool tool = toolOpt.get();

        // Parse arguments
        JsonObject args;
        try {
            args = JsonParser.parseString(payload.arguments()).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[MineAgent] ExecuteTool: invalid arguments: "
                    + e.getMessage());
            sendToolResult(sender, payload, false, "Invalid arguments: " + e.getMessage());
            return;
        }

        // Execute the tool
        AtomicBoolean replied = new AtomicBoolean();
        try {
            tool.onServerCall(payload.toolCallId(), args, companion, result -> {
                if (!replied.compareAndSet(false, true)) return;
                boolean success = isSuccessfulToolResult(result);
                sendToolResult(sender, payload, success,
                        result != null ? result : "{}");
                System.out.println("[MineAgent] Tool " + payload.toolName()
                        + " completed for companion " + payload.companionId());
            });
        } catch (Throwable failure) {
            if (replied.compareAndSet(false, true)) {
                sendToolResult(sender, payload, false,
                        "Tool failed: " + failure.getClass().getSimpleName()
                                + " - " + String.valueOf(failure.getMessage()));
            }
        }
    }

    /**
     * Handle an incoming CancelTasksPayload.
     * <p>
     * Cancels all running tasks for the specified companion. This includes:
     * <ul>
     *   <li>Any running companion task in the priority auction</li>
     *   <li>Any in-progress agent loop turn</li>
     * </ul>
     *
     * @param server  the Minecraft server
     * @param sender  the player who sent the packet
     * @param payload the cancel tasks payload
     */
    public static void onCancelTasks(MinecraftServer server, ServerPlayer sender,
                                      CancelTasksPayload payload) {
        Optional<MineAgentEngine.CompanionState> companionState =
                MineAgentEngine.getCompanion(payload.companionId());

        if (companionState.isEmpty()) {
            System.err.println("[MineAgent] CancelTasks: companion not found: "
                    + payload.companionId());
            return;
        }

        var state = companionState.get();

        // Verify ownership
        if (!state.companion.serverPlayerOwner().getUUID().equals(sender.getUUID())) {
            System.err.println("[MineAgent] CancelTasks: player does not own companion");
            return;
        }

        // Cancel the running task in the priority auction
        state.auction.cancelTask();

        // Cancel the current agent loop turn
        state.loop.cancel();

        System.out.println("[MineAgent] All tasks cancelled for companion "
                + payload.companionId());
    }

    private static void sendToolResult(ServerPlayer sender, ExecuteToolPayload request,
                                       boolean success, String message) {
        com.mineagent.engine.network.MineAgentNetwork.sendTaskResultTo(sender,
                new com.mineagent.api.network.payload.TaskResultPayload(
                        request.companionId(), request.toolCallId(), success,
                        message != null ? message : ""));
    }

    private static boolean isSuccessfulToolResult(String result) {
        if (result == null || result.isBlank()) return true;
        try {
            var parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject()) return true;
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("error")) return false;
            return !object.has("success") || object.get("success").getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /**
     * Handle an incoming ClientUiActionPayload.
     * <p>
     * Processes UI actions from the client:
     * <ul>
     *   <li>{@code open_chat} — open the companion chat interface</li>
     *   <li>{@code toggle_reflex} — enable/disable a specific reflex</li>
     *   <li>{@code pause} — pause the companion's agent loop</li>
     *   <li>{@code resume} — resume the companion's agent loop</li>
     * </ul>
     *
     * @param server  the Minecraft server
     * @param sender  the player who sent the packet
     * @param payload the client UI action payload
     */
    public static void onClientUiAction(MinecraftServer server, ServerPlayer sender,
                                         ClientUiActionPayload payload) {
        String action = payload.action();
        String data = payload.data();

        // ── Actions that do NOT require an existing companion ──
        switch (action) {
            case "chat" -> {
                // Route the chat screen message to the specific companion
                // identified by companionId. If companionId doesn't match
                // any living companion owned by the sender, fall back to
                // the standard @mention / primary-companion routing.
                if (data != null && !data.isBlank()) {
                    UUID cid = payload.companionId();
                    if (cid != null) {
                        Optional<MineAgentEngine.CompanionState> cs =
                                MineAgentEngine.getCompanion(cid);
                        if (cs.isPresent()
                                && cs.get().companion.serverPlayerOwner()
                                        .getUUID().equals(sender.getUUID())) {
                            String ownerName = sender.getName().getString();
                            cs.get().loop.onOwnerMessage("[" + ownerName + "]: " + data);
                            return;
                        }
                    }
                    // Fallback: standard routing
                    MineAgentEngine.onPlayerChat(sender, data);
                }
                return;
            }
            case "spawn_companion" -> {
                // Equivalent to "/mineagent quick": spawn using the global
                // config (provider/model/apiKey), model name as display name.
                var cfg = MineAgentEngine.getConfig();
                String apiKey = cfg.llm().apiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c[MineAgent] No API key in config! Edit mineagent.json first."));
                    return;
                }
                String rawName = cfg.companion().name();
                String name = (rawName == null || rawName.isBlank() || "MineAgent".equals(rawName))
                        ? cfg.llm().model() : rawName;
                MineAgentEngine.spawnCompanion(sender, name, cfg.llm().provider(),
                        apiKey, cfg.llm().model(),
                        cfg.llm().baseUrl().isEmpty() ? null : cfg.llm().baseUrl(),
                        cfg.llm().temperature(), null, false);
                return;
            }
            case "request_companions" -> {
                // Recover missed spawn packets using authoritative ownership
                // and both IDs; the client must never guess from player lists.
                for (var owned : MineAgentEngine.getCompanionsByOwner(sender.getUUID())) {
                    MineAgentNetwork.sendUiActionTo(sender,
                            owned.companion.companionId(), "companion_spawned",
                            owned.companion.serverPlayer().getUUID().toString());
                }
                return;
            }
            default -> { /* fall through to companion-scoped actions */ }
        }

        // ── Companion-scoped actions: require the companion to exist and
        //    to be owned by the sender ──
        Optional<MineAgentEngine.CompanionState> companionState =
                MineAgentEngine.getCompanion(payload.companionId());

        if (companionState.isEmpty()) {
            System.err.println("[MineAgent] ClientUiAction: companion not found: "
                    + payload.companionId());
            return;
        }

        var state = companionState.get();

        // Verify ownership
        if (!state.companion.serverPlayerOwner().getUUID().equals(sender.getUUID())) {
            System.err.println("[MineAgent] ClientUiAction: player does not own companion");
            return;
        }

        switch (action) {
            case "open_chat" -> {
                // The client handles opening the chat UI; this is a no-op on
                // the server side but we log it for awareness
                System.out.println("[MineAgent] Player " + sender.getName().getString()
                        + " opened chat for companion " + payload.companionId());
            }
            case "toggle_reflex" -> {
                // data format: "<reflexId>=<true|false>" from the chat screen,
                // or bare "<reflexId>" to flip the current state.
                if (data != null && !data.isEmpty()) {
                    String reflexId = data;
                    Boolean explicitState = null;
                    int eq = data.indexOf('=');
                    if (eq > 0) {
                        reflexId = data.substring(0, eq);
                        String rawState = data.substring(eq + 1).trim();
                        // Boolean.parseBoolean silently maps every typo to
                        // false, so malformed/untrusted packets used to turn a
                        // reflex off. Only the protocol's two legal values are
                        // accepted as an explicit state.
                        if ("true".equalsIgnoreCase(rawState)) {
                            explicitState = true;
                        } else if ("false".equalsIgnoreCase(rawState)) {
                            explicitState = false;
                        } else {
                            System.err.println("[MineAgent] Invalid reflex state: " + rawState);
                            return;
                        }
                    }
                    reflexId = reflexId.trim();
                    if (reflexId.isEmpty()) return;
                    var reflex = ReflexRegistry.get(reflexId);
                    if (reflex.isPresent()) {
                        boolean target = explicitState != null
                                ? explicitState
                                : !reflex.get().isEnabled(state.companion);
                        if (target) {
                            reflex.get().enable(state.companion);
                        } else {
                            reflex.get().disable(state.companion);
                        }
                        System.out.println("[MineAgent] Reflex '" + reflexId + "' "
                                + (target ? "enabled" : "disabled")
                                + " for companion " + payload.companionId());
                    } else {
                        System.err.println("[MineAgent] Unknown reflex: " + reflexId);
                    }
                }
            }
            case "remove_companion" -> {
                MineAgentEngine.despawnCompanion(payload.companionId(), true);
                System.out.println("[MineAgent] Companion " + payload.companionId()
                        + " removed by owner");
            }
            case "pause" -> {
                state.auction.cancelTask();
                state.lifecycle.pauseByOwner();
                System.out.println("[MineAgent] Companion " + payload.companionId()
                        + " paused by owner");
            }
            case "resume" -> {
                if (state.lifecycle.resumeByOwner()) {
                    System.out.println("[MineAgent] Companion " + payload.companionId()
                            + " resumed by owner");
                } else {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§c[MineAgent] Dead companions must be respawned first."));
                }
            }
            default -> System.err.println("[MineAgent] Unknown UI action: " + action);
        }
    }
}
