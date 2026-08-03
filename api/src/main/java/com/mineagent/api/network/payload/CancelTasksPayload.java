package com.mineagent.api.network.payload;

import java.util.UUID;

/**
 * Payload: cancel all running tasks for a companion.
 */
public record CancelTasksPayload(UUID companionId) {
    public CancelTasksPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
    }
}
