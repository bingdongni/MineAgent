package com.mineagent.engine.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Baidu Qianfan ERNIE native RPC chat adapter with OAuth client credentials. */
public final class BaiduQianfanProvider implements LLMProvider {
    @Override public BaiduQianfanProvider newSession() { return new BaiduQianfanProvider(); }
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    @Override public String providerId() { return "baidu-qianfan"; }
    @Override public String displayName() { return "Baidu Qianfan"; }
    @Override public String defaultBaseUrl() { return "https://aip.baidubce.com"; }
    @Override public boolean autoDetectable() { return false; }
    @Override public boolean supportsModel(String model) { return model != null && !model.isBlank(); }
    @Override public List<String> defaultModels() { return List.of("ernie-4.5-turbo-128k", "ernie-4.0-turbo-8k", "ernie-speed"); }
    @Override public LLMCapabilities capabilities() { return new LLMCapabilities(EnumSet.of(LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0); }

    @Override public LLMResponse complete(String baseUrl, String apiKey, String model, List<ChatMessage> messages,
                                          List<Map<String, Object>> tools, double temperature, int maxTokens, String effort) {
        return completeInternal(baseUrl, apiKey, model, messages, tools, temperature, maxTokens, Duration.ofSeconds(120));
    }
    @Override public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Baidu Qianfan request is null");
        return completeInternal(request.baseUrl(), request.apiKey(), request.model(), request.messages(), request.tools(),
                request.temperature(), request.maxTokens(), request.deadline());
    }

    private LLMResponse completeInternal(String baseUrl, String raw, String model, List<ChatMessage> messages,
                                         List<Map<String, Object>> tools, double temperature, int maxTokens, Duration deadline) {
        ProviderSupport.validateRequest("Baidu Qianfan", raw, model, messages, temperature, maxTokens);
        Credentials credentials = Credentials.parse(raw);
        String token = credentials.accessToken != null ? credentials.accessToken : token(credentials, NativeProviderSupport.timeout(deadline));
        String url = ProviderSupport.validatedBaseUrl(baseUrl, defaultBaseUrl(), "Baidu Qianfan")
                + "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + encode(model)
                + "?access_token=" + encode(token);
        JsonObject body = new JsonObject(); body.add("messages", NativeProviderSupport.messages(messages));
        body.addProperty("temperature", temperature); body.addProperty("max_output_tokens", maxTokens);
        JsonArray functions = new JsonArray();
        if (tools != null) for (Map<String, Object> tool : tools) {
            if (tool != null && tool.get("function") != null) functions.add(NativeProviderSupport.GSON.toJsonTree(tool.get("function")));
        }
        if (!functions.isEmpty()) body.add("functions", functions);
        var response = NativeProviderSupport.post("Baidu Qianfan", url, NativeProviderSupport.GSON.toJson(body), Map.of(), deadline);
        try { return parse(response.body(), model); }
        catch (RuntimeException e) { throw ProviderSupport.malformedResponse("Baidu Qianfan", response.body(), e); }
    }

    LLMResponse parse(String json, String model) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("error_code")) throw new LLMProviderException("Baidu Qianfan API error: " + root.get("error_msg"), root.get("error_code").getAsInt(), false, null);
        String text = root.has("result") ? root.get("result").getAsString() : null;
        List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        if (root.has("function_call") && root.get("function_call").isJsonObject()) {
            JsonObject call = root.getAsJsonObject("function_call");
            if (call.has("name")) calls.add(new ChatMessage.ToolCallRef("qianfan_call_0", call.get("name").getAsString(), call.has("arguments") ? call.get("arguments").toString() : "{}"));
        }
        if (root.has("tool_calls") && root.get("tool_calls").isJsonArray()) for (var item : root.getAsJsonArray("tool_calls")) {
            if (!item.isJsonObject()) continue; JsonObject function = item.getAsJsonObject().has("function") ? item.getAsJsonObject().getAsJsonObject("function") : item.getAsJsonObject();
            if (!function.has("name")) continue; String id = item.getAsJsonObject().has("id") ? item.getAsJsonObject().get("id").getAsString() : "qianfan_call_" + calls.size(); calls.add(new ChatMessage.ToolCallRef(id, function.get("name").getAsString(), function.has("arguments") ? NativeProviderSupport.toolArguments(function.get("arguments")) : "{}"));
        }
        int prompt = 0, output = 0; if (root.has("usage") && root.get("usage").isJsonObject()) { JsonObject usage = root.getAsJsonObject("usage"); prompt = integer(usage, "prompt_tokens"); output = integer(usage, "completion_tokens"); }
        String finish = calls.isEmpty() ? "stop" : "tool_calls";
        return new LLMResponse("", model, new LLMResponse.Choice(0, new ChatMessage("assistant", text, calls.isEmpty() ? null : calls, null), finish), new LLMResponse.Usage(prompt, output, prompt + output), finish);
    }
    private static int integer(JsonObject o, String k) { try { return o.has(k) ? Math.max(0, o.get(k).getAsInt()) : 0; } catch (RuntimeException e) { return 0; } }
    private static String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static String token(Credentials c, Duration timeout) {
        String key = c.apiKey + "\u0000" + c.secretKey; Token cached = TOKENS.get(key); if (cached != null && cached.expires > System.currentTimeMillis() + 60_000) return cached.value;
        try {
            String form = "grant_type=client_credentials&client_id=" + encode(c.apiKey) + "&client_secret=" + encode(c.secretKey);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = NativeProviderSupport.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw LLMProviderException.http("Qianfan OAuth", response.statusCode(), response.body());
            JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject(); String value = object.get("access_token").getAsString(); long expires = System.currentTimeMillis() + (object.has("expires_in") ? object.get("expires_in").getAsLong() * 1000 : 2_500_000); TOKENS.put(key, new Token(value, expires)); return value;
        } catch (LLMProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException("Qianfan OAuth API call interrupted", null, false, e);
        } catch (Exception e) {
            throw LLMProviderException.transport("Qianfan OAuth", e);
        }
    }
    private record Token(String value, long expires) {}
    private static final class Credentials {
        final String accessToken, apiKey, secretKey;
        private Credentials(String accessToken, String apiKey, String secretKey) { this.accessToken = accessToken; this.apiKey = apiKey; this.secretKey = secretKey; }
        static Credentials parse(String raw) {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Qianfan credentials are not configured");
            String value = raw.trim(); if (value.startsWith("bearer:")) return new Credentials(value.substring(7), null, null);
            try {
                JsonObject o = JsonParser.parseString(value).getAsJsonObject();
                String access = first(o, "accessToken", "access_token");
                if (access != null && !access.isBlank()) return new Credentials(access, null, null);
                String key = first(o, "apiKey", "API_KEY", "client_id"); String secret = first(o, "secretKey", "SECRET_KEY", "client_secret");
                if (key == null || secret == null) throw new IllegalArgumentException("Qianfan credential JSON needs apiKey and secretKey");
                return new Credentials(null, key, secret);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                // Qianfan access tokens are often copied directly from the
                // OAuth response. Treat a non-JSON value as that token; AK/SK
                // credentials remain explicit JSON so a secret is never
                // accidentally used as a client id.
                if (!value.contains("\"") && value.length() <= 16_384
                        && value.indexOf('|') < 0 && value.indexOf(':') < 0) {
                    return new Credentials(value, null, null);
                }
                throw new IllegalArgumentException("Qianfan credential JSON is malformed", e);
            }
        }
        private static String first(JsonObject o, String... names) { for (String n : names) if (o.has(n) && o.get(n).isJsonPrimitive()) return o.get(n).getAsString(); return null; }
    }
}
