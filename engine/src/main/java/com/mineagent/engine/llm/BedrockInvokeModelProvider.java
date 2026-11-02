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
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bedrock model-specific InvokeModel adapter for common Anthropic, Nova,
 * Mistral, Llama, Cohere and Titan profiles. Converse remains preferred when
 * available; this adapter exists for models that expose only InvokeModel.
 */
public final class BedrockInvokeModelProvider implements LLMProvider {
    @Override public BedrockInvokeModelProvider newSession() { return new BedrockInvokeModelProvider(); }
    @Override public String providerId() { return "aws-bedrock-invoke"; }
    @Override public String displayName() { return "AWS Bedrock InvokeModel"; }
    @Override public String defaultBaseUrl() { return "https://bedrock-runtime.us-east-1.amazonaws.com"; }
    @Override public boolean autoDetectable() { return false; }
    @Override public boolean supportsModel(String model) { return model != null && !model.isBlank(); }
    @Override public List<String> defaultModels() { return List.of("anthropic.claude-3-5-sonnet-20241022-v2:0", "amazon.nova-pro-v1:0", "mistral.mistral-large-2407-v1:0", "meta.llama3-3-70b-instruct-v1:0", "cohere.command-r-v1:0"); }
    @Override public LLMCapabilities capabilities() { return new LLMCapabilities(EnumSet.of(LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0); }
    @Override public LLMResponse complete(String baseUrl, String apiKey, String model, List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, int maxTokens, String effort) { return internal(baseUrl, apiKey, model, messages, tools, temperature, maxTokens, Duration.ofSeconds(120)); }
    @Override public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("AWS Bedrock InvokeModel request is null");
        return internal(request.baseUrl(), request.apiKey(), request.model(), request.messages(), request.tools(), request.temperature(), request.maxTokens(), request.deadline());
    }

