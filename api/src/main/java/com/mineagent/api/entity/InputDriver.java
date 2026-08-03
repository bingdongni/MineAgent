package com.mineagent.api.entity;

/**
 * Low-level input driver — sets the vanilla movement/input fields on the
 * companion's ServerPlayer to simulate player input.
 *
 * <p>Controls:
 * <ul>
 *   <li>Forward/strafe movement (xxa, zza)</li>
 *   <li>Jump, sneak, sprint</li>
 *   <li>Left/right click</li>
 * </ul>
 *
 * <p>Each call overrides the previous state; the fields are consumed by
 * the vanilla movement code each tick.
 */
public interface InputDriver {

    /** Set forward movement (-1=backward, 0=still, 1=forward). */
    void setForward(float value);

    /** Set strafe movement (-1=left, 0=still, 1=right). */
    void setStrafe(float value);

    /** Set jump. */
    void setJumping(boolean jumping);

    /** Set sneak. */
    void setSneaking(boolean sneaking);

    /** Set sprint. */
    void setSprinting(boolean sprinting);

    /** Simulate left-click (attack/destroy). */
    void leftClick();

    /** Simulate right-click (use/interact). */
    void rightClick();

    /** Clear all inputs (reset to idle). */
    void clear();
}
