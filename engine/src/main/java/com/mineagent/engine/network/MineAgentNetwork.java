package com.mineagent.engine.network;

import com.mineagent.api.network.payload.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Network channel registration and packet handling for MineAgent.
 * <p>
 * Defines the protocol identifiers for all custom payload types and provides
 * serialization/deserialization helpers for the platform modules.
 * <p>
 * Payload directions:
 * <ul>
 *   <li><b>Server → Client:</b> {@link TaskResultPayload}, {@link PathDebugPayload}</li>
 *   <li><b>Client → Server:</b> {@link ExecuteToolPayload}, {@link CancelTasksPayload},
 *       {@link ClientUiActionPayload}</li>
 * </ul>
 * <p>
 * Platform modules (Fabric, NeoForge) call {@link #registerPayloadTypes()} at
 * initialization to register the custom payload types with the mod loader's
 * networking API. They then use the read/write methods here to serialize
 * and deserialize payloads from {@link FriendlyByteBuf}.
 */
public final class MineAgentNetwork {

    private static final int MAX_PATH_NODES = 4096;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_ACTION_LENGTH = 64;
    private static final int MAX_UI_DATA_LENGTH = 4096;
    private static final int MAX_TASK_MESSAGE_LENGTH = 4096;
    private static final int MAX_TOOL_JSON_LENGTH = 32768;

    // ── Channel identifier ─────────────────────────────────────────

    /** The main network channel ID. */
    public static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "main");

    // ── Payload type identifiers ───────────────────────────────────

    /** Server→Client: Task result payload ID. */
    public static final ResourceLocation TASK_RESULT_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "task_result");

    /** Server→Client: Path debug payload ID. */
    public static final ResourceLocation PATH_DEBUG_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "path_debug");

    /** Client→Server: Execute tool payload ID. */
    public static final ResourceLocation EXECUTE_TOOL_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "execute_tool");

    /** Client→Server: Cancel tasks payload ID. */
    public static final ResourceLocation CANCEL_TASKS_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "cancel_tasks");

    /** Client→Server: Client UI action payload ID. */
    public static final ResourceLocation CLIENT_UI_ACTION_ID =
            ResourceLocation.fromNamespaceAndPath("mineagent", "client_ui_action");

    private MineAgentNetwork() {}

    // ── Server→Client UI push hook ─────────────────────────────────

    /**
     * Platform-implemented sender for server→client UI action pushes.
     * Registered by the platform module at init time (Fabric sets this to
     * a {@code ServerPlayNetworking.send} bridge).
     */
    public interface UiActionSender {
        void send(net.minecraft.server.level.ServerPlayer player,
                  ClientUiActionPayload payload);
    }

    public interface TaskResultSender {
        void send(net.minecraft.server.level.ServerPlayer player, TaskResultPayload payload);
    }

    public interface PathDebugSender {
        void send(net.minecraft.server.level.ServerPlayer player, PathDebugPayload payload);
    }

    private static volatile UiActionSender uiActionSender = null;
    private static volatile TaskResultSender taskResultSender;
    private static volatile PathDebugSender pathDebugSender;

    /** Register the platform's UI action sender. Called once at mod init. */
    public static void setUiActionSender(UiActionSender sender) {
        uiActionSender = sender;
    }

    public static void setTaskResultSender(TaskResultSender sender) {
        taskResultSender = sender;
    }

    public static void setPathDebugSender(PathDebugSender sender) {
        pathDebugSender = sender;
    }

    /**
     * Push a UI action to a player's client (companion chat message, task
     * update, spawn/despawn notification). No-op when the platform has not
     * registered a sender or the player is null.
     */
    public static void sendUiActionTo(net.minecraft.server.level.ServerPlayer player,
                                      UUID companionId, String action, String data) {
        UiActionSender sender = uiActionSender;
        if (sender == null || player == null || companionId == null || action == null) return;
        try {
            if (action.isBlank() || action.length() > MAX_ACTION_LENGTH) {
                throw new IllegalArgumentException("invalid UI action length");
            }
            sender.send(player, new ClientUiActionPayload(companionId, action,
                    truncateUtf16(data, MAX_UI_DATA_LENGTH)));
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to push UI action '" + action
                    + "': " + e.getMessage());
        }
    }

    public static void sendTaskResultTo(net.minecraft.server.level.ServerPlayer player,
                                        TaskResultPayload payload) {
        TaskResultSender sender = taskResultSender;
        if (sender == null || player == null || payload == null
                || payload.companionId() == null) return;
        try {
            String id = payload.toolCallId() == null || payload.toolCallId().isBlank()
                    ? "unknown" : truncateUtf16(payload.toolCallId(), MAX_ID_LENGTH);
            String message = truncateUtf16(payload.message(), MAX_TASK_MESSAGE_LENGTH);
            sender.send(player, new TaskResultPayload(
                    payload.companionId(), id, payload.success(), message));
        } catch (Exception error) {
            System.err.println("[MineAgent] Failed to push task result: " + error.getMessage());
        }
    }

    public static void sendPathDebugTo(net.minecraft.server.level.ServerPlayer player,
                                       PathDebugPayload payload) {
        PathDebugSender sender = pathDebugSender;
        if (sender == null || player == null || payload == null) return;
        try {
            sender.send(player, payload);
        } catch (Exception error) {
            System.err.println("[MineAgent] Failed to push path debug data: " + error.getMessage());
        }
    }

    private static String truncateUtf16(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    // ── Registration ───────────────────────────────────────────────

    /**
     * Register all payload types with the mod loader's networking API.
     * <p>
     * This is a no-op at the engine level - the actual registration is
     * done by the platform modules (Fabric/NeoForge) using their
     * respective networking APIs. This method serves as a hook point.
     * <p>
     * Platform modules should call this and then register the individual
     * payload types with their mod loader's registry.
     */
    public static void registerPayloadTypes() {
        System.out.println("[MineAgent] Network payload types ready for registration");
    }

    // ── TaskResultPayload serialization ────────────────────────────

    /**
     * Write a TaskResultPayload to a byte buffer.
     */
    public static void writeTaskResultPayload(FriendlyByteBuf buf, TaskResultPayload payload) {
        buf.writeUUID(payload.companionId());
        buf.writeUtf(payload.toolCallId(), MAX_ID_LENGTH);
        buf.writeBoolean(payload.success());
        buf.writeUtf(payload.message(), MAX_TASK_MESSAGE_LENGTH);
    }

    /**
     * Read a TaskResultPayload from a byte buffer.
     */
    public static TaskResultPayload readTaskResultPayload(FriendlyByteBuf buf) {
        UUID companionId = buf.readUUID();
        String toolCallId = buf.readUtf(MAX_ID_LENGTH);
        boolean success = buf.readBoolean();
        String message = buf.readUtf(MAX_TASK_MESSAGE_LENGTH);
        return new TaskResultPayload(companionId, toolCallId, success, message);
    }

    // ── PathDebugPayload serialization ─────────────────────────────

    /**
     * Write a PathDebugPayload to a byte buffer.
     */
    public static void writePathDebugPayload(FriendlyByteBuf buf, PathDebugPayload payload) {
        buf.writeUUID(payload.companionId());
        buf.writeVarInt(payload.pathNodes().size());
        for (double[] node : payload.pathNodes()) {
            buf.writeDouble(node[0]);
            buf.writeDouble(node[1]);
            buf.writeDouble(node[2]);
        }
        buf.writeVarInt(payload.currentNode());
        buf.writeUtf(payload.pathStatus(), MAX_ID_LENGTH);
    }

    /**
     * Read a PathDebugPayload from a byte buffer.
     */
    public static PathDebugPayload readPathDebugPayload(FriendlyByteBuf buf) {
        UUID companionId = buf.readUUID();
        int nodeCount = buf.readVarInt();
        if (nodeCount < 0 || nodeCount > MAX_PATH_NODES) {
            throw new IllegalArgumentException("invalid path node count");
        }
        List<double[]> pathNodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            pathNodes.add(new double[]{x, y, z});
        }
        int currentNode = buf.readVarInt();
        String pathStatus = buf.readUtf(MAX_ID_LENGTH);
        return new PathDebugPayload(companionId, pathNodes, currentNode, pathStatus);
    }

    // ── ExecuteToolPayload serialization ───────────────────────────

    /**
     * Write an ExecuteToolPayload to a byte buffer.
     */
    public static void writeExecuteToolPayload(FriendlyByteBuf buf, ExecuteToolPayload payload) {
        buf.writeUUID(payload.companionId());
        buf.writeUtf(payload.toolCallId(), MAX_ID_LENGTH);
        buf.writeUtf(payload.toolName(), MAX_ID_LENGTH);
        buf.writeUtf(payload.arguments(), MAX_TOOL_JSON_LENGTH);
    }

    /**
     * Read an ExecuteToolPayload from a byte buffer.
     */
    public static ExecuteToolPayload readExecuteToolPayload(FriendlyByteBuf buf) {
        UUID companionId = buf.readUUID();
        String toolCallId = buf.readUtf(MAX_ID_LENGTH);
        String toolName = buf.readUtf(MAX_ID_LENGTH);
        String arguments = buf.readUtf(MAX_TOOL_JSON_LENGTH);
        return new ExecuteToolPayload(companionId, toolCallId, toolName, arguments);
    }

    // ── CancelTasksPayload serialization ───────────────────────────

    /**
     * Write a CancelTasksPayload to a byte buffer.
     */
    public static void writeCancelTasksPayload(FriendlyByteBuf buf, CancelTasksPayload payload) {
        buf.writeUUID(payload.companionId());
    }

    /**
     * Read a CancelTasksPayload from a byte buffer.
     */
    public static CancelTasksPayload readCancelTasksPayload(FriendlyByteBuf buf) {
        UUID companionId = buf.readUUID();
        return new CancelTasksPayload(companionId);
    }

    // ── ClientUiActionPayload serialization ────────────────────────

    /**
     * Write a ClientUiActionPayload to a byte buffer.
     */
    public static void writeClientUiActionPayload(FriendlyByteBuf buf, ClientUiActionPayload payload) {
        buf.writeUUID(payload.companionId());
        buf.writeUtf(payload.action(), MAX_ACTION_LENGTH);
        buf.writeUtf(payload.data() != null ? payload.data() : "", MAX_UI_DATA_LENGTH);
    }

    /**
     * Read a ClientUiActionPayload from a byte buffer.
     */
    public static ClientUiActionPayload readClientUiActionPayload(FriendlyByteBuf buf) {
        UUID companionId = buf.readUUID();
        String action = buf.readUtf(MAX_ACTION_LENGTH);
        String data = buf.readUtf(MAX_UI_DATA_LENGTH);
        return new ClientUiActionPayload(companionId, action, data.isEmpty() ? null : data);
    }
}
