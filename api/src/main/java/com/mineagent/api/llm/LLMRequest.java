package com.mineagent.api.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional state-aware request contract.  Existing providers can keep using
 * the legacy method on {@code LLMProvider}; the default adapter converts this
 * request back to the old message representation.
 */
public record LLMRequest(String baseUrl, String apiKey, String model,
                         List<ChatMessage> messages,
                         List<ChatMessage> fallbackMessages,
                         List<ConversationItem> items,
                         List<Map<String, Object>> tools,
                         double temperature, int maxTokens,
                         String reasoningEffort, String previousResponseId,
                         String conversationId, String promptCacheKey,
                         Duration deadline, boolean allowServerCompaction,
                         int cacheableMessageCount) {
    /** Legacy callers do not declare a reusable history boundary. */
    public LLMRequest(String baseUrl, String apiKey, String model,
                      List<ChatMessage> messages, List<ChatMessage> fallbackMessages,
                      List<ConversationItem> items, List<Map<String, Object>> tools,
                      double temperature, int maxTokens, String reasoningEffort,
                      String previousResponseId, String conversationId, String promptCacheKey,
                      Duration deadline, boolean allowServerCompaction) {
        this(baseUrl, apiKey, model, messages, fallbackMessages, items, tools, temperature,
                maxTokens, reasoningEffort, previousResponseId, conversationId, promptCacheKey,
                deadline, allowServerCompaction, 0);
    }
    /** Source-compatible constructor for integrations written against the
     * stage-0 state-aware request contract. The same messages are used as the
     * complete local fallback when no separate history is supplied. */
    public LLMRequest(String baseUrl, String apiKey, String model,
                      List<ChatMessage> messages,
                      List<ConversationItem> items,
                      List<Map<String, Object>> tools,
                      double temperature, int maxTokens,
                      String reasoningEffort, String previousResponseId,
                      String conversationId, String promptCacheKey,
                      Duration deadline, boolean allowServerCompaction) {
        this(baseUrl, apiKey, model, messages, messages, items, tools,
                temperature, maxTokens, reasoningEffort, previousResponseId,
                conversationId, promptCacheKey, deadline, allowServerCompaction);
    }

    public LLMRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        fallbackMessages = fallbackMessages == null
                ? messages : List.copyOf(fallbackMessages);
        items = items == null ? List.of() : List.copyOf(items);
        tools = tools == null ? List.of() : List.copyOf(tools);
        // This prefix ends before transient observations; it is a capability hint,
        // not a provider-specific wire parameter or a response cache.
        cacheableMessageCount = Math.max(0, Math.min(messages.size(), cacheableMessageCount));
        model = model == null ? "" : model.trim();
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null : reasoningEffort.trim();
        previousResponseId = blankToNull(previousResponseId);
        conversationId = blankToNull(conversationId);
        promptCacheKey = blankToNull(promptCacheKey);
        deadline = deadline == null || deadline.isNegative() || deadline.isZero()
                ? Duration.ofSeconds(30) : deadline;
        maxTokens = Math.max(1, maxTokens);
        temperature = Double.isFinite(temperature)
                ? Math.max(0.0, Math.min(2.0, temperature)) : 0.0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
