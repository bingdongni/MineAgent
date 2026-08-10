package com.mineagent.fabric.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fabric wire records for the platform-neutral payloads in the API module. */
public final class MineAgentPayloads {

    private static final int MAX_ID = 128;
    private static final int MAX_NAME = 128;
    private static final int MAX_JSON = 32_768;
    private static final int MAX_MESSAGE = 4_096;
    private static final int MAX_PATH_NODES = 4_096;
    private static final int MAX_API_KEY = 16_384;
    private static final int MAX_MODEL = 256;
    private static final int MAX_BASE_URL = 2_048;

    private MineAgentPayloads() {}

    /** Dedicated credential-bearing request; credentials never enter chat commands. */
    public record CompanionSetup(String name, String providerId, String apiKey,
                                 boolean reuseStoredApiKey,
                                 String model, String baseUrl, double temperature,
                                 String reasoningEffort) implements CustomPacketPayload {
        public static final Type<CompanionSetup> TYPE = new Type<>(id("companion_setup"));
        public static final StreamCodec<FriendlyByteBuf, CompanionSetup> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.name, 64);
                    buf.writeUtf(p.providerId, 64);
                    buf.writeUtf(p.apiKey, MAX_API_KEY);
                    buf.writeBoolean(p.reuseStoredApiKey);
                    buf.writeUtf(p.model, MAX_MODEL);
                    buf.writeUtf(p.baseUrl, MAX_BASE_URL);
                    buf.writeDouble(p.temperature);
                    buf.writeUtf(p.reasoningEffort, 16);
                },
                buf -> new CompanionSetup(buf.readUtf(64), buf.readUtf(64),
                        buf.readUtf(MAX_API_KEY), buf.readBoolean(), buf.readUtf(MAX_MODEL),
                        buf.readUtf(MAX_BASE_URL), buf.readDouble(), buf.readUtf(16)));

        public CompanionSetup {
            // Reuse the loader-neutral constructor so wire and engine limits
            // cannot silently drift apart.
            var checked = new com.mineagent.api.network.payload.CompanionSetupPayload(
                    name, providerId, apiKey, reuseStoredApiKey, model, baseUrl, temperature,
                    reasoningEffort);
            name = checked.name();
            providerId = checked.providerId();
            apiKey = checked.apiKey();
            model = checked.model();
            baseUrl = checked.baseUrl();
            reasoningEffort = checked.reasoningEffort();
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ExecuteTool(UUID companionId, String toolCallId, String toolName,
                              String arguments) implements CustomPacketPayload {
        public static final Type<ExecuteTool> TYPE = new Type<>(id("execute_tool"));
        public static final StreamCodec<FriendlyByteBuf, ExecuteTool> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.companionId);
                    buf.writeUtf(p.toolCallId, MAX_ID);
                    buf.writeUtf(p.toolName, MAX_NAME);
                    buf.writeUtf(p.arguments, MAX_JSON);
                },
                buf -> new ExecuteTool(buf.readUUID(), buf.readUtf(MAX_ID),
                        buf.readUtf(MAX_NAME), buf.readUtf(MAX_JSON)));

        public ExecuteTool {
            if (companionId == null || blank(toolCallId) || blank(toolName)
                    || arguments == null) throw new IllegalArgumentException("invalid execute_tool payload");
            if (toolCallId.length() > MAX_ID || toolName.length() > MAX_NAME
                    || arguments.length() > MAX_JSON) throw new IllegalArgumentException("execute_tool payload too long");
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CancelTasks(UUID companionId) implements CustomPacketPayload {
        public static final Type<CancelTasks> TYPE = new Type<>(id("cancel_tasks"));
        public static final StreamCodec<FriendlyByteBuf, CancelTasks> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeUUID(p.companionId),
                buf -> new CancelTasks(buf.readUUID()));

        public CancelTasks {
            if (companionId == null) throw new IllegalArgumentException("companionId required");
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record TaskResult(UUID companionId, String toolCallId, boolean success,
                             String message) implements CustomPacketPayload {
        public static final Type<TaskResult> TYPE = new Type<>(id("task_result"));
        public static final StreamCodec<FriendlyByteBuf, TaskResult> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.companionId);
                    buf.writeUtf(p.toolCallId, MAX_ID);
                    buf.writeBoolean(p.success);
                    buf.writeUtf(p.message, MAX_MESSAGE);
                },
                buf -> new TaskResult(buf.readUUID(), buf.readUtf(MAX_ID),
                        buf.readBoolean(), buf.readUtf(MAX_MESSAGE)));

        public TaskResult {
            if (companionId == null || blank(toolCallId) || message == null)
                throw new IllegalArgumentException("invalid task_result payload");
            if (toolCallId.length() > MAX_ID || message.length() > MAX_MESSAGE)
                throw new IllegalArgumentException("task_result payload too long");
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PathDebug(UUID companionId, List<double[]> nodes, int currentNode,
                            String status) implements CustomPacketPayload {
        public static final Type<PathDebug> TYPE = new Type<>(id("path_debug"));
        public static final StreamCodec<FriendlyByteBuf, PathDebug> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUUID(p.companionId);
                    buf.writeVarInt(p.nodes.size());
                    for (double[] node : p.nodes) {
                        buf.writeDouble(node[0]);
                        buf.writeDouble(node[1]);
                        buf.writeDouble(node[2]);
                    }
                    buf.writeVarInt(p.currentNode);
                    buf.writeUtf(p.status, MAX_NAME);
                },
                buf -> {
                    UUID id = buf.readUUID();
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_PATH_NODES)
                        throw new IllegalArgumentException("invalid path node count");
                    List<double[]> nodes = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        nodes.add(new double[]{buf.readDouble(), buf.readDouble(), buf.readDouble()});
                    }
                    return new PathDebug(id, nodes, buf.readVarInt(), buf.readUtf(MAX_NAME));
                });

        public PathDebug {
            if (companionId == null || nodes == null || nodes.size() > MAX_PATH_NODES
                    || currentNode < -1 || currentNode >= nodes.size() || blank(status)
                    || status.length() > MAX_NAME) throw new IllegalArgumentException("invalid path_debug payload");
            for (double[] node : nodes) {
                if (node == null || node.length != 3 || !Double.isFinite(node[0])
                        || !Double.isFinite(node[1]) || !Double.isFinite(node[2]))
                    throw new IllegalArgumentException("invalid path node");
            }
            nodes = List.copyOf(nodes);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mineagent", path);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
