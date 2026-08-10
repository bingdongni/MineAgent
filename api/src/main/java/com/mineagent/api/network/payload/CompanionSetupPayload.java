package com.mineagent.api.network.payload;

/**
 * Atomically configures an LLM connection and creates one companion.
 *
 * <p>This payload deliberately keeps credentials out of slash commands. A
 * A blank API key is unauthenticated unless {@code reuseStoredApiKey} is true
 * and the server independently verifies that the connection target did not
 * change. Every field is validated again by the authoritative server before
 * any world or config state changes.
 */
public record CompanionSetupPayload(
        String name,
        String providerId,
        String apiKey,
        boolean reuseStoredApiKey,
        String model,
        String baseUrl,
        double temperature,
        String reasoningEffort
) {
    public static final int MAX_NAME = 64;
    public static final int MAX_PROVIDER_ID = 64;
    public static final int MAX_API_KEY = 16_384;
    public static final int MAX_MODEL = 256;
    public static final int MAX_BASE_URL = 2_048;
    public static final int MAX_EFFORT = 16;

    public CompanionSetupPayload {
        name = valueOrEmpty(name).trim();
        providerId = valueOrEmpty(providerId).trim();
        // Credentials are opaque bytes represented as text. Do not normalize
        // them: trimming can silently change a valid secret. Only line breaks
        // are rejected because HTTP header values cannot contain them.
        apiKey = valueOrEmpty(apiKey);
        model = valueOrEmpty(model).trim();
        baseUrl = valueOrEmpty(baseUrl).trim();
        reasoningEffort = valueOrEmpty(reasoningEffort).trim();

        if (name.length() > MAX_NAME || containsControl(name)) {
            throw new IllegalArgumentException("invalid companion name");
        }
        if (providerId.isBlank() || providerId.length() > MAX_PROVIDER_ID
                || containsControl(providerId)) {
            throw new IllegalArgumentException("invalid provider id");
        }
        if (apiKey.length() > MAX_API_KEY || containsLineBreak(apiKey)) {
            throw new IllegalArgumentException("invalid API key");
        }
        if (model.isBlank() || model.length() > MAX_MODEL || containsControl(model)) {
            throw new IllegalArgumentException("invalid model id");
        }
        if (baseUrl.length() > MAX_BASE_URL || containsControl(baseUrl)) {
            throw new IllegalArgumentException("invalid base URL");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (reasoningEffort.length() > MAX_EFFORT || containsControl(reasoningEffort)) {
            throw new IllegalArgumentException("invalid reasoning effort");
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }
}
