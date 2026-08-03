package com.mineagent.api.agent.tool;

/**
 * Represents a single tool call from the LLM, with an ID and arguments.
 */
public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    }
}
