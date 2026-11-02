package com.mineagent.engine.llm;

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

import javax.crypto.Mac;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Google Vertex AI Gemini adapter with OAuth access-token and service-account JWT auth. */
public final class VertexAIProvider implements LLMProvider {
    @Override public VertexAIProvider newSession() { return new VertexAIProvider(); }
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Map<String, Token> TOKEN_CACHE = new ConcurrentHashMap<>();

    @Override public String providerId() { return "vertex-ai"; }
    @Override public String displayName() { return "Google Vertex AI"; }
    @Override public String defaultBaseUrl() { return "https://us-central1-aiplatform.googleapis.com"; }
    @Override public boolean autoDetectable() { return false; }
    @Override public boolean supportsModel(String model) {
        return model != null && (model.startsWith("gemini-") || model.startsWith("models/gemini-"));
    }
    @Override public List<String> defaultModels() {
        return List.of("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash");
    }
    @Override public LLMCapabilities capabilities() {
        return new LLMCapabilities(EnumSet.of(LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0);
    }

    @Override public LLMResponse complete(String baseUrl, String apiKey, String model,
                                          List<ChatMessage> messages, List<Map<String, Object>> tools,
                                          double temperature, int maxTokens, String reasoningEffort) {
        return completeInternal(baseUrl, apiKey, model, messages, tools, temperature, maxTokens,
                reasoningEffort, Duration.ofSeconds(120));
    }

    @Override public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Vertex AI request is null");
        return completeInternal(request.baseUrl(), request.apiKey(), request.model(), request.messages(),
                request.tools(), request.temperature(), request.maxTokens(), request.reasoningEffort(),
                request.deadline());
    }

