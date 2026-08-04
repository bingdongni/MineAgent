package com.mineagent.api.network.payload;

import java.util.UUID;

/**
 * Payload: report a task result to the client.
 */
public record TaskResultPayload(UUID companionId, String toolCallId,
                                 boolean success, String message) {
    public TaskResultPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
        if (toolCallId == null) toolCallId = "";
        if (message == null) message = "";
        if (toolCallId.length() > 128 || message.length() > 4096) {
            throw new IllegalArgumentException("task-result payload too large");
        }
    }
}
