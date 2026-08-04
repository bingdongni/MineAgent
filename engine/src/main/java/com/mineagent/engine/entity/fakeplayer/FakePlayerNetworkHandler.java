package com.mineagent.engine.entity.fakeplayer;

import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Server game listener for a fake player that has no remote client.
 *
 * <p>The listener deliberately drops outbound packets and never applies the
 * keep-alive timeout logic from the vanilla listener. Its tick is invoked by
 * {@link com.mineagent.engine.MineAgentEngine#onServerTick} because the
 * associated {@link FakeConnection} is not registered in the server
 * connection listener's private connection list.</p>
 */
public class FakePlayerNetworkHandler extends ServerGamePacketListenerImpl {

    private final FakeConnection fakeConnection;
    private long lastKeepAliveId;
    private int tickCounter;

    public FakePlayerNetworkHandler(MinecraftServer server, FakeConnection connection,
                                    net.minecraft.server.level.ServerPlayer player) {
        super(server, connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        this.fakeConnection = connection;
    }

    /**
     * Supply the network-owned half of the vanilla player tick.
     *
     * <p>Vanilla splits player work between two different methods.
     * {@code ServerLevel} already invokes {@link
     * net.minecraft.server.level.ServerPlayer#tick()}, which advances the
     * game mode, containers and server bookkeeping. A real network listener
     * additionally invokes {@link net.minecraft.server.level.ServerPlayer#doTick()},
     * which consumes movement input and advances item-use/living-entity
     * physics. Calling {@code player.tick()} here duplicated the first half
     * and omitted the second half, so movement input was never consumed.</p>
     *
     * <p>Calling {@code super.tick()} is also incorrect: vanilla restores the
     * body to the last client-reported coordinates after {@code doTick()}, but
     * a fake player never sends movement packets. Directly invoking
     * {@code doTick()} preserves input-driven movement.</p>
     */
    @Override
    public void tick() {
        tickCounter++;
        if (tickCounter % 75 == 0) {
            lastKeepAliveId++;
        }
        this.player.doTick();
    }

    @Override
    public void handleKeepAlive(ServerboundKeepAlivePacket packet) {
        lastKeepAliveId = packet.getId();
    }

    @Override
    public void handlePong(ServerboundPongPacket packet) {
        // No remote client exists.
    }

    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
        // Fake players never originate custom payloads.
    }

    @Override
    public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
        // Fake players do not download resource packs.
    }

    @Override
    public void send(Packet<?> packet) {
        // Outbound packets are intentionally discarded by FakeConnection too.
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        fakeConnection.close();
    }

    public void disconnect(String reason) {
        fakeConnection.close();
    }

    @Override
    public boolean isAcceptingMessages() {
        return fakeConnection.isConnected();
    }

    public long getLastKeepAliveId() {
        return lastKeepAliveId;
    }
}
