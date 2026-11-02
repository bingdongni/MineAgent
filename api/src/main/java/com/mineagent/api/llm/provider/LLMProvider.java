package com.mineagent.api.llm.provider;

import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.ProviderSessionState;

import java.util.List;
import java.util.Map;

/**
 * A provider for LLM completions. Implementations translate MineAgent's
 * unified messages and tool schemas to one concrete wire protocol.
 *
 * <p>The registry is intentionally open: built-in adapters cover OpenAI Chat
 * Completions, Anthropic Messages, Gemini generateContent, and AWS Bedrock
 * Converse, while another mod can register a provider for a private or future
 * protocol.
 */
public interface LLMProvider {

    /**
     * Create a companion-owned transport. Legacy extensions retain their shared,
     * synchronized instance until they explicitly implement session isolation.
     */
    default LLMProvider newSession() { return this; }

    /**
     * Advertise protocol features without requiring callers to inspect model
     * names.  Legacy providers remain chat-completions providers by default.
     */
    default LLMCapabilities capabilities() {
        return LLMCapabilities.chatDefaults();
    }

    /** Current transport/session state, if this provider supports it. */
    default ProviderSessionState sessionState(String model) {
        return ProviderSessionState.empty(providerId(), model);
    }

    /** Restore transport/session state loaded from durable companion memory. */
    default void restoreSessionState(ProviderSessionState state) {
        // Legacy providers have no stateful wire protocol to restore.
    }

    /** A unique id for this provider (e.g. "openai", "deepseek", "anthropic"). */
    String providerId();

    /** Human-readable name. */
    String displayName();

    /** The default base URL for this provider's API. */
    String defaultBaseUrl();

    /**
     * Whether companion creation must reject an empty API key.
     *
     * <p>Official hosted adapters require authentication by default. Local or
     * custom adapters may override this for endpoints such as Ollama, LM
     * Studio, or vLLM that intentionally expose an unauthenticated compatible
     * API on the user's own machine.
     */
    default boolean requiresApiKey() {
        return true;
    }

    /**
     * Whether this adapter participates in model-name auto detection.
     * Protocol adapters that accept every model id (for example the native
     * Responses adapter) must remain opt-in: choosing a wire protocol from a
     * name alone is unsafe and would silently send a relay's Chat Completions
     * model to the wrong endpoint.  Explicit provider selection is always
     * honored regardless of this flag.
     */
    default boolean autoDetectable() {
        return true;
    }

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
     * State-aware entry point.  The default bridge preserves source and binary
     * compatibility for existing providers while newer adapters can retain
     * Responses output items and session state.
     */
    default LLMResponse complete(LLMRequest request) {
        return complete(request.baseUrl(), request.apiKey(), request.model(),
                request.messages(), request.tools(), request.temperature(),
                request.maxTokens(), request.reasoningEffort());
    }

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
