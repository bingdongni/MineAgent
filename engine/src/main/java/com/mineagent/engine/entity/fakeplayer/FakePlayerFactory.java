package com.mineagent.engine.entity.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.mineagent.engine.entity.SafeTeleport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;

import java.util.UUID;
import java.util.Map;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Factory for creating fake ServerPlayer instances that operate without a
 * real client connection.
 * <p>
 * A fake player is a fully functional ServerPlayer on the server side -
 * it can mine, build, interact with blocks and entities, and be seen by
 * other players - but it has no human behind it. Its inputs are driven
 * by the AI agent loop.
 * <p>
 * The fake player renders identically to a human player — same model,
 * same animations, same skin system. By default, the companion uses the
 * Steve or Alex skin (determined by UUID parity). Custom skins can be
 * applied via {@link SkinLoader} or by setting a skin player name in the
 * config file.
 * <p>
 * The factory:
 * <ol>
 *   <li>Creates a ServerPlayer with an offline GameProfile</li>
 *   <li>Sets up a {@link FakeConnection} and {@link FakePlayerNetworkHandler}</li>
 *   <li>Configures the game mode (survival with creative-like reach)</li>
 *   <li>Sets the spawn position and initial inventory</li>
 *   <li>Registers the player with the server's player list</li>
 *   <li>Optionally loads a custom skin asynchronously</li>
 * </ol>
 */
public final class FakePlayerFactory {

    private static volatile Field playersByUuidField;

    private FakePlayerFactory() {}

    /**
     * Create a new fake ServerPlayer without a real client connection.
     *
     * @param server    the Minecraft server instance
     * @param profile   the GameProfile for the fake player (use
     *                  {@link #createOfflineProfile} to generate one)
     * @param level     the server level to spawn the player in
     * @param spawnPos  the block position to spawn at
     * @return a fully initialized ServerPlayer registered with the server
     */
    public static ServerPlayer create(MinecraftServer server, GameProfile profile,
                                       ServerLevel level, BlockPos spawnPos) {
        return create(server, profile, level, spawnPos, GameType.SURVIVAL);
    }

    /**
     * Create a fake player in the requested mode before it becomes visible.
     * Applying the mode before Player Info/entity registration avoids one tick
     * where observers and mod hooks see an incorrect survival player.
     */
    public static ServerPlayer create(MinecraftServer server, GameProfile profile,
                                       ServerLevel level, BlockPos spawnPos,
                                       GameType requestedGameType) {

        // 1. Create the ServerPlayer using the 1.21.1 constructor
        // In 1.21.1, ServerPlayer requires ClientInformation as the 4th parameter
        ServerPlayer player = new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());

        // 2. Create the fake connection (no real network)
        FakeConnection connection = new FakeConnection();

