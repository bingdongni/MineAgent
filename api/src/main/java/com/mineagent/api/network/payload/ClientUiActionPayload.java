package com.mineagent.api.network.payload;

import java.util.UUID;

/**
 * Payload: a UI action from the client (open chat, toggle reflex, etc.).
 */
public record ClientUiActionPayload(UUID companionId, String action,
                                     String data) {
    public ClientUiActionPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action required");
        if (action.length() > 64) throw new IllegalArgumentException("action too long");
        if (data != null && data.length() > 4096) throw new IllegalArgumentException("data too long");
    }
}
