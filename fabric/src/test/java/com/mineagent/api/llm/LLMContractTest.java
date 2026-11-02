package com.mineagent.api.llm;

import com.mineagent.api.llm.provider.LLMProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LLMContractTest {
    @Test void cacheBoundaryIsExplicitBoundedAndOptInForLegacyCallers() {
        var messages = List.of(ChatMessage.system("static"), ChatMessage.user("live"));
        var legacy = new LLMRequest("https://example.test", "key", "model", messages, messages,
                List.of(), List.of(), 0, 10, null, null, null, null, Duration.ofSeconds(1), false);
        assertEquals(0, legacy.cacheableMessageCount());
        var current = new LLMRequest("https://example.test", "key", "model", messages, messages,
                List.of(), List.of(), 0, 10, null, null, null, null, Duration.ofSeconds(1), false, 1);
        assertEquals(1, current.cacheableMessageCount());
        var excessive = new LLMRequest("https://example.test", "key", "model", messages, messages,
                List.of(), List.of(), 0, 10, null, null, null, null, Duration.ofSeconds(1), false, 99);
        assertEquals(2, excessive.cacheableMessageCount());
    }
    @Test
    void malformedRelayUsageCannotCreateNegativeOrOverlappingCacheAccounting() {
        var empty = new LLMResponse.Usage(-10, -20, -30, 100, 100);
        assertEquals(new LLMResponse.Usage(0, 0, 0, 0, 0), empty);
        var overlap = new LLMResponse.Usage(100, 25, 1, 80, 80);
        assertEquals(80, overlap.cachedPromptTokens());
        assertEquals(20, overlap.cacheCreationPromptTokens());
        assertEquals(125, overlap.totalTokens());
        assertEquals(Integer.MAX_VALUE,
                new LLMResponse.Usage(Integer.MAX_VALUE, Integer.MAX_VALUE, 0).totalTokens());
    }

    @Test
    void requestNormalizesUnsafeAndMissingValues() {
        LLMRequest request = new LLMRequest("https://example.test", "key", "model",
                List.of(ChatMessage.user("hello")), List.of(ChatMessage.user("hello")),
                List.of(), List.of(),
                Double.NaN, 0, " ", " ", " ", " ", Duration.ZERO, false);
        assertEquals(0.0, request.temperature());
        assertEquals(1, request.maxTokens());
        assertEquals(Duration.ofSeconds(30), request.deadline());
        assertNull(request.reasoningEffort());
    }

    @Test
    void opaqueItemsAreNeverInterpretedAsText() {
        ConversationItem item = ConversationItem.opaque("compaction", "{\"type\":\"compaction\"}");
        assertTrue(item.opaque());
        assertNull(item.text());
        assertEquals("{\"type\":\"compaction\"}", item.rawJson());
    }

    @Test
    void legacyProviderReceivesStateAwareBridge() {
        LLMProvider provider = new LLMProvider() {
            @Override public String providerId() { return "test"; }
            @Override public String displayName() { return "test"; }
            @Override public String defaultBaseUrl() { return "https://example.test"; }
            @Override public boolean supportsModel(String model) { return true; }
            @Override public List<String> defaultModels() { return List.of(); }
            @Override public LLMResponse complete(String baseUrl, String apiKey, String model,
                                                  List<ChatMessage> messages,
                                                  List<Map<String, Object>> tools,
                                                  double temperature, int maxTokens,
                                                  String reasoningEffort) {
                return new LLMResponse("id", model,
                        new LLMResponse.Choice(0, ChatMessage.assistant("ok"), "stop"),
                        new LLMResponse.Usage(1, 1, 2), "stop");
            }
        };
        LLMResponse response = provider.complete(new LLMRequest(
                "https://example.test", "key", "model",
                List.of(ChatMessage.user("hello")), List.of(ChatMessage.user("hello")),
                List.of(), List.of(),
                0.0, 10, null, null, null, null,
                Duration.ofSeconds(1), false));
        assertEquals("ok", response.choice().message().content());
    }
}
