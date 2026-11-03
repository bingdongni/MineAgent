package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.ConversationItem;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.ProviderSessionState;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Native OpenAI Responses adapter for models such as GPT-6 Astra.
 *
 * <p>The regular chat adapter cannot represent Responses output items.  This
 * adapter keeps function calls structured, forwards previous-response and
 * compaction state, and falls back to Chat Completions only when an endpoint
 * explicitly reports that Responses is unavailable (404/405).  It never
 * treats encrypted reasoning as readable text.</p>
 */
public final class OpenAIResponsesProvider implements LLMProvider {
    private static final Gson GSON = new GsonBuilder().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();
    /**
     * Agent loops use companion-owned sessions; direct legacy callers may still
     * borrow the registry prototype. Keeping one mutable cursor here would let
     * one such caller continue another's response. The map is keyed by the stable
     * conversation identity carried in {@link LLMRequest}; a model-only key
     * remains for legacy direct callers/tests.
     */
    private final ConcurrentMap<String, ProviderSessionState> sessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> responsesUnsupported =
            new ConcurrentHashMap<>();
    private volatile String activeSessionKey;
    private final LLMProvider chatFallback;

    public OpenAIResponsesProvider() {
        this(OpenAICompatibleProvider.openai());
    }

    public OpenAIResponsesProvider(LLMProvider chatFallback) {
        this.chatFallback = Objects.requireNonNull(chatFallback, "chatFallback");
    }

    @Override public String providerId() { return "openai-responses"; }
    @Override public LLMProvider newSession() {
        return new OpenAIResponsesProvider(chatFallback.newSession());
    }
    @Override public String displayName() { return "OpenAI Responses (Astra)"; }
    @Override public String defaultBaseUrl() { return "https://api.openai.com"; }
    @Override public boolean requiresApiKey() { return true; }
    @Override public boolean supportsModel(String model) {
        return model != null && !model.isBlank();
    }

    @Override public boolean autoDetectable() {
        // Responses is an endpoint choice, not a model-name guarantee.  Keep
        // it explicit so an arbitrary model on a Chat Completions relay is not
        // accidentally routed to /v1/responses; callers can still select
        // "openai-responses" for any current or future Responses model.
        return false;
    }
    @Override public List<String> defaultModels() { return List.of("gpt-6-astra"); }

    @Override
    public LLMCapabilities capabilities() {
        return new LLMCapabilities(EnumSet.of(
                LLMCapabilities.Feature.RESPONSES,
                LLMCapabilities.Feature.STATEFUL_RESPONSES,
                LLMCapabilities.Feature.SERVER_COMPACTION,
                LLMCapabilities.Feature.OPAQUE_REASONING_STATE,
                LLMCapabilities.Feature.PROMPT_CACHE_KEY,
                LLMCapabilities.Feature.PROMPT_CACHE_BREAKPOINT,
                LLMCapabilities.Feature.STRUCTURED_OUTPUTS,
                LLMCapabilities.Feature.FUNCTION_CALLING,
                LLMCapabilities.Feature.STREAMING), 1_050_000, 128_000);
    }

    @Override
    public ProviderSessionState sessionState(String model) {
        String key = activeSessionKey == null ? key(null, model) : activeSessionKey;
        ProviderSessionState current = sessions.get(key);
        if (current == null) current = ProviderSessionState.empty(providerId(), model);
        if (!Objects.equals(model, current.model())) {
            // Response ids are model-bound. Carrying one across a model change
            // makes the first request on the new model fail or, on permissive
            // relays, attach to the wrong conversation.
            return ProviderSessionState.empty(providerId(), model);
        }
        return current;
    }

    @Override
    public void restoreSessionState(ProviderSessionState state) {
        if (state != null && providerId().equals(state.providerId())) {
            String key = key(state.conversationId(), state.model());
            sessions.put(key, state);
            activeSessionKey = key;
        }
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                List<ChatMessage> messages,
                                List<Map<String, Object>> tools,
                                double temperature, int maxTokens,
                                String reasoningEffort) {
        // The legacy overload has no conversation identity. It must therefore
        // use the model-only compatibility cursor, never whichever companion
        // happened to be the last state-aware caller on this singleton. Using
        // activeSessionKey here leaked response IDs between companions for
        // extensions that still call the old API.
        ProviderSessionState state = sessions.getOrDefault(key(null, model),
                ProviderSessionState.empty(providerId(), model));
        return complete(new LLMRequest(baseUrl, apiKey, model, messages, messages, List.of(),
                tools, temperature, maxTokens, reasoningEffort,
                state.previousResponseId(), state.conversationId(),
                "openai-responses:" + model, Duration.ofSeconds(120), true));
    }

