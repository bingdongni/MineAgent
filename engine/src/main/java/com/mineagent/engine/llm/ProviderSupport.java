package com.mineagent.engine.llm;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.provider.LLMProviderException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.net.URI;

/** Shared validation for the HTTP-backed LLM providers. */
final class ProviderSupport {

    private ProviderSupport() {}

    static String validatedBaseUrl(String configured, String fallback,
                                   String provider) {
        String base = configured == null || configured.isBlank()
                ? fallback : configured.trim();
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException(provider + " base URL is not configured");
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(provider + " base URL is malformed", malformed);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(provider
                    + " base URL must be an absolute HTTP(S) URL without query or fragment");
        }
        // Callers append a fixed API suffix; trimming avoids a double slash
        // that strict reverse proxies may reject.
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** Append an API path without duplicating a user-supplied /v1 segment. */
    static String endpoint(String base, String apiPath) {
        int nextSlash = apiPath.indexOf('/', 1);
        String versionPrefix = nextSlash > 0
                ? apiPath.substring(0, nextSlash) : apiPath;
        return base.endsWith(versionPrefix)
                ? base + apiPath.substring(versionPrefix.length())
                : base + apiPath;
    }

    static void validateRequest(String provider, String apiKey, String model,
                                List<ChatMessage> messages, double temperature,
                                int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(provider + " API key is not configured");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(provider + " model is not configured");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException(provider + " request has no messages");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException(provider + " maxTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(
                    provider + " temperature must be finite and between 0 and 2");
        }
    }

    static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    /**
     * Convert persisted tool arguments into the object shape required by
     * Anthropic and Gemini. A malformed historical argument must not poison
     * every later request; the tool result already records the original
     * failure, so an empty object is the only safe replay representation.
     */
    static JsonObject toolArgumentsObject(String arguments) {
        if (arguments == null || arguments.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    /** A successful HTTP response with a missing/truncated JSON envelope is transient. */
    static LLMProviderException malformedResponse(String provider, String body,
                                                   RuntimeException cause) {
        return new LLMProviderException(provider + " API returned a malformed response: "
                + truncate(body, 500), null, true, cause);
    }
}
