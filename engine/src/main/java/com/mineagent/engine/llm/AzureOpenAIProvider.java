package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Azure OpenAI native route: deployment path, api-version query, api-key header. */
public final class AzureOpenAIProvider extends OpenAICompatibleProvider {
    @Override public AzureOpenAIProvider newSession() { return new AzureOpenAIProvider(); }
    private static final ThreadLocal<AzureCredentials> CURRENT = new ThreadLocal<>();
    public AzureOpenAIProvider() {
        super("azure-openai", "Azure OpenAI",
                "https://YOUR_RESOURCE.openai.azure.com",
                List.of("gpt-4o", "gpt-4.1", "gpt-5"), java.util.Set.of());
    }

    @Override public boolean supportsModel(String model) {
        return model != null && !model.isBlank();
    }

    @Override public String defaultBaseUrl() {
        return "https://YOUR_RESOURCE.openai.azure.com";
    }

    @Override public boolean requiresApiKey() { return true; }

    @Override
    protected String chatEndpoint(String base, String model) {
        AzureCredentials credentials = CURRENT.get();
        if (credentials == null) credentials = AzureCredentials.parseForEndpoint(model, "placeholder");
        String deployment = credentials.deployment == null || credentials.deployment.isBlank()
                ? model : credentials.deployment;
        String version = credentials.apiVersion == null || credentials.apiVersion.isBlank()
                ? "2024-10-21" : credentials.apiVersion;
        String encodedDeployment = URLEncoder.encode(deployment, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String suffix = "/openai/deployments/" + encodedDeployment
                + "/chat/completions?api-version="
                + URLEncoder.encode(version, StandardCharsets.UTF_8);
        if (base.endsWith(suffix)) return base;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + suffix
                : base + suffix;
    }

    @Override
    protected void addAuthentication(HttpRequest.Builder builder, String apiKey) {
        // Azure supports both resource API keys and Microsoft Entra bearer
        // tokens.  The latter is common for managed identities and must not be
        // sent as an api-key header (Azure returns a misleading 401 in that
        // case).  CURRENT is set for the whole request, including the
        // state-aware overload, so deployment metadata and auth mode cannot
        // get out of sync.
        AzureCredentials credentials = CURRENT.get();
        if (credentials == null) credentials = AzureCredentials.parseForEndpoint(null, apiKey);
        if (credentials.mode == AzureCredentials.AuthMode.BEARER) {
            builder.header("Authorization", "Bearer " + credentials.apiKey);
        } else {
            builder.header("api-key", credentials.apiKey);
        }
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                List<com.mineagent.api.llm.ChatMessage> messages,
                                List<java.util.Map<String, Object>> tools,
                                double temperature, int maxTokens, String reasoningEffort) {
        // Validate the envelope before building a request; the base class keeps
        // the common HTTP/error/response path and the hooks above select the
        // Azure deployment route and header.
        AzureCredentials credentials = AzureCredentials.parseForEndpoint(model, apiKey);
        CURRENT.set(credentials);
        try {
            return super.complete(baseUrl, credentials.apiKey, model, messages, tools,
                    temperature, maxTokens, reasoningEffort);
        } finally {
            CURRENT.remove();
        }
    }

    @Override
    public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Azure OpenAI request is null");
        AzureCredentials credentials = AzureCredentials.parseForEndpoint(request.model(), request.apiKey());
        CURRENT.set(credentials);
        try {
            return super.complete(new LLMRequest(request.baseUrl(), credentials.apiKey,
                    request.model(), request.messages(), request.fallbackMessages(), request.items(),
                    request.tools(), request.temperature(), request.maxTokens(), request.reasoningEffort(),
                    request.previousResponseId(), request.conversationId(), request.promptCacheKey(),
                    request.deadline(), request.allowServerCompaction(), request.cacheableMessageCount()));
        } finally {
            CURRENT.remove();
        }
    }

    static final class AzureCredentials {
        final String apiKey;
        final AuthMode mode;
        final String apiVersion;
        final String deployment;

        private AzureCredentials(String apiKey, String apiVersion, String deployment, AuthMode mode) {
            this.apiKey = apiKey;
            this.apiVersion = apiVersion;
            this.deployment = deployment;
            this.mode = mode;
        }

        static AzureCredentials parseForEndpoint(String model, String raw) {
            if (raw == null || raw.isBlank()) {
                // Endpoint construction can happen before the auth hook. The
                // real credential is validated by complete(...); this branch
                // only supplies the model deployment and default API version.
                if (model != null && !model.isBlank()) {
                    return new AzureCredentials("", null, model, AuthMode.API_KEY);
                }
                throw new IllegalArgumentException("Azure OpenAI API key is not configured");
            }
            String value = raw.trim();
            AuthMode mode = AuthMode.API_KEY;
            String key = value;
            if (value.regionMatches(true, 0, "bearer:", 0, "bearer:".length())) {
                mode = AuthMode.BEARER;
                key = value.substring("bearer:".length()).trim();
            }
            String version = null;
            String deployment = null;
            if (value.startsWith("{")) {
                try {
                    JsonObject object = JsonParser.parseString(value).getAsJsonObject();
                    boolean tokenField = object.has("accessToken") || object.has("access_token")
                            || object.has("token");
                    key = first(object, "apiKey", "api_key", "key", "accessToken", "access_token", "token");
                    String authType = first(object, "authType", "auth_type", "type");
                    if (tokenField || (authType != null && authType.equalsIgnoreCase("bearer"))) {
                        mode = AuthMode.BEARER;
                    }
                    version = first(object, "apiVersion", "api-version", "version");
                    deployment = first(object, "deployment", "deploymentName");
                } catch (RuntimeException malformed) {
                    throw new IllegalArgumentException("Azure OpenAI credential JSON is malformed", malformed);
                }
            }
            if (mode == AuthMode.API_KEY && key != null
                    && key.regionMatches(true, 0, "bearer:", 0, "bearer:".length())) {
                mode = AuthMode.BEARER;
                key = key.substring("bearer:".length()).trim();
            }
            if (key == null || key.isBlank() || key.length() > 16_384
                    || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Azure OpenAI API key is invalid");
            }
            if (version != null && (version.isBlank() || version.length() > 64
                    || !version.matches("[A-Za-z0-9._-]+"))) {
                throw new IllegalArgumentException("Azure OpenAI api-version is invalid");
            }
            if (deployment != null && (deployment.isBlank() || deployment.length() > 256
                    || deployment.indexOf('\r') >= 0 || deployment.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("Azure OpenAI deployment is invalid");
            }
            return new AzureCredentials(key, version, deployment, mode);
        }

        private enum AuthMode { API_KEY, BEARER }

        private static String first(JsonObject object, String... names) {
            for (String name : names) {
                if (object.has(name) && object.get(name).isJsonPrimitive()) {
                    return object.get(name).getAsString();
                }
            }
            return null;
        }
    }
}
