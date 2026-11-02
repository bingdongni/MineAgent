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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Tencent Hunyuan ChatCompletions adapter with official TC3-HMAC-SHA256 auth. */
public final class TencentHunyuanProvider implements LLMProvider {
    @Override public TencentHunyuanProvider newSession() { return new TencentHunyuanProvider(); }
    private static final String HOST = "hunyuan.tencentcloudapi.com";
    private static final String SERVICE = "hunyuan";
    private static final String VERSION = "2023-09-01";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    @Override public String providerId() { return "tencent-hunyuan"; }
    @Override public String displayName() { return "Tencent Hunyuan"; }
    @Override public String defaultBaseUrl() { return "https://" + HOST; }
    @Override public boolean autoDetectable() { return false; }
    @Override public boolean supportsModel(String model) { return model != null && model.startsWith("hunyuan"); }
    @Override public List<String> defaultModels() { return List.of("hunyuan-turbo", "hunyuan-pro", "hunyuan-lite"); }
    @Override public LLMCapabilities capabilities() { return new LLMCapabilities(EnumSet.of(LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0); }
    @Override public LLMResponse complete(String baseUrl, String apiKey, String model, List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, int maxTokens, String effort) { return internal(baseUrl, apiKey, model, messages, tools, temperature, maxTokens, Duration.ofSeconds(120)); }
    @Override public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Tencent Hunyuan request is null");
        return internal(request.baseUrl(), request.apiKey(), request.model(), request.messages(), request.tools(), request.temperature(), request.maxTokens(), request.deadline());
    }

    private LLMResponse internal(String baseUrl, String raw, String model, List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, int maxTokens, Duration deadline) {
        ProviderSupport.validateRequest("Tencent Hunyuan", raw, model, messages, temperature, maxTokens);
        Credentials credentials = Credentials.parse(raw); String endpoint = ProviderSupport.validatedBaseUrl(baseUrl, defaultBaseUrl(), "Tencent Hunyuan");
        URI endpointUri = URI.create(endpoint);
        // Sign the same authority that java.net.http places in Host.  The
        // port matters for local test relays and non-default HTTPS gateways.
        String host = endpointUri.getRawAuthority();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Tencent Hunyuan endpoint host is missing");
        String path = endpointUri.getRawPath();
        if (path == null || path.isBlank()) path = "/";
        JsonObject body = new JsonObject(); body.addProperty("Model", model); body.add("Messages", messages(messages)); body.addProperty("Temperature", temperature); body.addProperty("TopP", 0.8); body.addProperty("MaxTokens", maxTokens);
        JsonArray toolsWire = tools(tools); if (!toolsWire.isEmpty()) body.add("Tools", toolsWire);
        String payload = NativeProviderSupport.GSON.toJson(body); long timestamp = Instant.now().getEpochSecond();
        Signing signing = sign(payload, credentials, timestamp, host, path);
        Map<String, String> headers = Map.of("X-TC-Action", "ChatCompletions", "X-TC-Version", VERSION, "X-TC-Region", credentials.region, "X-TC-Timestamp", Long.toString(timestamp), "Authorization", signing.authorization);
        var response = NativeProviderSupport.post("Tencent Hunyuan", endpoint, payload, headers, deadline);
        try { return parse(response.body(), model); } catch (RuntimeException e) { throw ProviderSupport.malformedResponse("Tencent Hunyuan", response.body(), e); }
    }

