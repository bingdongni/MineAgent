package com.mineagent.engine.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Bedrock Runtime Converse adapter.
 *
 * <p>Converse is AWS's provider-neutral message/tool contract.  It is the
 * correct Bedrock integration point for Anthropic, Amazon, Meta, Mistral and
 * other Bedrock models; model-specific {@code InvokeModel} JSON is intentionally
 * not guessed because those bodies differ between vendors and versions.</p>
 *
 * <p>The single MineAgent API-key field accepts one of these forms:</p>
 * <ul>
 *   <li>JSON: {@code {"accessKeyId":"...","secretAccessKey":"...",
 *       "sessionToken":"...","region":"us-east-1"}}</li>
 *   <li>Convenience: {@code accessKeyId|secretAccessKey|sessionToken|region}
 *       (the last two fields are optional)</li>
 *   <li>For a non-AWS relay only: {@code bearer:token} or
 *       {@code api-key:token}</li>
 * </ul>
 *
 * <p>Secrets are never included in exceptions, response objects or logs.</p>
 */
public final class BedrockConverseProvider implements LLMProvider {
    @Override public BedrockConverseProvider newSession() { return new BedrockConverseProvider(); }

    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Pattern AWS_HOST = Pattern.compile(
            "^bedrock-runtime(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_CREDENTIAL_FIELD = 4_096;

    @Override
    public String providerId() {
        return "aws-bedrock";
    }

    @Override
    public String displayName() {
        return "AWS Bedrock Converse";
    }

    @Override
    public String defaultBaseUrl() {
        // Region-specific endpoints are required by AWS.  us-east-1 is only a
        // safe default for the first-run form; users should select their region
        // in the credential envelope or enter the matching endpoint.
        return "https://bedrock-runtime.us-east-1.amazonaws.com";
    }

    @Override
    public boolean supportsModel(String model) {
        // Bedrock model IDs are intentionally open (vendor prefixes and custom
        // provisioned model IDs change over time).  Explicit provider selection
        // is preferred, so this adapter does not steal arbitrary model names
        // during auto-detection.
        return model != null && !model.isBlank();
    }

    @Override
    public boolean autoDetectable() {
        return false;
    }

    @Override
    public List<String> defaultModels() {
        return List.of(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "amazon.nova-pro-v1:0",
                "meta.llama3-3-70b-instruct-v1:0",
                "mistral.mistral-large-2407-v1:0");
    }

    @Override
    public LLMCapabilities capabilities() {
        return new LLMCapabilities(EnumSet.of(
                LLMCapabilities.Feature.CONVERSE,
                LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0);
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                List<ChatMessage> messages,
                                List<Map<String, Object>> tools,
                                double temperature, int maxTokens,
                                String reasoningEffort) {
        ProviderSupport.validateRequest("AWS Bedrock", apiKey, model, messages,
                temperature, maxTokens);
        return completeInternal(baseUrl, apiKey, model, messages, tools,
                temperature, maxTokens, Duration.ofSeconds(120));
    }

    @Override
    public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Bedrock request is null");
        ProviderSupport.validateRequest("AWS Bedrock", request.apiKey(), request.model(),
                request.messages(), request.temperature(), request.maxTokens());
        return completeInternal(request.baseUrl(), request.apiKey(), request.model(),
                request.messages(), request.tools(), request.temperature(),
                request.maxTokens(), request.deadline());
    }

    private LLMResponse completeInternal(String configuredBaseUrl, String rawCredentials,
                                          String model, List<ChatMessage> messages,
                                          List<Map<String, Object>> tools,
                                          double temperature, int maxTokens,
                                          Duration timeout) {
        String baseUrl = ProviderSupport.validatedBaseUrl(configuredBaseUrl,
                defaultBaseUrl(), "AWS Bedrock");
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("AWS Bedrock base URL is malformed", malformed);
        }
        if (base.getUserInfo() != null) {
            throw new IllegalArgumentException("AWS Bedrock base URL must not contain user info");
        }

        Credentials credentials = Credentials.parse(rawCredentials);
        Matcher awsHost = AWS_HOST.matcher(base.getHost() == null ? "" : base.getHost());
        boolean officialEndpoint = awsHost.matches();
        if (officialEndpoint && credentials.mode != AuthMode.AWS) {
            throw new IllegalArgumentException(
                    "AWS Bedrock official endpoints require accessKeyId and secretAccessKey");
        }
        String region = credentials.region != null && !credentials.region.isBlank()
                ? credentials.region : (officialEndpoint ? awsHost.group(1) : null);
        if (credentials.mode == AuthMode.AWS && (region == null || !isRegion(region))) {
            throw new IllegalArgumentException(
                    "AWS Bedrock credentials need a valid region (for example us-east-1)");
        }

        JsonObject body = buildBody(model, messages, tools, temperature, maxTokens);
        String payload = GSON.toJson(body);
        String requestPath = requestPath(base, model);
        URI endpoint = URI.create(base.getScheme() + "://" + base.getRawAuthority()
                + requestPath);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));

        if (credentials.mode == AuthMode.AWS) {
            SigningHeaders signing = sign("POST", requestPath, "", base.getRawAuthority(),
                    payload, credentials, region, Instant.now());
            // java.net.http treats Host as a restricted header and rejects it
            // on ordinary clients. The URI supplies the same Host value on the
            // wire; it is still included in the canonical request for SigV4.
            signing.headers.forEach((name, value) -> {
                if (!"host".equalsIgnoreCase(name)) requestBuilder.header(name, value);
            });
            requestBuilder.header("Authorization", signing.authorization);
        } else if (credentials.mode == AuthMode.BEARER) {
            requestBuilder.header("Authorization", "Bearer " + credentials.accessKey);
        } else {
            requestBuilder.header("x-api-key", credentials.accessKey);
        }

        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30) : timeout;
        // HttpRequest rejects non-positive values; cap only the legacy path so
        // an accidentally huge deadline cannot keep an AgentLoop blocked forever.
        effectiveTimeout = effectiveTimeout.compareTo(Duration.ofMinutes(10)) > 0
                ? Duration.ofMinutes(10) : effectiveTimeout;
        requestBuilder.timeout(effectiveTimeout);

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http("AWS Bedrock", response.statusCode(),
                        response.body(), response.headers().firstValue("Retry-After").orElse(null));
            }
            try {
                String requestId = response.headers().firstValue("x-amzn-requestid").orElse("");
                return parseResponse(response.body(), model, requestId);
            } catch (LLMProviderException e) {
                throw e;
            } catch (RuntimeException malformed) {
                throw ProviderSupport.malformedResponse("AWS Bedrock", response.body(), malformed);
            }
        } catch (java.io.IOException transport) {
            throw LLMProviderException.transport("AWS Bedrock", transport);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException("AWS Bedrock API call interrupted", null, false,
                    interrupted);
        }
    }

    /** Build the provider-neutral Converse request body. Package-private for mock tests. */
    JsonObject buildBody(String model, List<ChatMessage> messages,
                         List<Map<String, Object>> tools,
                         double temperature, int maxTokens) {
        JsonObject body = new JsonObject();
        JsonArray system = new JsonArray();
        JsonArray wireMessages = new JsonArray();
        Map<String, String> toolNames = new HashMap<>();

        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message == null) continue;
                if ("system".equalsIgnoreCase(message.role())) {
                    if (message.content() != null && !message.content().isBlank()) {
                        JsonObject block = new JsonObject();
                        block.addProperty("text", message.content());
                        system.add(block);
                    }
                    continue;
                }
                if (message.toolCalls() != null) {
                    for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                        if (call != null && call.id() != null && call.name() != null) {
                            toolNames.put(call.id(), call.name());
                        }
                    }
                }
            }

            for (ChatMessage message : messages) {
                if (message == null || "system".equalsIgnoreCase(message.role())) continue;
                String role = "assistant".equalsIgnoreCase(message.role())
                        ? "assistant" : "user";
                JsonArray content = new JsonArray();

                if ("tool".equalsIgnoreCase(message.role()) && message.toolCallId() != null) {
                    JsonObject result = new JsonObject();
                    JsonObject toolResult = new JsonObject();
                    toolResult.addProperty("toolUseId", message.toolCallId());
                    JsonArray resultContent = new JsonArray();
                    JsonObject text = new JsonObject();
                    text.addProperty("text", message.content() == null ? "" : message.content());
                    resultContent.add(text);
                    toolResult.add("content", resultContent);
                    result.add("toolResult", toolResult);
                    content.add(result);
                    role = "user";
                } else {
                    if (message.content() != null && !message.content().isEmpty()) {
                        JsonObject text = new JsonObject();
                        text.addProperty("text", message.content());
                        content.add(text);
                    }
                    if (message.toolCalls() != null) {
                        for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                            if (call == null || call.id() == null || call.name() == null
                                    || call.name().isBlank()) continue;
                            JsonObject use = new JsonObject();
                            JsonObject toolUse = new JsonObject();
                            toolUse.addProperty("toolUseId", call.id());
                            toolUse.addProperty("name", call.name());
                            toolUse.add("input", ProviderSupport.toolArgumentsObject(call.arguments()));
                            use.add("toolUse", toolUse);
                            content.add(use);
                        }
                    }
                }
                if (!content.isEmpty()) appendMessage(wireMessages, role, content);
            }
        }

        // Converse requires at least one message. The common validation above
        // rejects empty requests, but retain a defensive placeholder for direct
        // calls from integrations and tests.
        if (wireMessages.isEmpty()) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("text", "");
            content.add(text);
            message.add("content", content);
            wireMessages.add(message);
        }
        body.add("messages", wireMessages);
        if (!system.isEmpty()) body.add("system", system);

        JsonObject inference = new JsonObject();
        inference.addProperty("maxTokens", maxTokens);
        inference.addProperty("temperature", temperature);
        body.add("inferenceConfig", inference);

        JsonArray wireTools = new JsonArray();
        if (tools != null) {
            for (Map<String, Object> tool : tools) {
                if (tool == null) continue;
                Object functionValue = tool.get("function");
                if (!(functionValue instanceof Map<?, ?> function)) continue;
                String name = stringValue(function.get("name"));
                if (name == null || name.isBlank()) continue;
                JsonObject spec = new JsonObject();
                spec.addProperty("name", name);
                String description = stringValue(function.get("description"));
                if (description != null && !description.isBlank()) {
                    spec.addProperty("description", description);
                }
                Object parameters = function.get("parameters");
                JsonElement schema = parameters == null
                        ? new JsonObject() : GSON.toJsonTree(parameters);
                JsonObject inputSchema = new JsonObject();
                inputSchema.add("json", schema);
                JsonObject toolSpec = new JsonObject();
                toolSpec.add("toolSpec", spec);
                spec.add("inputSchema", inputSchema);
                wireTools.add(toolSpec);
            }
        }
        if (!wireTools.isEmpty()) {
            JsonObject toolConfig = new JsonObject();
            toolConfig.add("tools", wireTools);
            body.add("toolConfig", toolConfig);
        }
        // Bedrock has no cross-vendor reasoning-effort field. Sending a guessed
        // additionalModelRequestFields object causes strict models to reject the
        // entire request, so the unified effort hint is intentionally omitted.
        return body;
    }

    private static void appendMessage(JsonArray messages, String role, JsonArray content) {
        if (!messages.isEmpty()) {
            JsonObject previous = messages.get(messages.size() - 1).getAsJsonObject();
            if (role.equals(previous.get("role").getAsString())) {
                JsonArray previousContent = previous.getAsJsonArray("content");
                for (JsonElement block : content) previousContent.add(block);
                return;
            }
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        messages.add(message);
    }

    LLMResponse parseResponse(String json, String requestedModel, String requestId) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("output") || !root.get("output").isJsonObject()) {
            throw new RuntimeException("AWS Bedrock response has no output message");
        }
        JsonObject output = root.getAsJsonObject("output");
        if (!output.has("message") || !output.get("message").isJsonObject()) {
            throw new RuntimeException("AWS Bedrock response has no output.message");
        }
        JsonObject message = output.getAsJsonObject("message");
        JsonArray contentBlocks = message.has("content") && message.get("content").isJsonArray()
                ? message.getAsJsonArray("content") : new JsonArray();
        StringBuilder text = new StringBuilder();
        List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        int generatedId = 0;
        for (JsonElement element : contentBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("text") && block.get("text").isJsonPrimitive()) {
                if (text.length() > 0) text.append('\n');
                text.append(block.get("text").getAsString());
            }
            if (block.has("toolUse") && block.get("toolUse").isJsonObject()) {
                JsonObject use = block.getAsJsonObject("toolUse");
                String id = stringValue(use.get("toolUseId"));
                String name = stringValue(use.get("name"));
                if (id == null || id.isBlank()) id = "bedrock_call_" + generatedId;
                if (name == null || name.isBlank()) continue;
                String args = use.has("input") ? GSON.toJson(use.get("input")) : "{}";
                calls.add(new ChatMessage.ToolCallRef(id, name, args));
                generatedId++;
            }
        }
        String stopReason = root.has("stopReason") && root.get("stopReason").isJsonPrimitive()
                ? root.get("stopReason").getAsString() : "stop";
        String finishReason = calls.isEmpty() ? mapStopReason(stopReason) : "tool_calls";

        LLMResponse.Usage usage = null;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject u = root.getAsJsonObject("usage");
            int input = nonNegativeInt(u, "inputTokens");
            int outputTokens = nonNegativeInt(u, "outputTokens");
            int total = nonNegativeInt(u, "totalTokens");
            if (total == 0) total = input + outputTokens;
            int cached = firstNonNegative(u, "cacheReadInputTokens", "cachedInputTokens");
            int cacheCreated = firstNonNegative(u, "cacheWriteInputTokens");
            usage = new LLMResponse.Usage(input, outputTokens, total, cached, cacheCreated);
        }
        ChatMessage assistant = new ChatMessage("assistant",
                text.length() == 0 ? null : text.toString(),
                calls.isEmpty() ? null : calls, null);
        return new LLMResponse(requestId == null ? "" : requestId, requestedModel,
                new LLMResponse.Choice(0, assistant, finishReason), usage, finishReason);
    }

    private static String mapStopReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "stop", "end_turn", "stop_sequence" -> "stop";
            case "guardrail_intervened", "content_filtered" -> "content_filter";
            default -> reason == null || reason.isBlank() ? "stop" : reason;
        };
    }

    private static int nonNegativeInt(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? Math.max(0, object.get(key).getAsInt()) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int firstNonNegative(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) return nonNegativeInt(object, key);
        }
        return 0;
    }

    private static String requestPath(URI base, String model) {
        String prefix = base.getRawPath() == null ? "" : base.getRawPath();
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix + "/model/" + percentEncodePathValue(model) + "/converse";
    }

    /** RFC 3986 encoding for one AWS model-id path value (including ':' and '/'). */
    static String percentEncodePathValue(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte byteValue : bytes) {
            int c = byteValue & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                result.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return result.toString();
    }

    /**
     * Produce AWS SigV4 headers. Package-private deterministic entry point for
     * protocol tests; callers never receive the secret or derived signing key.
     */
    static SigningHeaders sign(String method, String canonicalUri, String canonicalQuery,
                               String host, String payload, Credentials credentials,
                               String region, Instant instant) {
        if (credentials == null || credentials.mode != AuthMode.AWS) {
            throw new IllegalArgumentException("AWS signing requires AWS credentials");
        }
        String amzDate = AMZ_DATE.format(instant);
        String shortDate = SHORT_DATE.format(instant);
        String payloadHash = sha256Hex(payload == null ? "" : payload);
        String normalizedHost = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        StringBuilder signedHeaders = new StringBuilder("content-type;host;x-amz-content-sha256;x-amz-date");
        StringBuilder canonicalHeaders = new StringBuilder()
                .append("content-type:application/json\n")
                .append("host:").append(normalizedHost).append('\n')
                .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadHash);
        if (credentials.sessionToken != null && !credentials.sessionToken.isBlank()) {
            canonicalHeaders.append("x-amz-security-token:")
                    .append(credentials.sessionToken.trim()).append('\n');
            signedHeaders.append(";x-amz-security-token");
            headers.put("x-amz-security-token", credentials.sessionToken);
        }
        headers.put("host", normalizedHost);
        String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
                + canonicalUri + "\n" + (canonicalQuery == null ? "" : canonicalQuery) + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = shortDate + "/" + region + "/bedrock/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] kDate = hmac(("AWS4" + credentials.secretAccessKey).getBytes(StandardCharsets.UTF_8), shortDate);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "bedrock");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + credentials.accessKey
                + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
        return new SigningHeaders(Map.copyOf(headers), authorization);
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static boolean isRegion(String region) {
        return region != null && region.length() <= 64
                && region.matches("[a-z0-9-]+") && !region.startsWith("-")
                && !region.endsWith("-");
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        try {
            String text = value instanceof JsonElement element
                    ? (element.isJsonPrimitive() ? element.getAsString() : null)
                    : String.valueOf(value);
            return text == null ? null : text;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    enum AuthMode { AWS, BEARER, API_KEY }

    record SigningHeaders(Map<String, String> headers, String authorization) {}

    /** Parsed credentials with a redacted record representation by design. */
    static final class Credentials {
        final AuthMode mode;
        final String accessKey;
        final String secretAccessKey;
        final String sessionToken;
        final String region;

        private Credentials(AuthMode mode, String accessKey, String secretAccessKey,
                            String sessionToken, String region) {
            this.mode = mode;
            this.accessKey = accessKey;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken = sessionToken;
            this.region = region;
        }

        static Credentials parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("AWS Bedrock credentials are not configured");
            }
            String value = raw.trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("bearer:")) {
                return token(AuthMode.BEARER, value.substring("bearer:".length()));
            }
            if (lower.startsWith("api-key:")) {
                return token(AuthMode.API_KEY, value.substring("api-key:".length()));
            }
            if (value.startsWith("{")) return parseJson(value);

            String[] pipe = value.split("\\|", -1);
            if (pipe.length >= 2 && pipe.length <= 4) {
                return aws(pipe[0], pipe[1], pipe.length >= 3 ? pipe[2] : null,
                        pipe.length >= 4 ? pipe[3] : null);
            }
            // A colon convenience form remains useful for short-lived session
            // credentials. Split at most twice so token characters after the
            // second delimiter are preserved.
            String[] colon = value.split(":", 3);
            if (colon.length >= 2 && !colon[0].isBlank() && !colon[1].isBlank()) {
                return aws(colon[0], colon[1], colon.length == 3 ? colon[2] : null, null);
            }
            // A non-AWS relay may use one opaque token. It is intentionally
            // treated as x-api-key only on custom endpoints; official AWS hosts
            // reject it above before a request is sent.
            return token(AuthMode.API_KEY, value);
        }

        private static Credentials parseJson(String value) {
            try {
                JsonObject object = JsonParser.parseString(value).getAsJsonObject();
                String access = firstJson(object, "accessKeyId", "access_key_id", "aws_access_key_id");
                String secret = firstJson(object, "secretAccessKey", "secret_access_key", "aws_secret_access_key");
                String session = firstJson(object, "sessionToken", "session_token", "aws_session_token", "securityToken");
                String region = firstJson(object, "region", "aws_region");
                return aws(access, secret, session, region);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("AWS Bedrock credential JSON is malformed", malformed);
            }
        }

        private static String firstJson(JsonObject object, String... keys) {
            for (String key : keys) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    return object.get(key).getAsString();
                }
            }
            return null;
        }

        private static Credentials aws(String access, String secret, String session, String region) {
            requireField(access, "accessKeyId");
            requireField(secret, "secretAccessKey");
            checkField(access, "accessKeyId");
            checkField(secret, "secretAccessKey");
            if (session != null && !session.isBlank()) checkField(session, "sessionToken");
            if (region != null && !region.isBlank() && !isRegion(region)) {
                throw new IllegalArgumentException("AWS Bedrock credential region is invalid");
            }
            return new Credentials(AuthMode.AWS, access, secret, blankToNull(session), blankToNull(region));
        }

        private static Credentials token(AuthMode mode, String token) {
            requireField(token, mode == AuthMode.BEARER ? "bearer token" : "API key");
            checkField(token, "token");
            return new Credentials(mode, token.trim(), null, null, null);
        }

        private static void requireField(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }

        private static void checkField(String value, String name) {
            if (value.length() > MAX_CREDENTIAL_FIELD || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0 || containsControl(value)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }

        private static boolean containsControl(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (Character.isISOControl(value.charAt(i))) return true;
            }
            return false;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        @Override
        public String toString() {
            return "Credentials[mode=" + mode + ", region=" + region + "]";
        }
    }
}
