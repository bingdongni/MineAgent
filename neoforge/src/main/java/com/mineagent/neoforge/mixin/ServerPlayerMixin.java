package com.mineagent.neoforge.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * Mixin into {@link ServerPlayer} that adds a companion ID field.
 * <p>
 * This allows the engine to identify which ServerPlayer instances are
 * AI-controlled companions vs. regular human players without maintaining
 * a separate lookup map. The field is {@code null} for human players
 * and set to a unique UUID for companion entities.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Unique
    private UUID mineagent$companionId = null;

    /**
     * Check whether this player is an AI-controlled companion.
     *
     * @return {@code true} if this player is a MineAgent companion
     */
    @Unique
    public boolean mineagent$isCompanion() {
        return mineagent$companionId != null;
    }

    /**
     * Get the companion ID, or {@code null} if this is a human player.
     *
     * @return the companion UUID, or null
     */
    @Unique
    public UUID mineagent$getCompanionId() {
        return mineagent$companionId;
    }

    /**
     * Set the companion ID. Called once when the companion is spawned.
     *
     * @param companionId the unique ID for this companion
     */
    @Unique
    public void mineagent$setCompanionId(UUID companionId) {
        this.mineagent$companionId = companionId;
    }

    /**
     * Clear the companion ID. Called when the companion is removed.
     */
    @Unique
    public void mineagent$clearCompanionId() {
        this.mineagent$companionId = null;
    }
}
