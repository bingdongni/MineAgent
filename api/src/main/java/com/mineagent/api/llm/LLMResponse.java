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

    /**
     * Token usage statistics normalized across providers.
     *
     * @param cachedPromptTokens prompt tokens served from a provider cache
     * @param cacheCreationPromptTokens prompt tokens written to an explicit
     *                                  cache (primarily Anthropic)
     */
    public record Usage(int promptTokens, int completionTokens, int totalTokens,
                        int cachedPromptTokens, int cacheCreationPromptTokens) {
        /** Source-compatible constructor for providers without cache metrics. */
        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, 0, 0);
        }

        public double promptCacheHitRate() {
            return promptTokens <= 0 ? 0.0
                    : Math.min(1.0, Math.max(0.0,
                    (double) cachedPromptTokens / promptTokens));
        }
    }
}
