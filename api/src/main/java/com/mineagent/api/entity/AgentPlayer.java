package com.mineagent.api.entity;

import java.util.UUID;

/**
 * The companion entity — an AI-controlled player.
 * Provides AI-specific operations without exposing Minecraft internals.
 *
 * <p>The companion is NOT a regular player — it has no human behind it.
 * Its inputs are driven by the {@link InputDriver} and its behavior
 * is governed by the task chain priority auction.
 *
 * <p>Engine module provides the concrete implementation that bridges
 * these abstract methods to Minecraft ServerPlayer internals.
 */
public abstract class AgentPlayer {

    /** The companion's unique ID. */
    public abstract UUID companionId();

    /** The companion's display name. */
    public abstract String companionName();

    /** The owner (human player) who created this companion — UUID. */
    public abstract UUID ownerUuid();

    /** The owner's display name. */
    public abstract String ownerName();

    /** Get companion's block position as "x,y,z" string. */
    public abstract String blockPositionStr();

    /** Get companion's X coordinate. */
    public abstract double posX();

    /** Get companion's Y coordinate. */
    public abstract double posY();

    /** Get companion's Z coordinate. */
    public abstract double posZ();

    /** Get companion's health (0-20). */
    public abstract float health();

    /** Get companion's max health. */
    public abstract float maxHealth();

    /** Get companion's food level (0-20). */
    public abstract int foodLevel();

    /** Get companion's air supply. */
    public abstract int airSupply();

    /** Is the companion in water? */
    public abstract boolean isInWater();

    /** Is the companion alive? */
    public abstract boolean isAlive();

    /** Hold a specific hotbar slot (1-9). */
    public abstract void holdInHand(int slot);

    /** Get the item in the companion's main hand as item ID string. */
    public abstract String mainHandItemId();

    /** Get the companion's dimension key. */
    public abstract String dimensionKey();
}
