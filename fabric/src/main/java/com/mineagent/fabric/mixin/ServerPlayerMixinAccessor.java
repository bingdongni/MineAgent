package com.mineagent.fabric.mixin;

import java.util.UUID;

/**
 * Interface implemented by {@link ServerPlayerMixin} via {@code @Implements}.
 * Allows the engine to check if a ServerPlayer is an AI companion
 * and get/set its companion ID.
 *
 * <p>This avoids the need for instanceof checks or reflection.
 */
public interface ServerPlayerMixinAccessor {
    boolean mineagent$isCompanion();
    UUID mineagent$getCompanionId();
    void mineagent$setCompanionId(UUID companionId);
    void mineagent$clearCompanionId();
}
