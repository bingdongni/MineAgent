package com.mineagent.engine.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMResponse;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Ollama's native /api/chat protocol, including its non-streaming tool shape. */
public final class OllamaNativeProvider extends OpenAICompatibleProvider {
    @Override public OllamaNativeProvider newSession() { return new OllamaNativeProvider(); }
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    public OllamaNativeProvider() {
        super("ollama", "Ollama Native", "http://127.0.0.1:11434",
                List.of("llama3.2", "qwen3", "gemma3"), java.util.Set.of());
    }

    @Override public boolean requiresApiKey() { return false; }
    @Override public boolean autoDetectable() { return false; }
    @Override public boolean supportsModel(String model) { return model != null && !model.isBlank(); }
    @Override public LLMCapabilities capabilities() {
        return new LLMCapabilities(EnumSet.of(
                LLMCapabilities.Feature.CHAT_COMPLETIONS,
                LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0);
    }

    @Override protected String chatEndpoint(String base, String model) {
        return ProviderSupport.endpoint(base, "/api/chat");
    }

    @Override protected JsonObject buildRequestBody(String model, List<ChatMessage> messages,
                                                     List<Map<String, Object>> tools,
                                                     double temperature, int maxTokens,
                                                     boolean stream, String cacheKey) {
        JsonObject body = super.buildRequestBody(model, messages, tools, temperature,
                maxTokens, false, null);
        // Ollama defaults to NDJSON streaming. Explicitly disable it so the
        // regular HttpResponse parser receives one complete JSON document.
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxTokens);
        body.add("options", options);
        body.remove("temperature");
        body.remove("max_tokens");
        return body;
    }

    @Override protected LLMResponse parseResponse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject message = root.has("message") && root.get("message").isJsonObject()
                ? root.getAsJsonObject("message") : new JsonObject();
        String text = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : null;
        List<ChatMessage.ToolCallRef> calls = new java.util.ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (var item : message.getAsJsonArray("tool_calls")) {
                if (!item.isJsonObject()) continue;
                JsonObject function = item.getAsJsonObject().has("function")
                        ? item.getAsJsonObject().getAsJsonObject("function") : new JsonObject();
                if (!function.has("name")) continue;
                String id = item.getAsJsonObject().has("id")
                        ? item.getAsJsonObject().get("id").getAsString()
                        : "ollama_call_" + calls.size();
                    String args = function.has("arguments")
                        ? NativeProviderSupport.toolArguments(function.get("arguments")) : "{}";
                calls.add(new ChatMessage.ToolCallRef(id, function.get("name").getAsString(), args));
            }
        }
        int prompt = root.has("prompt_eval_count") ? root.get("prompt_eval_count").getAsInt() : 0;
        int output = root.has("eval_count") ? root.get("eval_count").getAsInt() : 0;
        String finish = calls.isEmpty() ? (root.has("done_reason")
                ? root.get("done_reason").getAsString() : "stop") : "tool_calls";
        return new LLMResponse("", root.has("model") ? root.get("model").getAsString() : "",
                new LLMResponse.Choice(0, new ChatMessage("assistant", text,
                        calls.isEmpty() ? null : calls, null), finish),
                new LLMResponse.Usage(prompt, output, prompt + output), finish);
    }
}