    @Override
    public LLMResponse complete(LLMRequest request) {
        ProviderSupport.validateRequest(providerId(), request.apiKey(), request.model(),
                request.messages(), request.temperature(), request.maxTokens(), true, true);
        if (request.messages().isEmpty() && request.items().isEmpty()
                && request.previousResponseId() == null) {
            throw new IllegalArgumentException(providerId() + " request has no input");
        }
        String base = ProviderSupport.validatedBaseUrl(request.baseUrl(), defaultBaseUrl(), providerId());
        activate(request);
        if (responsesUnsupported.containsKey(endpointKey(base, request.model()))) {
            // Once an endpoint has explicitly returned 404/405, probing it on
            // every turn only adds latency and token-free HTTP traffic.  Keep
            // the local full history path until the provider is recreated (a
            // configuration change creates a fresh instance at startup).
            return chatFallback.complete(withFreshHistory(request));
        }
        return send(request, base, false);
    }

    private LLMResponse send(LLMRequest request, String base,
                             boolean recoveredExpiredSession) {
        JsonObject body = buildBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ProviderSupport.endpoint(base, "/v1/responses")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + request.apiKey())
                .timeout(request.deadline())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 405) {
                // A relay may expose only Chat Completions.  Keep the user's
                // endpoint and model, but do not retry other permanent errors.
                // Clear the Responses cursor before falling back: retaining a
                // response id from an endpoint that just proved unsupported
                // would make every subsequent turn repeat the failed probe.
                responsesUnsupported.put(endpointKey(base, request.model()), Boolean.TRUE);
                clearActive(request);
                return chatFallback.complete(withFreshHistory(request));
            }
            if (!recoveredExpiredSession && response.statusCode() == 400
                    && request.previousResponseId() != null
                    && rejectedPreviousResponse(response.body())) {
                // Stored response ids can expire or be unavailable on a relay
                // after restart. Rebuild once from MineAgent's durable local
                // history instead of permanently bricking this companion.
                clearActive(request);
                return send(withFreshHistory(request), base, true);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http(providerId(), response.statusCode(),
                        response.body(), response.headers().firstValue("Retry-After").orElse(null));
            }
            return parseResponse(response.body(), request.model());
        } catch (LLMProviderException failure) {
            throw failure;
        } catch (java.io.IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LLMProviderException(providerId() + " request failed: "
                    + failure.getMessage(), null, true, failure);
        } catch (RuntimeException malformed) {
            throw ProviderSupport.malformedResponse(providerId(), null, malformed);
        }
    }

    JsonObject buildBody(LLMRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        JsonArray input = new JsonArray();
        // previous_response_id already includes the provider-owned output
        // items. Replay opaque items only while rebuilding a fresh chain.
        for (ConversationItem item : request.previousResponseId() == null
                ? request.items() : List.<ConversationItem>of()) {
            if (item != null && item.rawJson() != null) {
                try {
                    JsonElement parsed = JsonParser.parseString(item.rawJson());
                    if (parsed.isJsonArray()) {
                        for (JsonElement value : parsed.getAsJsonArray()) input.add(value.deepCopy());
                    } else input.add(parsed);
                } catch (RuntimeException ignored) { /* invalid opaque state is skipped */ }
            }
        }
        int durableInputEnd = input.size();
        for (int index=0; index<request.messages().size(); index++) {
            appendMessage(input, request.messages().get(index));
            if (index < request.cacheableMessageCount()) durableInputEnd = input.size();
        }
        body.add("input", input);
        CachePrefixPolicy.applyResponses(request, body, durableInputEnd);
        JsonArray functions = new JsonArray();
        for (Map<String, Object> definition : request.tools()) {
            if (definition == null) continue;
            JsonObject source = GSON.toJsonTree(definition).getAsJsonObject();
            JsonObject fn = source.has("function") && source.get("function").isJsonObject()
                    ? source.getAsJsonObject("function") : source;
            if (!fn.has("name")) continue;
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            copy(fn, tool, "name"); copy(fn, tool, "description");
            copy(fn, tool, "parameters"); copy(fn, tool, "strict");
            functions.add(tool);
        }
        if (!functions.isEmpty()) body.add("tools", functions);
        body.addProperty("max_output_tokens", request.maxTokens());
        if (request.reasoningEffort() != null && !"off".equalsIgnoreCase(request.reasoningEffort())) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", normalizeEffort(request.reasoningEffort()));
            body.add("reasoning", reasoning);
        } else if (Double.isFinite(request.temperature())) {
            // Reasoning models reject sampling temperature on several
            // Responses deployments.  It remains available for ordinary
            // non-reasoning Responses models; omitting it when reasoning is
            // requested avoids a provider-specific 400 while preserving the
            // configured value wherever the protocol supports it.
            body.addProperty("temperature", request.temperature());
        }
        if (request.previousResponseId() != null) body.addProperty("previous_response_id", request.previousResponseId());
        // Stateful continuation requires the response to remain addressable by
        // previous_response_id.  The old store=false setting made the next
        // round fail with an unknown response even though the first call had
        // succeeded.
        body.addProperty("store", true);
        if (request.promptCacheKey() != null) body.addProperty("prompt_cache_key", request.promptCacheKey());
        if (request.allowServerCompaction()) {
            JsonArray management = new JsonArray();
            JsonObject compaction = new JsonObject();
            compaction.addProperty("type", "compaction");
            compaction.addProperty("compact_threshold", 200_000);
            management.add(compaction);
            body.add("context_management", management);
        }
        return body;
    }

    private static void appendMessage(JsonArray input, ChatMessage message) {
        if (message == null) return;
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            // A ChatMessage may contain both visible assistant text and tool
            // calls.  Responses represents those as separate input items;
            // returning early used to silently drop the text and changed the
            // model's conversational context after every tool round.
            if (message.content() != null && !message.content().isBlank()) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "message");
                text.addProperty("role", "assistant");
                text.addProperty("content", message.content());
                input.add(text);
            }
            for (ChatMessage.ToolCallRef call : message.toolCalls()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "function_call");
                item.addProperty("call_id", call.id());
                item.addProperty("name", call.name());
                item.addProperty("arguments", call.arguments() == null ? "{}" : call.arguments());
                input.add(item);
            }
            return;
        }
        if ("tool".equals(message.role())) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call_output");
            item.addProperty("call_id", message.toolCallId());
            item.addProperty("output", message.content() == null ? "" : message.content());
            input.add(item);
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", message.role());
        item.addProperty("content", message.content() == null ? "" : message.content());
        input.add(item);
    }

    LLMResponse parseResponse(String raw, String model) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        String id = string(root, "id", null);
        List<ChatMessage.ToolCallRef> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonArray opaqueItems = new JsonArray();
        List<ConversationItem> outputItems = new ArrayList<>();
        if (root.has("output") && root.get("output").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("output")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String type = string(item, "type", "");
                if ("function_call".equals(type)) {
                    String arguments = responseArguments(item.get("arguments"));
                    calls.add(new ChatMessage.ToolCallRef(string(item, "call_id", "call_unknown"),
                            string(item, "name", "unknown"), arguments));
                } else if ("message".equals(type) && item.has("content")) {
                    JsonElement contentValue = item.get("content");
                    if (contentValue.isJsonPrimitive()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(contentValue.getAsString());
                    } else if (contentValue.isJsonArray()) {
                        for (JsonElement content : contentValue.getAsJsonArray()) {
                            if (content.isJsonObject()
                                    && "output_text".equals(string(content.getAsJsonObject(), "type", ""))) {
                                if (text.length() > 0) text.append('\n');
                                text.append(string(content.getAsJsonObject(), "text", ""));
                            }
                        }
                    }
                } else if (type.contains("reasoning") || type.contains("compaction")) {
                    // Reasoning/compaction payloads are provider-owned opaque
                    // state. Preserve every item byte-for-byte as one bounded
                    // JSON array; never summarize or expose it as memory text.
                    opaqueItems.add(item.deepCopy());
                    outputItems.add(ConversationItem.opaque(type, item.toString()));
                }
            }
        }
        if (text.isEmpty()) text.append(string(root, "output_text", ""));
        LLMResponse.Usage usage = usage(root);
        String key = activeSessionKey == null ? key(null, model) : activeSessionKey;
        ProviderSessionState previous = sessions.getOrDefault(key,
                ProviderSessionState.empty(providerId(), model));
        // Provider-owned reasoning/compaction state must be bounded before it
        // reaches the durable companion file.  A relay bug returning an
        // unbounded encrypted blob must not grow memory or disk usage forever.
        String opaque = opaqueItems.isEmpty() ? previous.opaqueCompactionItem()
                : boundedOpaque(opaqueItems.toString());
        ProviderSessionState updated = new ProviderSessionState(providerId(), model,
                previous.conversationId(),
                id, opaque,
                previous.revision() + 1L);
        sessions.put(key, updated);
        ChatMessage message = calls.isEmpty()
                ? ChatMessage.assistant(text.toString())
                : ChatMessage.assistant(text.toString(), calls);
        return new LLMResponse(id, model,
                new LLMResponse.Choice(0, message, string(root, "status", "completed")),
                usage, string(root, "status", "completed"), outputItems);
    }

    private static LLMResponse.Usage usage(JsonObject root) {
        if (!root.has("usage") || !root.get("usage").isJsonObject()) return null;
        return UsageParser.responses(root.getAsJsonObject("usage"));
    }

    private static String normalizeEffort(String effort) {
        String value = effort == null ? "medium" : effort.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) { case "low", "medium", "high", "xhigh", "max" -> value; default -> "medium"; };
    }

    private static String boundedOpaque(String value) {
        if (value == null) return null;
        final int max = 262_144;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void activate(LLMRequest request) {
        String sessionKey = key(request.conversationId(), request.model());
        activeSessionKey = sessionKey;
        ProviderSessionState existing = sessions.get(sessionKey);
        if (existing == null || !Objects.equals(existing.previousResponseId(),
                request.previousResponseId())) {
            sessions.put(sessionKey, new ProviderSessionState(providerId(), request.model(),
                    request.conversationId(), request.previousResponseId(),
                    existing == null ? null : existing.opaqueCompactionItem(),
                    existing == null ? 0L : existing.revision()));
        }
    }

    private void clearActive(LLMRequest request) {
        String sessionKey = key(request.conversationId(), request.model());
        sessions.put(sessionKey, ProviderSessionState.empty(providerId(), request.model())
                .withConversationId(request.conversationId()));
        activeSessionKey = sessionKey;
    }

    private static String key(String conversationId, String model) {
        return (conversationId == null || conversationId.isBlank()
                ? "model:" + (model == null ? "" : model) : "conversation:" + conversationId);
    }

    private static String endpointKey(String base, String model) {
        return base + "\u0000" + (model == null ? "" : model);
    }
    private static void copy(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && !source.get(key).isJsonNull()) target.add(key, source.get(key).deepCopy());
    }
    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static int integer(JsonObject object, String key, int fallback) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    /** Responses relays disagree on whether function arguments are a JSON
     * string or an already-decoded object. Preserve both forms faithfully so
     * a valid call is not reduced to an empty object by getAsString(). */
    private static String responseArguments(JsonElement value) {
        if (value == null || value.isJsonNull()) return "{}";
        try {
            return value.isJsonPrimitive() ? value.getAsString() : value.toString();
        } catch (RuntimeException ignored) {
            return "{}";
        }
    }

    private static boolean rejectedPreviousResponse(String body) {
        String value = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return value.contains("previous_response_id")
                || value.contains("previous response")
                || value.contains("response id") && (value.contains("not found")
                || value.contains("unknown") || value.contains("expired"));
    }

    private static LLMRequest withFreshHistory(LLMRequest request) {
        List<ChatMessage> history = request.fallbackMessages().isEmpty()
                ? request.messages() : request.fallbackMessages();
        return new LLMRequest(request.baseUrl(), request.apiKey(), request.model(),
                history, history, request.items(), request.tools(), request.temperature(),
                request.maxTokens(), request.reasoningEffort(), null,
                request.conversationId(), request.promptCacheKey(), request.deadline(),
                request.allowServerCompaction(), request.cacheableMessageCount() == 0 ? 0
                        : Math.max(0, history.size() - (request.messages().size() - request.cacheableMessageCount())));
    }
}
