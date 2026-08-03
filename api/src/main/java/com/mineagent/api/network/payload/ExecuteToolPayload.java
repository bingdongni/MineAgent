package com.mineagent.api.network.payload;

import java.util.UUID;

/**
 * Payload: execute a tool call on the server.
 */
public record ExecuteToolPayload(UUID companionId, String toolCallId,
                                  String toolName, String arguments) {
    public ExecuteToolPayload {
        if (companionId == null) throw new IllegalArgumentException("companionId required");
        if (toolCallId == null || toolCallId.isBlank()) throw new IllegalArgumentException("toolCallId required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName required");
        if (arguments == null) arguments = "{}";
        // Keep this limit identical to every platform wire codec. Accepting
        // 129-256 characters here only defers failure until packet encoding.
        if (toolCallId.length() > 128 || toolName.length() > 128
                || arguments.length() > 32768) {
            throw new IllegalArgumentException("execute-tool payload too large");
        }
    }
}
