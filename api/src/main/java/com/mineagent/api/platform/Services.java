package com.mineagent.api.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Platform abstraction — provides access to server internals
 * without depending on a specific mod loader (Fabric/NeoForge)
 * or Minecraft classes.
 *
 * <p>Each loader module (fabric, neoforge) registers its implementation
 * at startup via {@link #register}.
 */
public final class Services {

    private static IPlatform PLATFORM = null;

    private Services() {}

    /** Register the platform implementation. Called once at mod init. */
    public static void register(IPlatform platform) {
        if (PLATFORM != null) throw new IllegalStateException("Platform already registered");
        PLATFORM = platform;
    }

    /** Get the platform. Throws if not yet registered. */
    public static IPlatform platform() {
        if (PLATFORM == null) throw new IllegalStateException("Platform not registered");
        return PLATFORM;
    }

    /**
     * The platform interface — abstracts loader-specific operations.
     * All methods use plain Java types, no Minecraft class references.
     */
    public interface IPlatform {

        /** Schedule a task to run on the next server tick. */
        void scheduleOnServer(Runnable task);

        /** Find a player's UUID by name. */
        Optional<UUID> findPlayerUuid(String name);

        /** Whether a player with the given UUID is online. */
        boolean isPlayerOnline(UUID uuid);

        /** Whether we're running on the client side. */
        boolean isClientSide();

        /** Get the server's current game time (ticks). */
        long gameTime();

        /** Server-thread, read-only public automation port; callers must check reach and visibility first. */
        default CapabilityObservation observeBlockCapabilities(com.mineagent.api.entity.AgentPlayer player,
                int x, int y, int z, String face) {
            return CapabilityObservation.unsupported();
        }

        /** Server-thread transfer through a public port. The caller must verify reach, visibility and permissions. */
        default PortTransferResult transferBlockItems(com.mineagent.api.entity.AgentPlayer player,
                int x, int y, int z, String face, int inventorySlot, String itemId, int amount, boolean intoBlock) {
            return PortTransferResult.unavailable("No public item-port adapter");
        }
    }
}
