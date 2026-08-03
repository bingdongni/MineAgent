package com.mineagent.api.llm.provider;

import java.util.*;

/**
 * Registry of all LLM providers. Provider implementations are registered
 * at startup and looked up by provider id or auto-detected from model names.
 */
public final class LLMProviderRegistry {

    private static final LinkedHashMap<String, LLMProvider> PROVIDERS = new LinkedHashMap<>();

    private LLMProviderRegistry() {}

    /** Register a provider. */
    public static void register(LLMProvider provider) {
        PROVIDERS.put(provider.providerId(), provider);
    }

    /** Look up by provider id. */
    public static Optional<LLMProvider> get(String providerId) {
        return Optional.ofNullable(PROVIDERS.get(providerId));
    }

    /**
     * Auto-detect the best provider for a model name.
     * Tries each provider's {@link LLMProvider#supportsModel} in order.
     */
    public static Optional<LLMProvider> detectForModel(String model) {
        for (var provider : PROVIDERS.values()) {
            if (provider.supportsModel(model)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** All registered providers. */
    public static Collection<LLMProvider> all() {
        return Collections.unmodifiableCollection(PROVIDERS.values());
    }

    /** Remove all (for testing). */
    public static void clear() {
        PROVIDERS.clear();
    }
}
