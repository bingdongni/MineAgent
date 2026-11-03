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
    String finishReason,
    List<ConversationItem> outputItems
) {

    /** Source-compatible constructor for providers without opaque output items. */
    public LLMResponse(String id, String model, Choice choice, Usage usage,
                       String finishReason) {
        this(id, model, choice, usage, finishReason, List.of());
    }

    public LLMResponse {
        outputItems = outputItems == null ? List.of() : List.copyOf(outputItems);
    }

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
                        int cachedPromptTokens, int cacheCreationPromptTokens,
                        boolean cacheReadReported, boolean cacheWriteReported) {
        /** Legacy providers do not establish observability by supplying a default zero. */
        public Usage(int promptTokens, int completionTokens, int totalTokens,
                     int cachedPromptTokens, int cacheCreationPromptTokens) {
            this(promptTokens, completionTokens, totalTokens, cachedPromptTokens,
                    cacheCreationPromptTokens, promptTokens > 0 && cachedPromptTokens > 0,
                    promptTokens > 0 && cacheCreationPromptTokens > 0);
        }
        public Usage {
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
            cachedPromptTokens = Math.max(0, Math.min(promptTokens, cachedPromptTokens));
            cacheCreationPromptTokens = Math.max(0,
                    Math.min(promptTokens - cachedPromptTokens, cacheCreationPromptTokens));
            totalTokens = (int) Math.min(Integer.MAX_VALUE,
                    Math.max((long) totalTokens, (long) promptTokens + completionTokens));
        }
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
