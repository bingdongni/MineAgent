package com.mineagent.engine.entity.fakeplayer;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Minimal ServerGamePacketListenerImpl substitute for fake players.
 * <p>
 * Extends ServerGamePacketListenerImpl (required because ServerPlayer.connection
 * is declared as that type) but:
 * <ul>
 *   <li>Never kicks the player for timeout (handles keep-alive internally)</li>
 *   <li>Silently drops all outgoing packets</li>
 *   <li>Accepts all incoming packets without validation</li>
 * </ul>
 * <p>
 * In Minecraft 1.21.1, ServerGamePacketListenerImpl's constructor requires
 * a {@link CommonListenerCookie} parameter.
 */
public class FakePlayerNetworkHandler extends ServerGamePacketListenerImpl {

    /** The fake connection this handler uses. */
    private final FakeConnection fakeConnection;

    /** Keep-alive counter — tracks the last keep-alive ID we responded to. */
    private long lastKeepAliveId = 0;

    /** Tick counter for internal keep-alive scheduling. */
    private int tickCounter = 0;

    /** Last game tick processed, guarding against accidental double ticking. */
    private long lastProcessedGameTick = Long.MIN_VALUE;

    public FakePlayerNetworkHandler(MinecraftServer server, FakeConnection connection,
                                    net.minecraft.server.level.ServerPlayer player) {
        // 1.21.1: ServerGamePacketListenerImpl requires CommonListenerCookie
        super(server, connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        this.fakeConnection = connection;
    }

    /**
     * Called every server tick — now invoked explicitly by
     * {@link com.mineagent.engine.MineAgentEngine#onServerTick}.
     *
     * <p><b>Why explicit invocation is required:</b></p>
     * In 1.21.x, ServerGamePacketListenerImpl.tick() is driven by
     * ServerConnectionListener.tick() iterating its `connections` list.
     * Our FakeConnection is not in that list (we never registered it),
     * so vanilla never calls our tick(). We bridge the gap by calling
     * connection.tick() directly from MineAgentEngine.onServerTick().
     */
    @Override
    public void tick() {
        long gameTick = this.player.level().getGameTime();
        if (gameTick == lastProcessedGameTick) {
            return;
        }
        lastProcessedGameTick = gameTick;

        tickCounter++;
        if (tickCounter % 75 == 0) {
            lastKeepAliveId++;
        }

        // Log the first few ticks for debugging — confirms the fix is active
        if (tickCounter <= 3) {
            System.out.println("[MineAgent] FakePlayerNetworkHandler.tick() #" + tickCounter
                    + " for " + player.getName().getString()
                    + " — player.tick() will apply movement physics");
        }

        // Call player.tick() for movement physics (aiStep/travel).
        // See class javadoc for why this is necessary.
        // Vanilla ServerGamePacketListenerImpl.tick() calls doTick(), not
        // ServerPlayer.tick(). The latter never reaches Player.tick()/travel(),
        // so zza/xxa input was previously set but never consumed by physics.
        this.player.doTick();
    }

    /**
     * Handle keep-alive packets — auto-respond to prevent timeout kicks.
     */
    @Override
    public void handleKeepAlive(ServerboundKeepAlivePacket packet) {
        lastKeepAliveId = packet.getId();
    }

    /**
     * Handle pong packets — no-op for fake players.
     */
    @Override
    public void handlePong(ServerboundPongPacket packet) {
        // No-op
    }

    /**
     * Handle custom payload packets — no-op for fake players.
     */
    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
        // No-op
    }

    /**
     * Handle resource pack responses — no-op for fake players.
     */
    @Override
    public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
        // No-op
    }

    /**
     * Silently drop all outgoing packets — the fake player has no client.
     */
    @Override
    public void send(Packet<?> packet) {
        // No-op: drop all outgoing packets
    }

    /**
     * Handle disconnect gracefully.
     * In 1.21.1, onDisconnect accepts DisconnectionDetails instead of Component.
     */
    @Override
    public void onDisconnect(DisconnectionDetails details) {
        fakeConnection.close();
    }

    /**
     * Disconnect with a string reason.
     */
    public void disconnect(String reason) {
        fakeConnection.close();
    }

    /**
     * Check if the connection is still active.
     */
    @Override
    public boolean isAcceptingMessages() {
        return fakeConnection.isConnected();
    }

    public long getLastKeepAliveId() {
        return lastKeepAliveId;
    }
}
