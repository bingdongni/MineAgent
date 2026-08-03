package com.mineagent.engine.entity.fakeplayer;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.net.SocketAddress;

/**
 * A Connection subclass that doesn't actually connect anywhere.
 * <p>
 * Used by {@link FakePlayerFactory} to create a ServerPlayer that has a
 * valid network connection object but never sends or receives real packets.
 * All outgoing packets are silently dropped.
 *
 * <p><b>1.21.1 fix:</b> The server tick loop calls
 * {@code ServerCommonNetworkHandler.enableFlush()} → {@code Connection.flushChannel()}
 * → {@code Connection.flush()} on every player connection every tick.
 * {@code flush()} accesses {@code this.channel.eventLoop()}, which would NPE
 * if {@code channel} were null. We therefore set {@code channel} to an
 * {@link EmbeddedChannel} in the constructor — an in-memory Netty channel
 * with a pre-registered event loop — following the approach used by
 * <a href="https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/patches/FakeClientConnection.java">Carpet Mod</a>.
 *
 * <p>The {@code channel} field is private in vanilla. We set it via reflection
 * so this code works on both Fabric (Yarn mappings — field name "channel")
 * and NeoForge (Mojang mappings — field name "channel") without needing
 * platform-specific access wideners.
 *
 * <p>We also override the {@code 3-argument send(Packet, PacketSendListener, boolean)}
 * method, because in 1.21.1 the 1-arg and 2-arg {@code send} overloads both
 * delegate to it; overriding it is sufficient to drop all outgoing packets.
 */
public class FakeConnection extends Connection {

    private volatile boolean open = true;
    private EmbeddedChannel embeddedChannel;

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // Set channel to an EmbeddedChannel so that flush()/eventLoop() calls
        // in the server tick loop do not throw NPE.
        // Use reflection for cross-platform compatibility (Fabric + NeoForge).
        setChannelField();
    }

    private void setChannelField() {
        // Try common field names across mappings
        String[] candidates = {"channel", "field_11651"};
        for (String name : candidates) {
            try {
                Field f = Connection.class.getDeclaredField(name);
                if (installChannel(f)) return;
            } catch (NoSuchFieldException ignored) {
                // try next name
            }
        }
        // Last resort: scan all declared fields for one of type Channel
        for (Field f : Connection.class.getDeclaredFields()) {
            if (f.getType() == io.netty.channel.Channel.class) {
                if (installChannel(f)) return;
            }
        }
        System.err.println("[MineAgent] Could not set Connection.channel — "
                + "server tick may crash with NPE");
        throw new IllegalStateException("Could not initialize fake Connection.channel");
    }

    private boolean installChannel(Field field) {
        EmbeddedChannel candidate = null;
        try {
            field.setAccessible(true);
            candidate = new EmbeddedChannel();
            field.set(this, candidate);
            embeddedChannel = candidate;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A failed candidate still owns queued reference-counted state.
            // Release it before trying another mapped field.
            if (candidate != null) candidate.finishAndReleaseAll();
            return false;
        }
    }

    /**
     * Silently drop all outgoing packets — the fake player has no real client.
     * This override catches the 3-arg send used internally by 1-arg and 2-arg send.
     */
    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        if (listener != null) {
            listener.onSuccess();
        }
    }

    /**
     * Always report as connected — the fake player should never be
     * disconnected by the server's connection-check logic.
     */
    @Override
    public boolean isConnected() {
        return open;
    }

    /**
     * No remote address for a fake connection.
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return new SocketAddress() {
            @Override
            public String toString() {
                return "fake-connection";
            }
        };
    }

    /**
     * Handle disconnect gracefully — mark as closed without trying to
     * send any disconnect packets to a real client.
     */
    @Override
    public void disconnect(net.minecraft.network.chat.Component reason) {
        // Vanilla may call Connection.disconnect directly. Route that path
        // through the same idempotent cleanup used by explicit despawn.
        close();
    }

    /**
     * Prevent the server from detecting this connection as "idle" or
     * timing it out. The fake player's keep-alive is handled by
     * {@link FakePlayerNetworkHandler}.
     */
    @Override
    public void setReadOnly() {
        // No-op
    }

    /**
     * Manually close the fake connection during companion removal.
     */
    public void close() {
        open = false;
        // EmbeddedChannel owns queued reference-counted buffers. Merely
        // flipping the connection flag leaks those buffers across repeated
        // companion spawn/despawn cycles in the same JVM.
        EmbeddedChannel channel = embeddedChannel;
        embeddedChannel = null;
        if (channel != null) channel.finishAndReleaseAll();
    }
}
