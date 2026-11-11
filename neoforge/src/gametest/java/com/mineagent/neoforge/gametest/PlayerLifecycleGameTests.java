package com.mineagent.neoforge.gametest;

import com.mineagent.gametest.PlayerLifecycleScenarios;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Separate development-only mod, never included in the distributable JAR. */
@Mod("mineagent_lifecycle_tests")
public final class PlayerLifecycleGameTests {
    public PlayerLifecycleGameTests(IEventBus modBus) {
        modBus.addListener((RegisterGameTestsEvent event) -> {
            event.register(PlayerLifecycleGameTests.class);
            try {
                GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(
                        new java.io.File(System.getProperty("mineagent.lifecycleReport"))));
            } catch (javax.xml.parsers.ParserConfigurationException failure) {
                throw new IllegalStateException("Cannot initialize native test report", failure);
            }
        });
    }

    @GameTest(templateNamespace = "mineagent_lifecycle_tests", template = "empty")
    public static void createsRemovesAndRecreatesTheSameProfile(GameTestHelper helper) {
        // NeoForge deliberately exposes a read-only view: reproduce the original spawn failure.
        boolean readOnly = false;
        try {
            var players = helper.getLevel().getServer().getPlayerList().getPlayers();
            players.add(null);
            players.remove(null);
        }
        catch (UnsupportedOperationException expected) { readOnly = true; }
        helper.assertTrue(readOnly, "NeoForge fixture must exercise its actual read-only player view");
        PlayerLifecycleScenarios.createsRemovesAndRecreatesTheSameProfile(helper);
    }

    @GameTest(templateNamespace = "mineagent_lifecycle_tests", template = "empty")
    public static void duplicateCreationPreservesTheExistingPlayer(GameTestHelper helper) {
        PlayerLifecycleScenarios.duplicateCreationPreservesTheExistingPlayer(helper);
    }

    @GameTest(templateNamespace = "mineagent_lifecycle_tests", template = "empty")
    public static void failedEntityRegistrationRollsBackOnlyTheNewPlayer(GameTestHelper helper) {
        PlayerLifecycleScenarios.failedEntityRegistrationRollsBackOnlyTheNewPlayer(helper);
    }
}
