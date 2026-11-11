package com.mineagent.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PlayerLifecycleGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
    public void createsRemovesAndRecreatesTheSameProfile(GameTestHelper helper) {
        PlayerLifecycleScenarios.createsRemovesAndRecreatesTheSameProfile(helper);
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void duplicateCreationPreservesTheExistingPlayer(GameTestHelper helper) {
        PlayerLifecycleScenarios.duplicateCreationPreservesTheExistingPlayer(helper);
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void failedEntityRegistrationRollsBackOnlyTheNewPlayer(GameTestHelper helper) {
        PlayerLifecycleScenarios.failedEntityRegistrationRollsBackOnlyTheNewPlayer(helper);
    }
}