    private LLMResponse internal(String configured, String raw, String model, List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, int maxTokens, Duration deadline) {
        ProviderSupport.validateRequest("AWS Bedrock InvokeModel", raw, model, messages, temperature, maxTokens);
        BedrockConverseProvider.Credentials credentials = BedrockConverseProvider.Credentials.parse(raw);
        String base = ProviderSupport.validatedBaseUrl(configured, defaultBaseUrl(), "AWS Bedrock InvokeModel"); URI uri = URI.create(base);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean official = host.matches("bedrock-runtime(?:-fips)?\\.[a-z0-9-]+\\.amazonaws\\.com(?:\\.cn)?");
        if (official && credentials.mode != BedrockConverseProvider.AuthMode.AWS) throw new IllegalArgumentException("Official Bedrock InvokeModel endpoints require AWS credentials");
        String region = credentials.region != null ? credentials.region : inferRegion(host);
        String path = (uri.getRawPath() == null ? "" : uri.getRawPath()).replaceAll("/+$", "") + "/model/" + BedrockConverseProvider.percentEncodePathValue(model) + "/invoke";
        Profile profile = Profile.forModel(model); JsonObject body = build(profile, model, messages, tools, temperature, maxTokens); String payload = NativeProviderSupport.GSON.toJson(body);
        var request = HttpRequest.newBuilder().uri(URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path)).header("Content-Type", "application/json").timeout(NativeProviderSupport.timeout(deadline)).POST(HttpRequest.BodyPublishers.ofString(payload));
        if (credentials.mode == BedrockConverseProvider.AuthMode.AWS) {
            var signed = BedrockConverseProvider.sign("POST", path, "", uri.getRawAuthority(), payload, credentials, region, Instant.now()); signed.headers().forEach((name, value) -> { if (!"host".equalsIgnoreCase(name)) request.header(name, value); }); request.header("Authorization", signed.authorization());
        } else if (credentials.mode == BedrockConverseProvider.AuthMode.BEARER) request.header("Authorization", "Bearer " + credentials.accessKey); else request.header("x-api-key", credentials.accessKey);
        try { var response = NativeProviderSupport.HTTP.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()); if (response.statusCode() < 200 || response.statusCode() >= 300) throw LLMProviderException.http("AWS Bedrock InvokeModel", response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null)); try { return parse(response.body(), model, profile); } catch (RuntimeException malformed) { throw ProviderSupport.malformedResponse("AWS Bedrock InvokeModel", response.body(), malformed); } }
        catch (LLMProviderException e) { throw e; } catch (java.io.IOException e) { throw LLMProviderException.transport("AWS Bedrock InvokeModel", e); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LLMProviderException("AWS Bedrock InvokeModel interrupted", null, false, e); }
    }

    private static JsonObject build(Profile profile, String model, List<ChatMessage> messages, List<Map<String, Object>> tools, double temperature, int maxTokens) {
        JsonObject body = new JsonObject();
        List<ChatMessage> safeMessages = messages == null ? List.of() : messages;
        switch (profile) {
            case ANTHROPIC -> {
                body.addProperty("anthropic_version", "bedrock-2023-05-31");
                body.addProperty("max_tokens", maxTokens);
                body.addProperty("temperature", temperature);
                JsonArray wire = new JsonArray();
                StringBuilder system = new StringBuilder();
                for (ChatMessage message : safeMessages) {
                    if (message == null) continue;
                    if ("system".equalsIgnoreCase(message.role())) {
                        if (message.content() != null && !message.content().isBlank()) {
                            if (system.length() > 0) system.append('\n');
                            system.append(message.content());
                        }
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("role", "assistant".equalsIgnoreCase(message.role()) ? "assistant" : "user");
                    JsonArray content = new JsonArray();
                    if (message.content() != null && !message.content().isEmpty()) {
                        JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", message.content()); content.add(text);
                    }
                    if (message.toolCalls() != null) for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                        if (call == null || call.name() == null || call.name().isBlank()) continue;
                        JsonObject use = new JsonObject(); use.addProperty("type", "tool_use");
                        use.addProperty("id", call.id() == null ? "invoke_" + content.size() : call.id());
                        use.addProperty("name", call.name()); use.add("input", ProviderSupport.toolArgumentsObject(call.arguments())); content.add(use);
                    }
                    if ("tool".equalsIgnoreCase(message.role()) && message.toolCallId() != null) {
                        JsonObject result = new JsonObject(); result.addProperty("type", "tool_result"); result.addProperty("tool_use_id", message.toolCallId()); result.addProperty("content", message.content() == null ? "" : message.content()); content.add(result);
                    }
                    if (!content.isEmpty()) { item.add("content", content); wire.add(item); }
                }
                if (system.length() > 0) body.addProperty("system", system.toString());
                body.add("messages", wire);
                JsonArray declarations = anthropicTools(tools);
                if (!declarations.isEmpty()) body.add("tools", declarations);
            }
            case NOVA -> {
                body.addProperty("schemaVersion", "messages-v1");
                JsonArray wire = new JsonArray(); JsonArray system = new JsonArray();
                for (ChatMessage message : safeMessages) {
                    if (message == null) continue;
                    if ("system".equalsIgnoreCase(message.role())) { if (message.content() != null) { JsonObject t = new JsonObject(); t.addProperty("text", message.content()); system.add(t); } continue; }
                    JsonObject item = new JsonObject(); item.addProperty("role", "assistant".equalsIgnoreCase(message.role()) ? "assistant" : "user"); JsonArray content = new JsonArray();
                    if (message.content() != null && !message.content().isEmpty()) { JsonObject t = new JsonObject(); t.addProperty("text", message.content()); content.add(t); }
                    if (!content.isEmpty()) { item.add("content", content); wire.add(item); }
                }
                if (!system.isEmpty()) body.add("system", system); body.add("messages", wire);
                JsonObject inference = new JsonObject(); inference.addProperty("maxTokens", maxTokens); inference.addProperty("temperature", temperature); body.add("inferenceConfig", inference);
                JsonArray declarations = novaTools(tools); if (!declarations.isEmpty()) { JsonObject config = new JsonObject(); config.add("tools", declarations); body.add("toolConfig", config); }
            }
            case MISTRAL -> { body.add("messages", NativeProviderSupport.messages(safeMessages)); body.addProperty("max_tokens", maxTokens); body.addProperty("temperature", temperature); JsonArray wire = NativeProviderSupport.tools(tools); if (!wire.isEmpty()) body.add("tools", wire); }
            case LLAMA -> { body.addProperty("prompt", prompt(safeMessages)); body.addProperty("max_gen_len", maxTokens); body.addProperty("temperature", temperature); }
            case COHERE -> { body.addProperty("message", lastUser(safeMessages)); body.addProperty("max_tokens", maxTokens); body.addProperty("temperature", temperature); }
            case TITAN -> { body.addProperty("inputText", lastUser(safeMessages)); JsonObject config = new JsonObject(); config.addProperty("maxTokenCount", maxTokens); config.addProperty("temperature", temperature); body.add("textGenerationConfig", config); }
        }
        return body;
    }

    private static String inferRegion(String host) {
        String[] parts = host.split("\\.");
        if (parts.length >= 4 && (parts[0].equals("bedrock-runtime") || parts[0].equals("bedrock-runtime-fips"))) {
            return parts[1];
        }
        return "us-east-1";
    }

    private static JsonArray anthropicTools(List<Map<String, Object>> tools) {
        JsonArray result = new JsonArray();
        if (tools == null) return result;
        for (Map<String, Object> tool : tools) {
            if (tool == null || !(tool.get("function") instanceof Map<?, ?> function)) continue;
            Object name = function.get("name"); if (name == null || String.valueOf(name).isBlank()) continue;
            JsonObject item = new JsonObject(); item.addProperty("name", String.valueOf(name));
            if (function.get("description") != null) item.addProperty("description", String.valueOf(function.get("description")));
            item.add("input_schema", function.get("parameters") == null ? new JsonObject() : NativeProviderSupport.GSON.toJsonTree(function.get("parameters"))); result.add(item);
        }
        return result;
    }

    private static JsonArray novaTools(List<Map<String, Object>> tools) {
        JsonArray result = new JsonArray();
        if (tools == null) return result;
        for (Map<String, Object> tool : tools) {
            if (tool == null || !(tool.get("function") instanceof Map<?, ?> function)) continue;
            Object name = function.get("name"); if (name == null || String.valueOf(name).isBlank()) continue;
            JsonObject spec = new JsonObject(); spec.addProperty("name", String.valueOf(name));
            if (function.get("description") != null) spec.addProperty("description", String.valueOf(function.get("description")));
            spec.add("inputSchema", jsonObject("json", function.get("parameters"))); JsonObject wrapper = new JsonObject(); wrapper.add("toolSpec", spec); result.add(wrapper);
        }
        return result;
    }

    private static JsonObject jsonObject(String key, Object value) { JsonObject object = new JsonObject(); object.add(key, value == null ? new JsonObject() : NativeProviderSupport.GSON.toJsonTree(value)); return object; }
    private static String prompt(List<ChatMessage> messages) { StringBuilder b = new StringBuilder(); for (ChatMessage m : messages) { if (m == null) continue; if (b.length() > 0) b.append('\n'); b.append(m.role()).append(": ").append(m.content() == null ? "" : m.content()); } return b.toString(); }
    private static String lastUser(List<ChatMessage> messages) { String result = ""; for (ChatMessage m : messages) if (m != null && "user".equalsIgnoreCase(m.role())) result = m.content() == null ? "" : m.content(); return result; }

    static LLMResponse parse(String json, String model, Profile profile) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject(); String text = null; List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        if (profile == Profile.ANTHROPIC && root.has("content") && root.get("content").isJsonArray()) { for (var item : root.getAsJsonArray("content")) { if (!item.isJsonObject()) continue; JsonObject block = item.getAsJsonObject(); if (block.has("text")) text = (text == null ? "" : text + "\n") + block.get("text").getAsString(); if (block.has("type") && "tool_use".equals(block.get("type").getAsString()) && block.has("name")) calls.add(new ChatMessage.ToolCallRef(block.has("id") ? block.get("id").getAsString() : "anthropic_call_" + calls.size(), block.get("name").getAsString(), block.has("input") ? NativeProviderSupport.toolArguments(block.get("input")) : "{}")); } }
        else if (profile == Profile.NOVA && root.has("output") && root.get("output").isJsonObject()) {
            JsonObject output = root.getAsJsonObject("output"); JsonObject message = output.has("message") && output.get("message").isJsonObject() ? output.getAsJsonObject("message") : new JsonObject();
            JsonArray content = message.has("content") && message.get("content").isJsonArray() ? message.getAsJsonArray("content") : new JsonArray();
            for (var item : content) if (item.isJsonObject()) { JsonObject block = item.getAsJsonObject(); if (block.has("text")) text = (text == null ? "" : text + "\n") + block.get("text").getAsString(); if (block.has("toolUse") && block.get("toolUse").isJsonObject()) { JsonObject use = block.getAsJsonObject("toolUse"); String id = use.has("toolUseId") ? use.get("toolUseId").getAsString() : "nova_call_" + calls.size(); String name = use.has("name") ? use.get("name").getAsString() : ""; if (!name.isBlank()) calls.add(new ChatMessage.ToolCallRef(id, name, use.has("input") ? NativeProviderSupport.toolArguments(use.get("input")) : "{}")); } }
        }
        else if (profile == Profile.MISTRAL && root.has("choices") && root.get("choices").isJsonArray() && !root.getAsJsonArray("choices").isEmpty()) { JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message"); text = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : null; if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) for (var element : message.getAsJsonArray("tool_calls")) if (element.isJsonObject()) { JsonObject call = element.getAsJsonObject(); JsonObject fn = call.has("function") && call.get("function").isJsonObject() ? call.getAsJsonObject("function") : call; if (fn.has("name")) calls.add(new ChatMessage.ToolCallRef(call.has("id") ? call.get("id").getAsString() : "mistral_call_" + calls.size(), fn.get("name").getAsString(), fn.has("arguments") ? NativeProviderSupport.toolArguments(fn.get("arguments")) : "{}")); } }
        else if (profile == Profile.LLAMA) text = root.has("generation") ? root.get("generation").getAsString() : null;
        else if (profile == Profile.COHERE) { if (root.has("text")) text = root.get("text").getAsString(); else if (root.has("generations") && root.get("generations").isJsonArray() && !root.getAsJsonArray("generations").isEmpty()) text = root.getAsJsonArray("generations").get(0).getAsJsonObject().has("text") ? root.getAsJsonArray("generations").get(0).getAsJsonObject().get("text").getAsString() : null; }
        else if (profile == Profile.TITAN && root.has("results") && root.get("results").isJsonArray() && !root.getAsJsonArray("results").isEmpty()) { JsonObject result = root.getAsJsonArray("results").get(0).getAsJsonObject(); if (result.has("outputText")) text = result.get("outputText").getAsString(); }
        if (text == null && calls.isEmpty()) throw new RuntimeException("Bedrock InvokeModel response has no text or tool call");
        int input = 0, output = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) { JsonObject usage = root.getAsJsonObject("usage"); input = firstInt(usage, "input_tokens", "prompt_tokens", "inputTokens", "promptTokenCount"); output = firstInt(usage, "output_tokens", "completion_tokens", "outputTokens", "candidatesTokenCount"); }
        String finish = calls.isEmpty() ? "stop" : "tool_calls"; return new LLMResponse("", model, new LLMResponse.Choice(0, new ChatMessage("assistant", text, calls.isEmpty() ? null : calls, null), finish), new LLMResponse.Usage(input, output, input + output), finish);
    }
    private static int firstInt(JsonObject object, String... keys) { for (String key : keys) try { if (object.has(key) && object.get(key).isJsonPrimitive()) return Math.max(0, object.get(key).getAsInt()); } catch (RuntimeException ignored) {} return 0; }
    enum Profile { ANTHROPIC, NOVA, MISTRAL, LLAMA, COHERE, TITAN; static Profile forModel(String model) { String m = model.toLowerCase(Locale.ROOT); if (m.startsWith("anthropic.")) return ANTHROPIC; if (m.startsWith("amazon.nova")) return NOVA; if (m.startsWith("mistral.")) return MISTRAL; if (m.startsWith("meta.llama")) return LLAMA; if (m.startsWith("cohere.")) return COHERE; if (m.startsWith("amazon.titan")) return TITAN; throw new IllegalArgumentException("No InvokeModel profile for model '" + model + "'; use Bedrock Converse or register a model adapter"); } }
}
