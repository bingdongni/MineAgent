package com.mineagent.gametest;

import com.mineagent.engine.entity.fakeplayer.FakeConnection;
import com.mineagent.engine.entity.fakeplayer.FakePlayerFactory;
import com.mineagent.engine.entity.fakeplayer.FakePlayerNetworkHandler;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Shared native scenarios: no AgentLoop, skin lookup or model transport is started. */
public final class PlayerLifecycleScenarios {
    private PlayerLifecycleScenarios() {}

    public static void createsRemovesAndRecreatesTheSameProfile(GameTestHelper helper) {
        BlockPos origin = prepareFloor(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        GameProfile profile = FakePlayerFactory.createOfflineProfile("MaLifecycle");
        int initialCount = server.getPlayerList().getPlayers().size();
        for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE)) {
            ServerPlayer player = FakePlayerFactory.create(server, profile, level, origin, mode);
            try {
                helper.assertTrue(server.getPlayerList().getPlayer(profile.getId()) == player,
                        "Creation must publish the UUID lookup");
                helper.assertTrue(level.getEntity(profile.getId()) == player,
                        "Creation must publish the world entity");
                helper.assertTrue(server.getPlayerList().getPlayers().size() == initialCount + 1,
                        "Exactly one player must be registered");
                helper.assertTrue(player.gameMode.getGameModeForPlayer() == mode,
                        "Registration must preserve the requested game mode");
                helper.assertTrue(player.connection.isAcceptingMessages(), "Fake connection must be live");
            } finally {
                FakePlayerFactory.unregister(server, player);
            }
            helper.assertTrue(server.getPlayerList().getPlayer(profile.getId()) == null,
                    "Removal must clear the UUID lookup");
            helper.assertTrue(level.getEntity(profile.getId()) == null,
                    "Removal must clear the world entity");
            helper.assertTrue(server.getPlayerList().getPlayers().size() == initialCount,
                    "Removal must restore the original player count");
            helper.assertTrue(!player.connection.isAcceptingMessages(), "Removal must close the fake connection");
        }
        helper.succeed();
    }

    public static void duplicateCreationPreservesTheExistingPlayer(GameTestHelper helper) {
        BlockPos origin = prepareFloor(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer observer = FakePlayerFactory.create(server,
                FakePlayerFactory.createOfflineProfile("MaDupObserver"), level, origin);
        RecordingHandler recorder = recordPackets(observer);
        ServerPlayer original = null;
        try {
            GameProfile profile = FakePlayerFactory.createOfflineProfile("MaDuplicate");
            original = FakePlayerFactory.create(server, profile, level, origin);
            recorder.packets.clear();
            expectCreateFailure(helper, profile, origin);
            helper.assertTrue(server.getPlayerList().getPlayer(profile.getId()) == original,
                    "Rejected duplicate must preserve the original UUID entry");
            helper.assertTrue(level.getEntity(profile.getId()) == original,
                    "Rejected duplicate must preserve the original body");
            helper.assertTrue(recorder.packets.stream().noneMatch(packet ->
                            packet instanceof ClientboundPlayerInfoRemovePacket removal
                                    && removal.profileIds().contains(profile.getId())),
                    "Rejected duplicate must not remove the existing player's client profile");
        } finally {
            if (original != null) FakePlayerFactory.unregister(server, original);
            FakePlayerFactory.unregister(server, observer);
        }
        helper.succeed();
    }

    public static void failedEntityRegistrationRollsBackOnlyTheNewPlayer(GameTestHelper helper) {
        BlockPos origin = prepareFloor(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        ServerPlayer observer = FakePlayerFactory.create(server,
                FakePlayerFactory.createOfflineProfile("MaFailObserver"), level, origin);
        RecordingHandler recorder = recordPackets(observer);
        GameProfile profile = FakePlayerFactory.createOfflineProfile("MaFailedCreate");
        var occupant = new ArmorStand(EntityType.ARMOR_STAND, level);
        occupant.setUUID(profile.getId());
        occupant.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + 3.5);
        try {
            helper.assertTrue(level.addFreshEntity(occupant), "Fixture entity must occupy the UUID");
            int initialCount = server.getPlayerList().getPlayers().size();
            expectCreateFailure(helper, profile, origin);
            helper.assertTrue(server.getPlayerList().getPlayer(profile.getId()) == null,
                    "Failed world registration must roll back the player UUID entry");
            helper.assertTrue(server.getPlayerList().getPlayers().size() == initialCount,
                    "Failed world registration must roll back the player list");
            helper.assertTrue(level.getEntity(profile.getId()) == occupant,
                    "Rollback must preserve an unrelated entity with the same UUID");
            int initialization = -1, removal = -1;
            for (int i = 0; i < recorder.packets.size(); i++) {
                Packet<?> packet = recorder.packets.get(i);
                if (packet instanceof ClientboundPlayerInfoUpdatePacket update
                        && update.entries().stream().anyMatch(entry -> entry.profileId().equals(profile.getId())))
                    initialization = i;
                if (packet instanceof ClientboundPlayerInfoRemovePacket remove
                        && remove.profileIds().contains(profile.getId())) removal = i;
            }
            helper.assertTrue(initialization >= 0 && removal > initialization,
                    "A published client profile must be withdrawn after registration fails");
        } finally {
            occupant.discard();
            FakePlayerFactory.unregister(server, observer);
        }
        helper.succeed();
    }

    private static void expectCreateFailure(GameTestHelper helper, GameProfile profile, BlockPos origin) {
        try {
            ServerPlayer unexpected = FakePlayerFactory.create(helper.getLevel().getServer(), profile,
                    helper.getLevel(), origin);
            FakePlayerFactory.unregister(helper.getLevel().getServer(), unexpected);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Creation should reject this conflicting UUID");
    }

    private static BlockPos prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
        }
        return helper.absolutePos(new BlockPos(2, 1, 2));
    }

    private static RecordingHandler recordPackets(ServerPlayer observer) {
        ((FakePlayerNetworkHandler) observer.connection).disconnect("Replace test observer handler");
        var handler = new RecordingHandler(observer);
        observer.connection = handler;
        return handler;
    }

    private static final class RecordingHandler extends FakePlayerNetworkHandler {
        private final List<Packet<?>> packets = new ArrayList<>();

        RecordingHandler(ServerPlayer player) {
            super(player.getServer(), new FakeConnection(), player);
        }

        @Override public void send(Packet<?> packet) { packets.add(packet); }
    }
}