    private LLMResponse completeInternal(String baseUrl, String rawCredentials, String model,
                                         List<ChatMessage> messages, List<Map<String, Object>> tools,
                                         double temperature, int maxTokens, String effort,
                                         Duration deadline) {
        ProviderSupport.validateRequest("Vertex AI", rawCredentials, model, messages, temperature, maxTokens);
        Credentials credentials = Credentials.parse(rawCredentials);
        String token = credentials.accessToken != null ? credentials.accessToken
                : accessToken(credentials, NativeProviderSupport.timeout(deadline));
        String location = credentials.location == null || credentials.location.isBlank()
                ? inferLocation(baseUrl) : credentials.location;
        String project = credentials.projectId;
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("Vertex AI credentials need projectId");
        }
        if (!project.matches("[A-Za-z0-9._:-]{1,256}")) {
            throw new IllegalArgumentException("Vertex AI projectId is invalid");
        }
        String resolvedBase = ProviderSupport.validatedBaseUrl(baseUrl, defaultBaseUrl(), "Vertex AI");
        String modelId = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String url = resolvedBase + "/v1/projects/" + enc(project) + "/locations/"
                + enc(location) + "/publishers/google/models/" + enc(modelId) + ":generateContent";
        JsonObject body = buildBody(messages, tools, temperature, maxTokens, effort);
        var response = NativeProviderSupport.post("Vertex AI", url,
                NativeProviderSupport.GSON.toJson(body), Map.of("Authorization", "Bearer " + token), deadline);
        try {
            return parse(response.body(), model);
        } catch (RuntimeException malformed) {
            throw ProviderSupport.malformedResponse("Vertex AI", response.body(), malformed);
        }
    }

    private JsonObject buildBody(List<ChatMessage> messages, List<Map<String, Object>> tools,
                                 double temperature, int maxTokens, String effort) {
        JsonObject root = new JsonObject();
        JsonArray contents = new JsonArray();
        Map<String, String> toolNames = new HashMap<>();
        StringBuilder system = new StringBuilder();
        if (messages != null) for (ChatMessage message : messages) {
            if (message == null) continue;
            if (message.toolCalls() != null) for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                if (call != null && call.id() != null && call.name() != null) toolNames.put(call.id(), call.name());
            }
            if ("system".equals(message.role())) {
                if (message.content() != null && !message.content().isBlank()) {
                    if (system.length() > 0) system.append('\n');
                    system.append(message.content());
                }
                continue;
            }
            String role = "assistant".equals(message.role()) ? "model" : "user";
            JsonArray parts = new JsonArray();
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                JsonObject functionResponse = new JsonObject();
                functionResponse.addProperty("name", toolNames.getOrDefault(message.toolCallId(), message.toolCallId()));
                JsonObject response = new JsonObject();
                response.addProperty("result", message.content() == null ? "" : message.content());
                functionResponse.add("response", response);
                JsonObject part = new JsonObject();
                part.add("functionResponse", functionResponse);
                parts.add(part);
                role = "user";
            } else {
                if (message.content() != null && !message.content().isEmpty()) {
                    JsonObject text = new JsonObject(); text.addProperty("text", message.content()); parts.add(text);
                }
                if (message.toolCalls() != null) for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                    if (call == null || call.name() == null) continue;
                    JsonObject functionCall = new JsonObject(); functionCall.addProperty("name", call.name());
                    functionCall.add("args", ProviderSupport.toolArgumentsObject(call.arguments()));
                    JsonObject part = new JsonObject(); part.add("functionCall", functionCall); parts.add(part);
                }
            }
            if (parts.isEmpty()) continue;
            appendContent(contents, role, parts);
        }
        root.add("contents", contents);
        if (system.length() > 0) {
            JsonObject instruction = new JsonObject(); JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject(); text.addProperty("text", system.toString()); parts.add(text);
            instruction.add("parts", parts); root.add("systemInstruction", instruction);
        }
        JsonObject generation = new JsonObject(); generation.addProperty("temperature", temperature);
        generation.addProperty("maxOutputTokens", maxTokens); root.add("generationConfig", generation);
        JsonArray declarations = new JsonArray();
        if (tools != null) for (Map<String, Object> tool : tools) {
            if (tool == null || !(tool.get("function") instanceof Map<?, ?> function)) continue;
            String name = String.valueOf(function.get("name")); if (name.isBlank()) continue;
            JsonObject declaration = new JsonObject(); declaration.addProperty("name", name);
            if (function.get("description") != null) declaration.addProperty("description", String.valueOf(function.get("description")));
            declaration.add("parameters", function.get("parameters") == null
                    ? new JsonObject() : NativeProviderSupport.GSON.toJsonTree(function.get("parameters")));
            declarations.add(declaration);
        }
        if (!declarations.isEmpty()) {
            JsonObject functionDeclarations = new JsonObject(); functionDeclarations.add("functionDeclarations", declarations);
            JsonArray wireTools = new JsonArray(); wireTools.add(functionDeclarations); root.add("tools", wireTools);
        }
        return root;
    }

    private static void appendContent(JsonArray contents, String role, JsonArray parts) {
        if (!contents.isEmpty() && role.equals(contents.get(contents.size() - 1).getAsJsonObject().get("role").getAsString())) {
            JsonArray existing = contents.get(contents.size() - 1).getAsJsonObject().getAsJsonArray("parts");
            for (JsonElement part : parts) existing.add(part);
            return;
        }
        JsonObject content = new JsonObject(); content.addProperty("role", role); content.add("parts", parts); contents.add(content);
    }

    LLMResponse parse(String json, String requestedModel) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("candidates") || !root.get("candidates").isJsonArray() || root.getAsJsonArray("candidates").isEmpty()) {
            throw new RuntimeException("Vertex AI response has no candidates");
        }
        JsonObject candidate = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject content = candidate.has("content") ? candidate.getAsJsonObject("content") : new JsonObject();
        JsonArray parts = content.has("parts") ? content.getAsJsonArray("parts") : new JsonArray();
        StringBuilder text = new StringBuilder(); List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        for (JsonElement element : parts) {
            if (!element.isJsonObject()) continue; JsonObject part = element.getAsJsonObject();
            if (part.has("text")) { if (text.length() > 0) text.append('\n'); text.append(part.get("text").getAsString()); }
            if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                JsonObject call = part.getAsJsonObject("functionCall"); if (!call.has("name")) continue;
                calls.add(new ChatMessage.ToolCallRef("vertex_call_" + calls.size(), call.get("name").getAsString(),
                        call.has("args") ? NativeProviderSupport.toolArguments(call.get("args")) : "{}"));
            }
        }
        String finish = calls.isEmpty() ? "stop" : "tool_calls"; int input = 0, output = 0;
        if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usageMetadata"); input = integer(usage, "promptTokenCount"); output = integer(usage, "candidatesTokenCount");
        }
        return new LLMResponse("", requestedModel, new LLMResponse.Choice(0,
                new ChatMessage("assistant", text.length() == 0 ? null : text.toString(), calls.isEmpty() ? null : calls, null), finish),
                new LLMResponse.Usage(input, output, input + output), finish);
    }

    private static int integer(JsonObject object, String key) { try { return object.has(key) ? Math.max(0, object.get(key).getAsInt()) : 0; } catch (RuntimeException e) { return 0; } }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String inferLocation(String base) { if (base != null && base.startsWith("https://")) { int dash = base.indexOf('-'); int dot = base.indexOf('.', dash); if (dash > 8 && dot > dash) return base.substring(8, dot); } return "us-central1"; }

    private static String accessToken(Credentials credentials, Duration timeout) {
        String key = credentials.clientEmail + "\u0000" + credentials.privateKey;
        Token cached = TOKEN_CACHE.get(key); if (cached != null && cached.expiresAt > System.currentTimeMillis() + 60_000) return cached.value;
        try {
            long now = Instant.now().getEpochSecond();
            String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            String payload = b64("{\"iss\":\"" + escape(credentials.clientEmail) + "\",\"scope\":\"" + SCOPE + "\",\"aud\":\"" + TOKEN_URL + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
            String unsigned = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(privateKey(credentials.privateKey)); signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            String assertion = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
            String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + enc(assertion);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = NativeProviderSupport.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw LLMProviderException.http("Vertex OAuth", response.statusCode(), response.body());
            JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject(); String token = object.get("access_token").getAsString();
            TOKEN_CACHE.put(key, new Token(token, System.currentTimeMillis() + Math.max(60_000, object.has("expires_in") ? object.get("expires_in").getAsLong() * 1000 : 3_000_000)));
            return token;
        } catch (LLMProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException("Vertex OAuth API call interrupted", null, false, e);
        } catch (Exception e) {
            throw LLMProviderException.transport("Vertex OAuth", e);
        }
    }
    private static PrivateKey privateKey(String pem) throws Exception { String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""); return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))); }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private record Token(String value, long expiresAt) {}

    static final class Credentials {
        final String accessToken, projectId, location, clientEmail, privateKey;
        private Credentials(String accessToken, String projectId, String location, String clientEmail, String privateKey) { this.accessToken = accessToken; this.projectId = projectId; this.location = location; this.clientEmail = clientEmail; this.privateKey = privateKey; }
        static Credentials parse(String raw) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Vertex AI credentials are not configured");
            String value = raw.trim(); if (value.startsWith("bearer:")) return new Credentials(value.substring(7), null, null, null, null);
            try {
                JsonObject object = JsonParser.parseString(value).getAsJsonObject();
                String access = first(object, "accessToken", "access_token");
                String project = first(object, "projectId", "project_id"); String location = first(object, "location", "region");
                String email = first(object, "client_email", "clientEmail"); String key = first(object, "private_key", "privateKey");
                if ((access == null || access.isBlank()) && (email == null || key == null)) throw new IllegalArgumentException("Vertex AI credential JSON needs accessToken or service-account fields");
                return new Credentials(access, project, location, email, key == null ? null : key.replace("\\n", "\n"));
            } catch (IllegalArgumentException e) { throw e; } catch (RuntimeException e) { throw new IllegalArgumentException("Vertex AI credential JSON is malformed", e); }
        }
        private static String first(JsonObject object, String... names) { for (String name : names) if (object.has(name) && object.get(name).isJsonPrimitive()) return object.get(name).getAsString(); return null; }
    }
}
