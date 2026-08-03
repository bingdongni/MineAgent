package com.mineagent.engine.pathing.moves;

/**
 * Input state for path execution. Describes what inputs should be active
 * during a single tick of movement execution.
 */
public class Input {

    private float forward;
    private float strafe;
    private boolean jumping;
    private boolean sneaking;
    private boolean sprinting;
    private boolean leftClick;
    private boolean rightClick;

    public Input() {
        clear();
    }

    /** Clear all inputs to idle state. */
    public void clear() {
        this.forward = 0;
        this.strafe = 0;
        this.jumping = false;
        this.sneaking = false;
        this.sprinting = false;
        this.leftClick = false;
        this.rightClick = false;
    }

    // --- Forward ---
    public Input forward(float value) { this.forward = value; return this; }
    public float forward() { return forward; }

    // --- Strafe ---
    public Input strafe(float value) { this.strafe = value; return this; }
    public float strafe() { return strafe; }

    // --- Jump ---
    public Input jumping(boolean value) { this.jumping = value; return this; }
    public boolean jumping() { return jumping; }

    // --- Sneak ---
    public Input sneaking(boolean value) { this.sneaking = value; return this; }
    public boolean sneaking() { return sneaking; }

    // --- Sprint ---
    public Input sprinting(boolean value) { this.sprinting = value; return this; }
    public boolean sprinting() { return sprinting; }

    // --- Left click ---
    public Input leftClick(boolean value) { this.leftClick = value; return this; }
    public boolean leftClick() { return leftClick; }

    // --- Right click ---
    public Input rightClick(boolean value) { this.rightClick = value; return this; }
    public boolean rightClick() { return rightClick; }

    @Override
    public String toString() {
        return "Input[fwd=" + forward + ", str=" + strafe
                + (jumping ? ", JUMP" : "")
                + (sneaking ? ", SNEAK" : "")
                + (sprinting ? ", SPRINT" : "")
                + (leftClick ? ", LCLICK" : "")
                + (rightClick ? ", RCLICK" : "")
                + "]";
    }
}
