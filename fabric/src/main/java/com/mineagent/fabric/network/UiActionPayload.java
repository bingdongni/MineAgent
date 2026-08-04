package com.mineagent.fabric.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Bidirectional UI action payload (Fabric 1.21.1 custom payload).
 *
 * <p>Client → Server: chat messages, reflex toggles, spawn/remove requests
 * from the companion chat screen.
 *
 * <p>Server → Client: companion chat messages, task updates, spawn/despawn
 * notifications pushed to the owner's client UI.
 *
 * <p>This is the Fabric-side wire type. The engine works with the plain
 * {@code ClientUiActionPayload} record from the api module (which has no
 * Minecraft dependency); conversion happens at the network boundary.
 */
public record UiActionPayload(UUID companionId, String action, String data)
        implements CustomPacketPayload {

    private static final int MAX_ACTION = 64;
    private static final int MAX_DATA = 4096;

    public static final CustomPacketPayload.Type<UiActionPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("mineagent", "ui_action"));

    public static final StreamCodec<FriendlyByteBuf, UiActionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.companionId());
                        buf.writeUtf(p.action(), MAX_ACTION);
                        buf.writeUtf(p.data() == null ? "" : p.data(), MAX_DATA);
                    },
                    buf -> new UiActionPayload(buf.readUUID(),
                            buf.readUtf(MAX_ACTION), buf.readUtf(MAX_DATA)));

    public UiActionPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
        if (action == null || action.isBlank() || action.length() > MAX_ACTION)
            throw new IllegalArgumentException("invalid action");
        if (data != null && data.length() > MAX_DATA)
            throw new IllegalArgumentException("data too long");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
