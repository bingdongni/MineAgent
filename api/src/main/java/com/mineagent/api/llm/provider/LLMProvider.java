package com.mineagent.api.llm.provider;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;

import java.util.List;
import java.util.Map;

/**
 * A provider for LLM completions — implements the wire protocol for a
 * specific model family (OpenAI, Anthropic, Google, etc.).
 *
 * <p>All providers use the OpenAI-compatible chat completions API format
 * (most providers now support this), but each has its own endpoint, auth,
 * and quirks that this interface abstracts away.
 */
public interface LLMProvider {

    /** A unique id for this provider (e.g. "openai", "deepseek", "anthropic"). */
    String providerId();

    /** Human-readable name. */
    String displayName();

    /** The default base URL for this provider's API. */
    String defaultBaseUrl();

    /**
     * Execute a chat completion request.
     *
     * @param baseUrl          the API base URL (may be overridden for proxies)
     * @param apiKey           the API key
     * @param model            the model name (provider-specific, e.g. "gpt-4o")
     * @param messages         the conversation messages
     * @param tools            tool definitions in OpenAI function-calling format, or null
     * @param temperature      sampling temperature (0.0 - 2.0)
     * @param maxTokens        maximum tokens to generate
     * @param reasoningEffort reasoning effort level (e.g. "off","low","medium",
     *                         "high","xhigh","max"), or null/empty for provider
     *                         default. Not all providers/levels are supported;
     *                         unsupported values are mapped or ignored.
     * @return the completion response
     */
    LLMResponse complete(String baseUrl, String apiKey, String model,
                          List<ChatMessage> messages,
                          List<Map<String, Object>> tools,
                          double temperature, int maxTokens,
                          String reasoningEffort);

    /**
     * Execute a streaming chat completion request.
     *
     * @return an iterable of partial responses
     */
    default Iterable<LLMResponse> completeStream(String baseUrl, String apiKey, String model,
                                                  List<ChatMessage> messages,
                                                  List<Map<String, Object>> tools,
                                                  double temperature, int maxTokens,
                                                  String reasoningEffort) {
        // Default: non-streaming (wrap single response)
        return List.of(complete(baseUrl, apiKey, model, messages, tools,
                temperature, maxTokens, reasoningEffort));
    }

    /**
     * Check if this provider supports the given model name.
     * Used for auto-detection when the user only specifies an API key.
     */
    boolean supportsModel(String model);

    /**
     * List the default models this provider supports.
     */
    List<String> defaultModels();
}
