package com.mineagent.api.llm;

import java.util.List;
import java.util.Map;

/**
 * A chat message in the LLM conversation.
 */
public record ChatMessage(String role, String content, List<ToolCallRef> toolCalls, String toolCallId) {

    /** Create a system message. */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    /** Create a user message. */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    /** Create an assistant message. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    /** Create an assistant message with tool calls. */
    public static ChatMessage assistantWithTools(List<ToolCallRef> toolCalls) {
        return new ChatMessage("assistant", null, toolCalls, null);
    }

    /** Create an assistant message with both content and tool calls. */
    public static ChatMessage assistant(String content, List<ToolCallRef> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    /** Create a tool result message. */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    /** A reference to a tool call in an assistant message. */
    public record ToolCallRef(String id, String name, String arguments) {}
}
