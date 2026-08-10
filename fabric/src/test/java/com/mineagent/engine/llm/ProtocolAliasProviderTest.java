package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolAliasProviderTest {

    @Test
    void protocolAliasDoesNotWhitelistModelNames() {
        ProtocolAliasProvider alias = new ProtocolAliasProvider(
                "future-compatible", "Future compatible", new StubProvider(), false);

        assertTrue(alias.supportsModel("unannounced-model-family/version-99"));
        assertEquals("future-compatible", alias.providerId());
        assertFalse(alias.requiresApiKey());
    }

    private static final class StubProvider implements LLMProvider {
        @Override public String providerId() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public String defaultBaseUrl() { return "https://example.test"; }
        @Override public boolean supportsModel(String model) { return false; }
        @Override public List<String> defaultModels() { return List.of(); }

        @Override
        public LLMResponse complete(String baseUrl, String apiKey, String model,
                                    List<ChatMessage> messages,
                                    List<Map<String, Object>> tools,
                                    double temperature, int maxTokens,
                                    String reasoningEffort) {
            throw new UnsupportedOperationException();
        }
    }
}
