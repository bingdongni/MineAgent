package com.mineagent.engine.pathing.execute;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.engine.pathing.moves.Input;

/**
 * Low-level input driver for path execution. Translates abstract Input
 * states into concrete InputDriver calls on the companion's ServerPlayer.
 *
 * <p>This is the bridge between the pathing system's abstract movement
 * inputs and the Minecraft server's player input fields.
 */
public class ExecHarness {

    private final AgentPlayer player;
    private final InputDriver inputDriver;

    public ExecHarness(AgentPlayer player) {
        this.player = player;
        // Get the input driver from the companion entity
        if (player instanceof com.mineagent.engine.entity.CompanionEntity companion) {
            this.inputDriver = companion.inputDriver();
        } else {
            // A silent no-op driver makes navigation stay RUNNING forever
            // while every input is discarded. This engine can only execute a
            // path for its concrete fake-player body, so fail at construction.
            throw new IllegalArgumentException(
                    "Path execution requires a CompanionEntity input driver");
        }
    }

    /**
     * Apply an Input state to the companion's input driver.
     */
    public void applyInput(Input input) {
        inputDriver.setForward(input.forward());
        inputDriver.setStrafe(input.strafe());
        inputDriver.setJumping(input.jumping());
        inputDriver.setSneaking(input.sneaking());
        inputDriver.setSprinting(input.sprinting());
        if (input.leftClick()) inputDriver.leftClick();
        if (input.rightClick()) inputDriver.rightClick();
    }

    /**
     * Clear all inputs (stop the companion).
     */
    public void clearInputs() {
        inputDriver.clear();
    }

    /** Get the companion player. */
    public AgentPlayer player() {
        return player;
    }

    /** Get the input driver. */
    public InputDriver inputDriver() {
        return inputDriver;
    }
}
