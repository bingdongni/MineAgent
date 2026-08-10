package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gives a wire protocol a stable, vendor-neutral registry id.
 *
 * <p>Vendor entries remain registered for old configuration files, while the
 * aliases let a user combine any model id and compatible official, relay, or
 * self-hosted base URL without pretending that a finite vendor list defines
 * the supported model universe.
 */
public final class ProtocolAliasProvider implements LLMProvider {

    private final String providerId;
    private final String displayName;
    private final LLMProvider delegate;
    private final boolean requiresApiKey;

    public ProtocolAliasProvider(String providerId, String displayName,
                                 LLMProvider delegate) {
        this(providerId, displayName, delegate, true);
    }

    public ProtocolAliasProvider(String providerId, String displayName,
                                 LLMProvider delegate, boolean requiresApiKey) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requiresApiKey = requiresApiKey;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String defaultBaseUrl() {
        return delegate.defaultBaseUrl();
    }

    @Override
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                List<ChatMessage> messages,
                                List<Map<String, Object>> tools,
                                double temperature, int maxTokens,
                                String reasoningEffort) {
        return delegate.complete(baseUrl, apiKey, model, messages, tools,
                temperature, maxTokens, reasoningEffort);
    }

    @Override
    public boolean supportsModel(String model) {
        // A protocol adapter accepts arbitrary model IDs. Actual availability
        // remains an endpoint capability and is reported by that endpoint.
        return model != null && !model.isBlank();
    }

    @Override
    public List<String> defaultModels() {
        return delegate.defaultModels();
    }
}
