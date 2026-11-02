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

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Cohere native Chat API v2 adapter. */
public final class CohereProvider implements LLMProvider {
    @Override public CohereProvider newSession() { return new CohereProvider(); }
    @Override public String providerId() { return "cohere"; }
    @Override public String displayName() { return "Cohere Command"; }
    @Override public String defaultBaseUrl() { return "https://api.cohere.com"; }
    @Override public boolean supportsModel(String model) {
        return model != null && (model.startsWith("command-") || model.startsWith("CohereForAI/"));
    }
    @Override public List<String> defaultModels() {
        return List.of("command-a-03-2025", "command-r-plus", "command-r7b-12-2024");
    }
    @Override public LLMCapabilities capabilities() {
        return new LLMCapabilities(EnumSet.of(
                LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0);
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                List<ChatMessage> messages, List<Map<String, Object>> tools,
                                double temperature, int maxTokens, String reasoningEffort) {
        return completeInternal(baseUrl, apiKey, model, messages, tools, temperature,
                maxTokens, Duration.ofSeconds(120));
    }

    @Override public LLMResponse complete(LLMRequest request) {
        if (request == null) throw new IllegalArgumentException("Cohere request is null");
        return completeInternal(request.baseUrl(), request.apiKey(), request.model(),
                request.messages(), request.tools(), request.temperature(), request.maxTokens(),
                request.deadline());
    }

    private LLMResponse completeInternal(String baseUrl, String apiKey, String model,
                                          List<ChatMessage> messages,
                                          List<Map<String, Object>> tools,
                                          double temperature, int maxTokens, Duration deadline) {
        ProviderSupport.validateRequest("Cohere", apiKey, model, messages, temperature, maxTokens);
        String url = NativeProviderSupport.endpoint(baseUrl, defaultBaseUrl(), "/v2/chat", "Cohere");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", NativeProviderSupport.messages(messages));
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonArray wireTools = NativeProviderSupport.tools(tools);
        if (!wireTools.isEmpty()) body.add("tools", wireTools);
        try {
            var response = NativeProviderSupport.post("Cohere", url,
                    NativeProviderSupport.GSON.toJson(body),
                    Map.of("Authorization", "Bearer " + apiKey), deadline);
            try {
                return parse(response.body(), model);
            } catch (RuntimeException malformed) {
                throw ProviderSupport.malformedResponse("Cohere", response.body(), malformed);
            }
        } catch (LLMProviderException failure) {
            throw failure;
        }
    }

    LLMResponse parse(String json, String requestedModel) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject message = root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : new JsonObject();
        String text = null;
        if (message.has("content")) {
            JsonElement content = message.get("content");
            if (content.isJsonPrimitive()) text = content.getAsString();
            else if (content.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (!part.isJsonObject()) continue;
                    JsonObject object = part.getAsJsonObject();
                    if (object.has("text")) {
                        if (joined.length() > 0) joined.append('\n');
                        joined.append(object.get("text").getAsString());
                    }
                }
                text = joined.length() == 0 ? null : joined.toString();
            }
        }
        List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                ? message.getAsJsonArray("tool_calls") : new JsonArray();
        for (JsonElement element : toolCalls) {
            if (!element.isJsonObject()) continue;
            JsonObject call = element.getAsJsonObject();
            JsonObject function = call.has("function") && call.get("function").isJsonObject()
                    ? call.getAsJsonObject("function") : call;
            if (!function.has("name")) continue;
            String id = call.has("id") ? call.get("id").getAsString() : "cohere_call_" + calls.size();
            String arguments = function.has("arguments")
                    ? NativeProviderSupport.toolArguments(function.get("arguments")) : "{}";
            calls.add(new ChatMessage.ToolCallRef(id, function.get("name").getAsString(), arguments));
        }
        String finish = calls.isEmpty() ? root.has("finish_reason")
                ? root.get("finish_reason").getAsString() : "stop" : "tool_calls";
        int input = 0, output = 0;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            JsonObject tokens = usage.has("tokens") && usage.get("tokens").isJsonObject()
                    ? usage.getAsJsonObject("tokens") : usage;
            input = integer(tokens, "input_tokens");
            output = integer(tokens, "output_tokens");
        }
        return new LLMResponse(root.has("id") ? root.get("id").getAsString() : "",
                requestedModel, new LLMResponse.Choice(0,
                new ChatMessage("assistant", text, calls.isEmpty() ? null : calls, null), finish),
                new LLMResponse.Usage(input, output, input + output), finish);
    }

    private static int integer(JsonObject object, String key) {
        try { return object.has(key) ? Math.max(0, object.get(key).getAsInt()) : 0; }
        catch (RuntimeException ignored) { return 0; }
    }
}