        try {

        // 3. Create the fake network handler and wire it up
        FakePlayerNetworkHandler handler = new FakePlayerNetworkHandler(
                server, connection, player);

        // Set the connection on the player
        player.connection = handler;

        // 4. Set up the custom game mode for the fake player
        //    The access widener (mineagent.accesswidener) makes the
        //    gameMode field accessible and mutable, so we can set it
        //    directly without reflection.
        FakePlayerGameMode gameMode = new FakePlayerGameMode(player);
        player.gameMode = gameMode;

        // A null/omitted mode is explicitly survival. Hardcore is represented
        // by MineAgent as SURVIVAL plus an independent permanent-death policy.
        GameType gameType = requestedGameType == null
                ? GameType.SURVIVAL : requestedGameType;
        player.setGameMode(gameType);

        // 6. Validate the complete two-block body volume and its support.
        // A fixed east offset can be a wall, lava, or open air at a cliff;
        // registering there creates a companion that suffocates or falls
        // before its first navigation tick.
        BlockPos spawnOrigin = spawnPos.offset(1, 0, 0);
        // ServerPlayConnectionEvents.JOIN can run before the owner's chunk
        // ticket has reached ChunkMap's immediately-available cache. The
        // safety search deliberately uses getChunkNow() so it never loads a
        // large ring of terrain, but that made every candidate look unloaded
        // during automatic restore. Prepare only the search origin chunk
        // synchronously; it is beside an online player and therefore the one
        // chunk whose availability creation is allowed to require.
        level.getChunkAt(spawnOrigin);
        if (!SafeTeleport.near(player, level, spawnOrigin,
                player.getYRot(), player.getXRot())) {
            throw new IllegalStateException("no safe loaded spawn position nearby");
        }

        // 7. Set initial health and food
        player.setHealth(20.0f);
        player.getFoodData().setFoodLevel(20);

        // 8. Set experience level
        player.experienceLevel = 0;
        player.setExperiencePoints(0);

        // 9. Register with the server's player list
        registerWithServer(server, player, level, gameType);

            return player;
        } catch (RuntimeException failure) {
            // Creation crosses three ownership domains (PlayerList, level
            // entity tracking, and Netty resources). If any later setup step
            // fails, roll all of them back; the caller cannot do this because
            // create() never returned the partially initialized player.
            rollbackFailedCreate(server, level, player, connection);
            throw failure;
        }
    }

    private static void rollbackFailedCreate(MinecraftServer server, ServerLevel level,
                                             ServerPlayer player,
                                             FakeConnection connection) {
        // Registration publishes Player Info before the entity spawn packet so
        // clients can construct a player entity. If any later step fails, undo
        // that client-side profile as part of the same ownership rollback.
        broadcastPlayerInfoRemoval(server.getPlayerList(), player);
        try {
            if (level.getEntity(player.getUUID()) == player) {
                level.removePlayerImmediately(player,
                        net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        } catch (RuntimeException cleanupError) {
            System.err.println("[MineAgent] Failed to roll back fake entity: "
                    + cleanupError.getMessage());
        }
        server.getPlayerList().getPlayers().remove(player);
        removeFromUuidIndex(server.getPlayerList(), player);
        connection.close();
    }

    /**
     * Create an offline GameProfile for a fake player.
     * Uses UUID v3 (name-based) to ensure consistent UUIDs for the same name.
     *
     * <p>No name prefix is added — the companion uses exactly the name the
     * player chose. This ensures compatibility with skin mods (SkinsRestorer,
     * CustomSkinLoader) that match by player name.
     *
     * <p>The offline UUID determines the default skin:
     * <ul>
     *   <li>Even UUID → Steve</li>
     *   <li>Odd UUID → Alex</li>
     * </ul>
     *
     * @param name the display name for the fake player
     * @return a GameProfile with an offline UUID
     */
    public static GameProfile createOfflineProfile(String name) {
        // Generate a deterministic offline UUID from the name
        // No prefix — use the name directly for skin mod compatibility
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(offlineUuid, name);
    }

    /**
     * Asynchronously load and apply a custom skin to a fake player.
     *
     * <p>Equivalent to {@link #applySkinAsync(ServerPlayer, String, Runnable)}
     * with a no-op completion callback.
     *
     * @param player     the fake ServerPlayer to apply the skin to
     * @param skinName   the Minecraft player name whose skin to use
     */
    public static void applySkinAsync(ServerPlayer player, String skinName) {
        applySkinAsync(player, skinName, () -> {});
    }

    /**
     * Asynchronously load and apply a custom skin to a fake player, then run a
     * completion callback on the server thread.
     *
     * <p>Fetches the skin texture from Mojang's API by player name. The skin is
     * applied by updating the GameProfile's textures property and re-syncing the
     * player entity to all clients. The {@code onDone} callback is ALWAYS
     * invoked exactly once on the server thread — whether the skin was found
     * and applied, or not found (in which case the default Steve/Alex skin is
     * kept). This lets callers schedule follow-up work (e.g. persisting the
     * companion state AFTER the skin is cached, so the saved record carries the
     * real skin value instead of null) without racing the async skin load.
     *
     * @param player     the fake ServerPlayer to apply the skin to
     * @param skinName   the Minecraft player name whose skin to use
     * @param onDone     run on the server thread after the skin load finishes
     *                   (found or not). If null, treated as a no-op.
     */
    public static void applySkinAsync(ServerPlayer player, String skinName, Runnable onDone) {
        Runnable done = onDone != null ? onDone : () -> {};
        SkinLoader.loadSkinAsync(skinName, skinOpt -> {
            player.server.execute(() -> {
                // A request may finish after /remove or owner disconnect.
                // Invoking the persistence callback then would recreate the
                // just-deleted companion record from stale state.
                if (player.isRemoved() || !player.connection.isAcceptingMessages()) return;

                if (skinOpt.isEmpty()) {
                    System.out.println("[MineAgent] No skin found for '" + skinName
                            + "', keeping default Steve/Alex");
                    done.run();
                    return;
                }

                SkinLoader.SkinResult skin = skinOpt.get();
                SkinLoader.cacheSkin(player.getUUID(), skin);
                var profile = player.getGameProfile();
                profile.getProperties().removeAll("textures");
                profile.getProperties().put("textures", skin.toProperty());

                // Refresh skin for all online viewers
                refreshPlayerInfo(player);

                System.out.println("[MineAgent] Skin applied to '" + player.getName().getString()
                        + "' from player '" + skinName + "'");
                done.run();
            });
        });
    }

    /**
     * Refresh player info for all online viewers so they see updated skin/profile.
     * Uses remove + re-add pattern to force clients to re-fetch the player's profile.
     */
    public static void refreshPlayerInfo(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) return;

        var removePacket = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                java.util.List.of(player.getUUID()));
        var initializePacket = net.minecraft.network.protocol.game
                .ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(
                        java.util.List.of(player));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.equals(player) || viewer.connection == null) continue;
            // Remove player info, then re-add to force skin refresh
            viewer.connection.send(removePacket);
            viewer.connection.send(initializePacket);
        }
    }

    /**
     * Register the fake player with the server's player list and add it
     * to the world.
     */
    private static void registerWithServer(MinecraftServer server,
                                            ServerPlayer player, ServerLevel level,
                                            GameType gameType) {
        PlayerList playerList = server.getPlayerList();

        // Add the player to the server's player list
        // This is normally done via PlayerList.placeNewPlayer, but that method
        // sends login packets. For fake players, we manually register instead.
        // M1 fix: ensure thread safety by running on the main server thread
        // (this method should already be called from the server thread, but
        // we use a synchronized block as a safety net)
        synchronized (playerList.getPlayers()) {
            boolean duplicate = playerList.getPlayers().stream().anyMatch(existing ->
                    existing.getUUID().equals(player.getUUID())
                            || existing.getGameProfile().getName()
                                    .equalsIgnoreCase(player.getGameProfile().getName()));
            if (duplicate) {
                throw new IllegalStateException("duplicate fake-player profile: "
                        + player.getGameProfile().getName());
            }
            Map<UUID, ServerPlayer> uuidIndex = playerUuidIndex(playerList);
            ServerPlayer indexed = uuidIndex.putIfAbsent(player.getUUID(), player);
            if (indexed != null) {
                throw new IllegalStateException("duplicate fake-player UUID: "
                        + player.getUUID());
            }
            playerList.getPlayers().add(player);
        }

        // ClientPacketListener cannot create an entity.minecraft.player from
        // its spawn packet until the UUID already exists in the Player Info
        // map. addFreshEntity starts entity tracking synchronously, so sending
        // this packet afterward caused "Server attempted to add player prior
        // to sending player info" followed by "Skipping Entity". Preserve the
        // same ordering used by the vanilla login path: profile first, entity
        // second. The fake connection is excluded because it drops all output.
        broadcastPlayerInfoInitialization(playerList, player);

        // Add the player entity to the level
        // In 1.21.1, use addFreshEntity instead of addNewPlayer (which was removed)
        boolean added = level.addFreshEntity(player);
        if (!added) {
            // create() owns rollback across PlayerList, client Player Info and
            // level tracking. Throwing here lets that one path undo all three.
            throw new IllegalStateException("could not add fake-player entity for '"
                    + player.getName().getString() + "'");
        } else {
            System.out.println("[MineAgent] Fake player entity added to level at ("
                    + player.getX() + ", " + player.getY() + ", " + player.getZ() + ")");
        }

        // Re-apply after registration so vanilla publishes the requested mode
        // and abilities. The former hard-coded SURVIVAL assignment silently
        // undid every non-survival mode selected during construction.
        player.setGameMode(gameType == null ? GameType.SURVIVAL : gameType);

        System.out.println("[MineAgent] Fake player '" + player.getName().getString()
                + "' registered with server (UUID: " + player.getUUID() + ")");
    }

    /**
     * Unregister a fake player from the server.
     * Removes the player from the player list and the level.
     *
     * @param server the Minecraft server instance
     * @param player the fake player to remove
     */
    public static void unregister(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) return;
        PlayerList playerList = server.getPlayerList();

        // This player bypassed PlayerList.placeNewPlayer(), so explicitly
        // remove its manually-added tab-list entry from every real client.
        broadcastPlayerInfoRemoval(playerList, player);

        // Remove from the level
        if (player.serverLevel() != null) {
            player.serverLevel().removePlayerImmediately(player,
                    net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        }

        // Remove from the player list
        playerList.getPlayers().remove(player);
        removeFromUuidIndex(playerList, player);

        // Close the fake connection
        if (player.connection instanceof FakePlayerNetworkHandler handler) {
            handler.disconnect("Companion removed");
        }

        SkinLoader.evictCachedSkin(player.getUUID());

        System.out.println("[MineAgent] Fake player '" + player.getName().getString()
                + "' unregistered from server");
    }

    /** Publish a fake player's profile before entity tracking emits its spawn. */
    private static void broadcastPlayerInfoInitialization(PlayerList playerList,
                                                           ServerPlayer player) {
        var packet = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
                .createPlayerInitializing(java.util.List.of(player));
        for (ServerPlayer viewer : java.util.List.copyOf(playerList.getPlayers())) {
            if (viewer != player && viewer.connection != null) {
                viewer.connection.send(packet);
            }
        }
    }

    /** Remove a manually-published fake-player profile from every real client. */
    private static void broadcastPlayerInfoRemoval(PlayerList playerList,
                                                   ServerPlayer player) {
        var packet = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                java.util.List.of(player.getUUID()));
        for (ServerPlayer viewer : java.util.List.copyOf(playerList.getPlayers())) {
            if (viewer != player && viewer.connection != null) {
                viewer.connection.send(packet);
            }
        }
    }

    /**
     * Resolve PlayerList's private UUID index across Mojang/intermediary names.
     * Maintaining only the public players list makes getPlayer(UUID) return
     * null for an entity that is visibly online, breaking vanilla ownership,
     * command and lookup paths. Reflection keeps this engine code portable to
     * Fabric and NeoForge without a platform-only accessor dependency.
     */
    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> playerUuidIndex(PlayerList list) {
        Field cached = playersByUuidField;
        if (cached != null) {
            try {
                return (Map<UUID, ServerPlayer>) cached.get(list);
            } catch (ReflectiveOperationException ignored) {
                playersByUuidField = null;
            }
        }

        for (String name : new String[]{"playersByUUID", "field_14354"}) {
            try {
                Field field = PlayerList.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(list);
                if (value instanceof Map<?, ?> map) {
                    playersByUuidField = field;
                    return (Map<UUID, ServerPlayer>) map;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next mapped name, then structural fallback below.
            }
        }

        // Structural fallback: spawn is owner-initiated, so the correct map
        // already maps at least that real player's UUID to the same instance.
        for (Field field : PlayerList.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(list);
                if (!(value instanceof Map<?, ?> map)) continue;
                boolean matchesOnlinePlayer = list.getPlayers().stream()
                        .anyMatch(online -> map.get(online.getUUID()) == online);
                if (matchesOnlinePlayer) {
                    playersByUuidField = field;
                    return (Map<UUID, ServerPlayer>) map;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Continue scanning; failure is reported once below.
            }
        }
        throw new IllegalStateException("could not access PlayerList UUID index");
    }

    private static void removeFromUuidIndex(PlayerList list, ServerPlayer player) {
        try {
            Map<UUID, ServerPlayer> index = playerUuidIndex(list);
            // Conditional removal cannot erase a real replacement that logged
            // in with the same UUID after a partially failed teardown.
            index.remove(player.getUUID(), player);
        } catch (RuntimeException cleanupError) {
            System.err.println("[MineAgent] Failed to remove fake-player UUID index: "
                    + cleanupError.getMessage());
        }
    }
}
