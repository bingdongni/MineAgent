package com.mineagent.api.llm;

import java.util.List;
import java.util.Map;

/**
 * The response from an LLM completion call.
 */
public record LLMResponse(
    String id,
    String model,
    Choice choice,
    Usage usage,
    String finishReason
) {

    /** A single choice in the response. */
    public record Choice(int index, ChatMessage message, String finishReason) {}

    /** Token usage statistics. */
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}