    private static JsonArray messages(List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (messages == null) return array;
        for (ChatMessage message : messages) {
            if (message == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("Role", message.role());
            item.addProperty("Content", message.content() == null ? "" : message.content());
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                item.addProperty("ToolCallId", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                    if (call == null || call.name() == null || call.name().isBlank()) continue;
                    JsonObject wireCall = new JsonObject();
                    if (call.id() != null && !call.id().isBlank()) wireCall.addProperty("Id", call.id());
                    wireCall.addProperty("Type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("Name", call.name());
                    function.add("Arguments", ProviderSupport.toolArgumentsObject(call.arguments()));
                    wireCall.add("Function", function);
                    calls.add(wireCall);
                }
                if (!calls.isEmpty()) item.add("ToolCalls", calls);
            }
            array.add(item);
        }
        return array;
    }

    /** Tencent's native schema uses upper-case field names and does not accept
     * the OpenAI-compatible {function:{...}} wrapper verbatim. */
    private static JsonArray tools(List<Map<String, Object>> tools) {
        JsonArray array = new JsonArray();
        if (tools == null) return array;
        for (Map<String, Object> tool : tools) {
            if (tool == null || !(tool.get("function") instanceof Map<?, ?> function)) continue;
            Object name = function.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            JsonObject wire = new JsonObject();
            wire.addProperty("Type", "function");
            JsonObject fn = new JsonObject();
            fn.addProperty("Name", String.valueOf(name));
            if (function.get("description") != null) fn.addProperty("Description", String.valueOf(function.get("description")));
            Object parameters = function.get("parameters");
            fn.add("Parameters", parameters == null ? new JsonObject() : NativeProviderSupport.GSON.toJsonTree(parameters));
            wire.add("Function", fn);
            array.add(wire);
        }
        return array;
    }

    LLMResponse parse(String json, String model) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); JsonObject response = root.has("Response") ? root.getAsJsonObject("Response") : root;
        if (response.has("Error")) throw new LLMProviderException("Tencent Hunyuan API error: " + response.getAsJsonObject("Error").get("Message"), null, false, null);
        JsonArray choices = response.has("Choices") && response.get("Choices").isJsonArray() ? response.getAsJsonArray("Choices") : new JsonArray(); if (choices.isEmpty()) throw new RuntimeException("Hunyuan response has no Choices");
        JsonObject choice = choices.get(0).getAsJsonObject(); JsonObject message = choice.has("Message") ? choice.getAsJsonObject("Message") : choice; String text = message.has("Content") ? message.get("Content").getAsString() : null; List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        if (message.has("ToolCalls") && message.get("ToolCalls").isJsonArray()) for (var item : message.getAsJsonArray("ToolCalls")) { if (!item.isJsonObject()) continue; JsonObject call = item.getAsJsonObject(); JsonObject function = call.has("Function") ? call.getAsJsonObject("Function") : call; if (function.has("Name")) calls.add(new ChatMessage.ToolCallRef(call.has("Id") ? call.get("Id").getAsString() : "hunyuan_call_" + calls.size(), function.get("Name").getAsString(), function.has("Arguments") ? NativeProviderSupport.toolArguments(function.get("Arguments")) : "{}")); }
        int input = 0, output = 0; if (response.has("Usage") && response.get("Usage").isJsonObject()) { JsonObject usage = response.getAsJsonObject("Usage"); input = integer(usage, "PromptTokens"); output = integer(usage, "CompletionTokens"); }
        String finish = calls.isEmpty() ? "stop" : "tool_calls"; return new LLMResponse(response.has("Id") ? response.get("Id").getAsString() : "", model, new LLMResponse.Choice(0, new ChatMessage("assistant", text, calls.isEmpty() ? null : calls, null), finish), new LLMResponse.Usage(input, output, input + output), finish);
    }
    private static int integer(JsonObject o, String k) { try { return o.has(k) ? Math.max(0, o.get(k).getAsInt()) : 0; } catch (RuntimeException e) { return 0; } }

    static Signing sign(String payload, Credentials c, long timestamp) {
        return sign(payload, c, timestamp, HOST, "/");
    }

    static Signing sign(String payload, Credentials c, long timestamp, String host, String path) {
        String date = DATE.format(Instant.ofEpochSecond(timestamp)); String hashed = sha256(payload);
        String canonicalPath = path == null || path.isBlank() ? "/" : path;
        String canonical = "POST\n" + canonicalPath + "\n\ncontent-type:application/json\nhost:" + host.toLowerCase(java.util.Locale.ROOT) + "\n\ncontent-type;host\n" + hashed; String scope = date + "/" + c.region + "/" + SERVICE + "/tc3_request"; String toSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonical); byte[] kDate = hmac(("TC3" + c.secretKey).getBytes(StandardCharsets.UTF_8), date); byte[] kRegion = hmac(kDate, c.region); byte[] kService = hmac(kRegion, SERVICE); byte[] kSigning = hmac(kService, "tc3_request"); String signature = hex(hmac(kSigning, toSign)); String authorization = "TC3-HMAC-SHA256 Credential=" + c.secretId + "/" + scope + ", SignedHeaders=content-type;host, Signature=" + signature; return new Signing(authorization);
    }
    private static String sha256(String s) { try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static byte[] hmac(byte[] key, String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte v : bytes) b.append(String.format("%02x", v & 255)); return b.toString(); }
    record Signing(String authorization) {}
    static final class Credentials { final String secretId, secretKey, region; private Credentials(String id, String key, String region) { this.secretId = id; this.secretKey = key; this.region = region; } static Credentials parse(String raw) { if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Hunyuan credentials are not configured"); String value = raw.trim(); try { JsonObject o = JsonParser.parseString(value).getAsJsonObject(); String id = first(o, "secretId", "SecretId", "secret_id"); String key = first(o, "secretKey", "SecretKey", "secret_key"); String region = first(o, "region", "Region"); if (id == null || key == null || region == null || !region.matches("[a-z0-9-]+") || invalid(id) || invalid(key)) throw new IllegalArgumentException("Hunyuan credentials need valid secretId, secretKey and region"); return new Credentials(id, key, region); } catch (IllegalArgumentException e) { throw e; } catch (RuntimeException e) { String[] fields = value.split("\\|", -1); if (fields.length == 3 && !invalid(fields[0]) && !invalid(fields[1]) && fields[2].matches("[a-z0-9-]+")) return new Credentials(fields[0], fields[1], fields[2]); throw new IllegalArgumentException("Hunyuan credential JSON is malformed", e); } } private static boolean invalid(String value) { if (value == null || value.isBlank() || value.length() > 4096) return true; for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true; return false; } private static String first(JsonObject o, String... names) { for (String n : names) if (o.has(n) && o.get(n).isJsonPrimitive()) return o.get(n).getAsString(); return null; } }
}
