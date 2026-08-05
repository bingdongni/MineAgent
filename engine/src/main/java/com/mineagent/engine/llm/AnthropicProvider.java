package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Anthropic (Claude) provider - uses the Anthropic Messages API format,
 * which differs from OpenAI's in message structure and tool calling.
 *
 * <p>We translate between the unified {@link ChatMessage} format and
 * Anthropic's native format internally.
 */
public class AnthropicProvider implements LLMProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String providerId() { return "anthropic"; }

    @Override
    public String displayName() { return "Anthropic (Claude)"; }

    @Override
    public String defaultBaseUrl() { return "https://api.anthropic.com"; }

    @Override
    public boolean supportsModel(String model) {
        return model != null && (model.startsWith("claude-") || model.contains("claude"));
    }

    @Override
    public List<String> defaultModels() {
        return List.of(
                // Claude Fable 5 (2026年6月旗舰，1M上下文)
                "claude-fable-5",
                // Claude Opus 5 (2026年7月24日发布，1M上下文，128K输出，半价逼近Fable 5)
                "claude-opus-5",
                // Claude Opus 4.8 (1M上下文)
                "claude-opus-4-8",
                // Claude Opus 4.7
                "claude-opus-4-7",
                // Claude Sonnet 5
                "claude-sonnet-5",
                // Claude 4.6 系列
                "claude-sonnet-4-6", "claude-opus-4-6",
                // Claude Opus 4.5
                "claude-opus-4-5-20251101",
                // Claude Sonnet 4
                "claude-sonnet-4-20250514",
                // Claude Opus 4
                "claude-opus-4-20250514",
                // Claude 3.7 Sonnet
                "claude-3-7-sonnet-20250219",
                // Claude 3.5 系列
                "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022",
                // Claude Haiku 4.5
                "claude-haiku-4-5-20251001"
        );
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                 List<ChatMessage> messages,
                                 List<Map<String, Object>> tools,
                                 double temperature, int maxTokens,
                                 String reasoningEffort) {
        ProviderSupport.validateRequest(
                "Anthropic", apiKey, model, messages, temperature, maxTokens);
        String resolvedBaseUrl = ProviderSupport.validatedBaseUrl(
                baseUrl, defaultBaseUrl(), "Anthropic");
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", maxTokens);

            // Per Anthropic API spec: when extended thinking is enabled
            // (reasoningEffort != null && != "off"), temperature MUST be 1.0
            // — any other value causes a 400 error. The newer
            // output_config.effort path on Claude 5+ has the same constraint
            // whenever thinking is actively engaged.
            // We therefore override the caller-supplied temperature to 1.0
            // whenever thinking is requested, regardless of model.
            double effectiveTemperature =
                    (reasoningEffort != null && !reasoningEffort.isBlank()
                            && !"off".equalsIgnoreCase(reasoningEffort))
                    ? 1.0 : temperature;
            body.addProperty("temperature", effectiveTemperature);

            // Extract system message
            StringBuilder systemPrompt = new StringBuilder();
            JsonArray msgsArr = new JsonArray();

            for (ChatMessage msg : messages) {
                if ("system".equals(msg.role())) {
                    // Skip null/blank system content — StringBuilder.append(null)
                    // would otherwise append the literal string "null",
                    // corrupting the system prompt sent to the model.
                    if (msg.content() == null || msg.content().isEmpty()) continue;
                    if (systemPrompt.length() > 0) systemPrompt.append("\n\n");
                    systemPrompt.append(msg.content());
                    continue;
                }

                String role;
                JsonArray contentBlocks = new JsonArray();

                // Tool results are user content blocks. Multiple results from
                // one assistant turn must be in ONE user message; appending via
                // appendMessage() below coalesces all adjacent blocks.
                if ("tool".equals(msg.role()) && msg.toolCallId() != null) {
                    role = "user";
                    JsonObject resultBlock = new JsonObject();
                    resultBlock.addProperty("type", "tool_result");
                    resultBlock.addProperty("tool_use_id", msg.toolCallId());
                    resultBlock.addProperty("content", msg.content() != null ? msg.content() : "");
                    contentBlocks.add(resultBlock);
                }
                // Assistant messages with tool calls — content is an array of
                // text blocks + tool_use blocks. Anthropic requires content
                // field to be present on every message.
                else if ("assistant".equals(msg.role())
                        && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    role = "assistant";
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "text");
                        textBlock.addProperty("text", msg.content());
                        contentBlocks.add(textBlock);
                    }
                    for (var tc : msg.toolCalls()) {
                        JsonObject toolBlock = new JsonObject();
                        toolBlock.addProperty("type", "tool_use");
                        toolBlock.addProperty("id", tc.id());
                        toolBlock.addProperty("name", tc.name());
                        toolBlock.add("input",
                                ProviderSupport.toolArgumentsObject(tc.arguments()));
                        contentBlocks.add(toolBlock);
                    }
                }
                // Regular text messages (user or assistant without tool_calls).
                // Anthropic requires content field — use "" when null to avoid
                // "messages.X: content is required" errors.
                else {
                    role = translateRole(msg.role());
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "text");
                        textBlock.addProperty("text", msg.content());
                        contentBlocks.add(textBlock);
                    }
                }
                if (!contentBlocks.isEmpty()) {
                    appendMessage(msgsArr, role, contentBlocks);
                }
            }

            if (systemPrompt.length() > 0) {
                // Native Anthropic prompt caching is opt-in. Mark the stable
                // instruction/summary prefix as an ephemeral cache breakpoint;
                // volatile MineAgent state is a final user message and is not
                // included here, so it cannot invalidate this block each turn.
                JsonArray systemBlocks = new JsonArray();
                JsonObject systemBlock = new JsonObject();
                systemBlock.addProperty("type", "text");
                systemBlock.addProperty("text", systemPrompt.toString());
                JsonObject cacheControl = new JsonObject();
                cacheControl.addProperty("type", "ephemeral");
                systemBlock.add("cache_control", cacheControl);
                systemBlocks.add(systemBlock);
                body.add("system", systemBlocks);
            }
            body.add("messages", msgsArr);

            // Tools
            if (tools != null && !tools.isEmpty()) {
                JsonArray toolsArr = new JsonArray();
                for (Map<String, Object> tool : tools) {
                    // Convert OpenAI function format to Anthropic format
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>) tool.get("function");
                    if (function != null) {
                        JsonObject tObj = new JsonObject();
                        tObj.addProperty("name", (String) function.get("name"));
                        tObj.addProperty("description", (String) function.get("description"));
                        if (function.get("parameters") != null) {
                            tObj.add("input_schema", GSON.toJsonTree(function.get("parameters")));
                        }
                        toolsArr.add(tObj);
                    }
                }
                body.add("tools", toolsArr);
            }

            // Inject reasoning effort per official Anthropic docs (2026-08-01):
            //   - output_config.effort: low | medium | high | xhigh | max
            //   - Default is "high" — when effort is null/blank, inject nothing
            //     so the API uses its default.
            //   - Fable 5 / Mythos 5: adaptive always on, CANNOT disable → off maps to "low"
            //   - Opus 5 / Sonnet 5 / Opus 4.8 / Opus 4.7: CAN disable via thinking.type=disabled
            //   - Opus 4.6 / Sonnet 4.6: low/medium/high/max (no xhigh), CAN disable
            //   - Opus 4.5: low/medium/high (no max, no xhigh), CAN disable
            //   - Sonnet 4.5 / Haiku 4.5 / Opus 4 / Sonnet 4 / 3.7: budget_tokens only
            if (reasoningEffort != null && !reasoningEffort.isBlank()
                    && model != null && !model.isBlank()) {
                String e = reasoningEffort.toLowerCase();
                String m = model.toLowerCase();

                // Only Fable 5 and Mythos 5 truly cannot disable thinking
                boolean cannotDisable =
                        m.startsWith("claude-fable-5") || m.startsWith("claude-mythos");

                // Models that support output_config.effort
                boolean supportsEffort =
                        m.startsWith("claude-fable-5") || m.startsWith("claude-mythos")
                                || m.startsWith("claude-opus-5")
                                || m.startsWith("claude-opus-4-8")
                                || m.startsWith("claude-opus-4-7")
                                || m.startsWith("claude-opus-4-6")
                                || m.startsWith("claude-sonnet-5")
                                || m.startsWith("claude-sonnet-4-6")
                                || m.startsWith("claude-opus-4-5");

                // xhigh support: Fable 5, Mythos 5, Opus 5, Opus 4.8, Opus 4.7, Sonnet 5
                boolean supportsXhigh =
                        m.startsWith("claude-fable-5") || m.startsWith("claude-mythos")
                                || m.startsWith("claude-opus-5")
                                || m.startsWith("claude-opus-4-8")
                                || m.startsWith("claude-opus-4-7")
                                || m.startsWith("claude-sonnet-5");

                // max support: above + Opus 4.6, Sonnet 4.6
                boolean supportsMax = supportsXhigh
                        || m.startsWith("claude-opus-4-6")
                        || m.startsWith("claude-sonnet-4-6");

                if ("off".equals(e)) {
                    if (cannotDisable) {
                        // Fable 5 / Mythos 5 — cannot disable, map to lowest effort
                        JsonObject outputConfig = new JsonObject();
                        outputConfig.addProperty("effort", "low");
                        body.add("output_config", outputConfig);
                    } else {
                        // All other effort-capable + budget-only models — disable thinking
                        JsonObject thinking = new JsonObject();
                        thinking.addProperty("type", "disabled");
                        body.add("thinking", thinking);
                    }
                } else if (supportsEffort) {
                    // Map levels per model capabilities
                    String mapped;
                    if ("xhigh".equals(e) && !supportsXhigh) {
                        mapped = "high"; // Opus 4.6, Sonnet 4.6, Opus 4.5: no xhigh → high
                    } else if ("max".equals(e) && !supportsMax) {
                        mapped = "high"; // Opus 4.5: no max → high
                    } else if ("minimal".equals(e)) {
                        mapped = "low"; // Anthropic has no "minimal"
                    } else {
                        mapped = e; // low, medium, high, xhigh, max
                    }
                    JsonObject outputConfig = new JsonObject();
                    outputConfig.addProperty("effort", mapped);
                    body.add("output_config", outputConfig);
                } else if (maxTokens > 1024) {
                    // Older Claude 3.x / 3.5 / 3.7 / Sonnet 4 / Haiku 4.5 — budget_tokens only
                    // Enable thinking with budget_tokens.
                    // IMPORTANT: Anthropic requires budget_tokens < max_tokens.
                    // The legacy hard-coded 16000 broke this whenever max_tokens
                    // was below 16001 (default 2048) → API returned 400.
                    // Use 80% of max_tokens to leave room for the final answer,
                    // and clamp to the documented minimum (1024) when 80% is
                    // too small (still ensuring budget < max_tokens).
                    int budget = (int) (maxTokens * 0.8);
                    if (budget < 1024) budget = Math.min(1024, maxTokens - 1);
                    if (budget >= maxTokens) budget = maxTokens - 1;
                    if (budget < 1) budget = 1;
                    JsonObject thinking = new JsonObject();
                    thinking.addProperty("type", "enabled");
                    thinking.addProperty("budget_tokens", budget);
                    body.add("thinking", thinking);
                } else {
                    // Extended thinking has a documented minimum budget of
                    // 1024 and also requires budget_tokens < max_tokens. With
                    // max_tokens <= 1024 no valid budget exists, so omitting
                    // thinking is the only request that Anthropic will accept.
                    System.err.println("[MineAgent] Anthropic thinking disabled because "
                            + "maxTokens=" + maxTokens + " is too small");
                }
            }

            String url = ProviderSupport.endpoint(resolvedBaseUrl, "/v1/messages");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http("Anthropic",
                        response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            }

            try {
                return parseAnthropicResponse(response.body());
            } catch (RuntimeException e) {
                throw ProviderSupport.malformedResponse("Anthropic", response.body(), e);
            }
        } catch (java.io.IOException e) {
            // HttpTimeoutException is an IOException and is safe to retry.
            throw LLMProviderException.transport("Anthropic", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException("Anthropic API call interrupted",
                    null, false, e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private String translateRole(String role) {
        return switch (role) {
            case "tool" -> "user"; // tool results go in user messages
            default -> role;
        };
    }

    private static void appendMessage(JsonArray messages, String role,
                                      JsonArray contentBlocks) {
        if (!messages.isEmpty()) {
            JsonObject previous = messages.get(messages.size() - 1).getAsJsonObject();
            if (role.equals(previous.get("role").getAsString())) {
                JsonArray previousBlocks = previous.getAsJsonArray("content");
                for (JsonElement block : contentBlocks) {
                    previousBlocks.add(block);
                }
                return;
            }
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", contentBlocks);
        messages.add(message);
    }

    private LLMResponse parseAnthropicResponse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String id = root.has("id") ? root.get("id").getAsString() : "";
        String model = root.has("model") ? root.get("model").getAsString() : "";

        String content = null;
        List<ChatMessage.ToolCallRef> toolCalls = null;
        String stopReason = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                ? root.get("stop_reason").getAsString() : "";

        if (!root.has("content") || !root.get("content").isJsonArray()) {
            throw new RuntimeException("Anthropic API returned no content array: "
                    + ProviderSupport.truncate(json, 500));
        }
        {
            StringBuilder textContent = new StringBuilder();
            toolCalls = new ArrayList<>();

            for (var elem : root.getAsJsonArray("content")) {
                if (!elem.isJsonObject()) continue;
                JsonObject block = elem.getAsJsonObject();
                if (!block.has("type") || !block.get("type").isJsonPrimitive()) continue;
                String type = block.get("type").getAsString();
                if ("text".equals(type)) {
                    if (!block.has("text") || !block.get("text").isJsonPrimitive()) continue;
                    if (textContent.length() > 0) textContent.append("\n");
                    textContent.append(block.get("text").getAsString());
                } else if ("tool_use".equals(type)) {
                    if (!block.has("id") || !block.has("name")
                            || !block.get("id").isJsonPrimitive()
                            || !block.get("name").isJsonPrimitive()) continue;
                    String tcId = block.get("id").getAsString();
                    String tcName = block.get("name").getAsString();
                    String tcArgs = block.has("input")
                            ? GSON.toJson(block.get("input")) : "{}";
                    toolCalls.add(new ChatMessage.ToolCallRef(tcId, tcName, tcArgs));
                }
            }

            content = textContent.length() > 0 ? textContent.toString() : null;
            if (toolCalls.isEmpty()) toolCalls = null;
        }

        ChatMessage message = new ChatMessage("assistant", content, toolCalls, null);

        LLMResponse.Usage usage = null;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usageObj = root.getAsJsonObject("usage");
            int uncached = usageObj.has("input_tokens")
                    ? usageObj.get("input_tokens").getAsInt() : 0;
            int cacheRead = usageObj.has("cache_read_input_tokens")
                    ? usageObj.get("cache_read_input_tokens").getAsInt() : 0;
            int cacheCreation = usageObj.has("cache_creation_input_tokens")
                    ? usageObj.get("cache_creation_input_tokens").getAsInt() : 0;
            int output = usageObj.has("output_tokens")
                    ? usageObj.get("output_tokens").getAsInt() : 0;
            int totalInput = uncached + cacheRead + cacheCreation;
            usage = new LLMResponse.Usage(
                    totalInput, output, totalInput + output,
                    cacheRead, cacheCreation
            );
        }

        String finishReason = switch (stopReason) {
            case "end_turn" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> stopReason;
        };

        return new LLMResponse(id, model,
                new LLMResponse.Choice(0, message, finishReason), usage, finishReason);
    }
}
