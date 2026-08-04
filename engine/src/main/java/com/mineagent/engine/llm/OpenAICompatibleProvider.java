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
import java.util.concurrent.*;

/**
 * OpenAI-compatible provider - supports OpenAI, DeepSeek, Qwen, GLM,
 * Moonshot/Kimi, Grok, and any provider that uses the same API format.
 *
 * <p>All these providers share the same /v1/chat/completions endpoint format,
 * differing only in base URL, auth header, and model names.
 */
public class OpenAICompatibleProvider implements LLMProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String providerId;
    private final String displayName;
    private final String defaultBaseUrl;
    private final List<String> defaultModels;
    private final Set<String> modelPrefixes;

    private OpenAICompatibleProvider(String providerId, String displayName,
                                     String defaultBaseUrl, List<String> defaultModels,
                                     Set<String> modelPrefixes) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModels = List.copyOf(defaultModels);
        this.modelPrefixes = Set.copyOf(modelPrefixes);
    }

    @Override
    public String providerId() { return providerId; }

    @Override
    public String displayName() { return displayName; }

    @Override
    public String defaultBaseUrl() { return defaultBaseUrl; }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return false;
        if (defaultModels.contains(model)) return true;
        for (String prefix : modelPrefixes) {
            if (model.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public List<String> defaultModels() { return defaultModels; }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                 List<ChatMessage> messages,
                                 List<Map<String, Object>> tools,
                                 double temperature, int maxTokens,
                                 String reasoningEffort) {
        ProviderSupport.validateRequest(
                providerId, apiKey, model, messages, temperature, maxTokens);
        String resolvedBaseUrl = ProviderSupport.validatedBaseUrl(
                baseUrl, defaultBaseUrl, providerId);
        try {
            JsonObject body = buildRequestBody(model, messages, tools, temperature, maxTokens, false);
            // Inject thinking effort parameters per provider/model spec.
            // Per official docs as of 2026-08:
            //   - When reasoningEffort is null/blank, NO parameter is injected
            //     (the API uses its own default — never mandatory).
            //   - For models that don't support any thinking parameter
            //     (GPT-4o, GPT-4.1, DeepSeek V3, Grok 4.0, etc.), the field
            //     is silently ignored.
            injectThinkingEffort(body, model, reasoningEffort);
            String url = ProviderSupport.endpoint(
                    resolvedBaseUrl, "/v1/chat/completions");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Preserve the status code so AgentLoop retries transient
                // failures without retrying bad credentials or bad requests.
                throw LLMProviderException.http(providerId,
                        response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            }

            try {
                return parseResponse(response.body());
            } catch (RuntimeException e) {
                // Gateways can return a truncated/empty body with a 2xx status.
                // Retrying that response is safe; treating it as permanent made
                // the companion stop after a single transient proxy failure.
                throw ProviderSupport.malformedResponse(providerId, response.body(), e);
            }
        } catch (java.io.IOException e) {
            // HttpTimeoutException is an IOException and is safe to retry.
            throw LLMProviderException.transport(providerId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException(providerId + " API call interrupted",
                    null, false, e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // ── Thinking effort injection ────────────────────────────────

    /**
     * Inject thinking-effort parameters into the request body per the
     * official docs of each model family. Key design:
     * <ul>
     *   <li>Empty/null effort → inject nothing (API default applies)</li>
     *   <li>"off" → disable thinking where possible; for models that
     *       cannot disable thinking, map to the lowest supported level</li>
     *   <li>Unsupported models (GPT-4o, Grok 4.0, Qwen2.5, etc.) →
     *       silently skip injection</li>
     * </ul>
     */
    private void injectThinkingEffort(JsonObject body, String model, String effort) {
        if (effort == null || effort.isBlank()) return;
        if (model == null || model.isBlank()) return;
        String e = effort.toLowerCase();
        String m = model.toLowerCase();
        String lvl = "off".equals(e) ? null : e; // null means "disable"

        switch (providerId) {
            case "openai" -> injectOpenAI(body, m, e, lvl);
            case "deepseek" -> injectDeepSeek(body, m, e, lvl);
            case "qwen" -> injectQwen(body, m, e, lvl);
            case "glm" -> injectGLM(body, m, e, lvl);
            case "moonshot" -> injectKimi(body, m, e, lvl);
            case "grok" -> injectGrok(body, m, e, lvl);
            case "minimax" -> injectMiniMax(body, m, e, lvl);
            default -> {
                // Unknown provider — fall back to plain reasoning_effort
                if (lvl != null) {
                    body.addProperty("reasoning_effort", lvl);
                }
            }
        }
    }

    // ── OpenAI ──
    // Per official docs (2026-08-01):
    //   GPT-5.6 (sol/terra/luna): none/low/medium/high/xhigh/max, default medium
    //   GPT-5.5/5.4/5.2: none/low/medium/high/xhigh, default medium
    //   GPT-5.1: none/low/medium/high, default medium
    //   GPT-5/5-mini/5-nano: minimal/low/medium/high, default medium (NO none — cannot disable)
    //   o3/o3-mini/o3-pro: low/medium/high, default medium (cannot disable)
    //   o4-mini: low/medium/high, default low (cannot disable)
    //   GPT-4.1/4o/4/3.5: NOT supported
    private void injectOpenAI(JsonObject body, String m, String e, String lvl) {
        // GPT-5.6 — full 6 levels including max
        if (m.startsWith("gpt-5.6")) {
            if (lvl == null) {
                body.addProperty("reasoning_effort", "none");
            } else {
                body.addProperty("reasoning_effort", lvl);
            }
            return;
        }
        // GPT-5.5 / 5.4 / 5.2 — none/low/medium/high/xhigh (no max, no minimal)
        if (m.startsWith("gpt-5.5") || m.startsWith("gpt-5.4") || m.startsWith("gpt-5.2")) {
            if (lvl == null) {
                body.addProperty("reasoning_effort", "none");
            } else {
                String mapped = switch (lvl) {
                    case "max" -> "xhigh"; // no max → xhigh
                    case "minimal" -> "low"; // no minimal → low
                    default -> lvl;
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // GPT-5.1 — none/low/medium/high
        if (m.startsWith("gpt-5.1")) {
            if (lvl == null) {
                body.addProperty("reasoning_effort", "none");
            } else {
                String mapped = switch (lvl) {
                    case "xhigh", "max" -> "high"; // no xhigh/max → high
                    case "minimal" -> "low";
                    default -> lvl;
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // GPT-5 / 5-mini / 5-nano — minimal/low/medium/high, CANNOT disable (no none)
        if (m.startsWith("gpt-5")) {
            if (lvl != null) {
                String mapped = switch (lvl) {
                    case "xhigh", "max" -> "high";
                    default -> lvl; // minimal, low, medium, high pass through
                };
                body.addProperty("reasoning_effort", mapped);
            }
            // if lvl == null (off), cannot disable — skip injection (API default applies)
            return;
        }
        // o4-mini — low/medium/high, cannot disable
        if (m.startsWith("o4-")) {
            if (lvl != null) {
                String mapped = switch (lvl) {
                    case "minimal" -> "low";
                    case "xhigh", "max" -> "high";
                    default -> lvl; // low, medium, high
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // o3 / o3-mini / o3-pro — low/medium/high, cannot disable
        if (m.startsWith("o3")) {
            if (lvl != null) {
                String mapped = switch (lvl) {
                    case "minimal" -> "low";
                    case "xhigh", "max" -> "high";
                    default -> lvl; // low, medium, high
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // GPT-4.1 / 4o / 4 / 3.5: NOT supported — skip
    }

    // ── DeepSeek ──
    // Per official docs (2026-08-01):
    //   V4-Pro/Flash: thinking.type = enabled/disabled (on/off)
    //                 reasoning_effort = high|max (low/medium→high, xhigh→max)
    //   off → thinking.type=disabled (NOT reasoning_effort=none)
    //   minimal → thinking.type=disabled
    // V3.2 (Alibaba Cloud): enable_thinking + reasoning_effort (low/medium/high/xhigh/max)
    // V3/chat/reasoner: NOT supported
    private void injectDeepSeek(JsonObject body, String m, String e, String lvl) {
        // V4 — use thinking.type for on/off, reasoning_effort for intensity
        if (m.startsWith("deepseek-v4")) {
            if (lvl == null) {
                // off / minimal → disable thinking via thinking.type
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled");
                body.add("thinking", thinking);
            } else {
                // Enable thinking + set effort (only high/max are real values)
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                body.add("thinking", thinking);
                String mapped = switch (lvl) {
                    case "low", "medium" -> "high";
                    case "xhigh" -> "max";
                    default -> lvl; // high, max pass through
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // V3.2 / V3.1 (Alibaba Cloud) — enable_thinking + reasoning_effort
        if (m.startsWith("deepseek-v3.2") || m.startsWith("deepseek-v3.1")) {
            if (lvl == null) {
                body.addProperty("enable_thinking", false);
            } else {
                body.addProperty("enable_thinking", true);
                body.addProperty("reasoning_effort", lvl);
            }
            return;
        }
        // deepseek-chat / V3 / reasoner: NOT supported — skip
    }

    // ── Qwen ──
    // Per official docs (2026-08-01):
    //   Qwen3 hybrid: enable_thinking (bool) + thinking_budget (1-81920)
    //     off → enable_thinking=false
    //     low/medium/high/xhigh/max → enable_thinking=true + thinking_budget
    //   Qwen3-Instruct-2507: non-thinking only — skip
    //   Qwen3-Thinking-2507 / QwQ: thinking-only — cannot disable, set budget only
    //   Qwen2.5 (qwen-max/plus/turbo): NOT supported
    private void injectQwen(JsonObject body, String m, String e, String lvl) {
        boolean isThinkingOnly =
                m.contains("thinking-2507") || m.startsWith("qwq");
        boolean isInstructOnly = m.contains("instruct-2507");
        boolean isHybrid =
                m.startsWith("qwen3.8") || m.startsWith("qwen3.7")
                        || m.startsWith("qwen3.6") || m.startsWith("qwen3.5")
                        || m.startsWith("qwen3-") || m.startsWith("qwen3");

        if (isInstructOnly) return; // cannot enable thinking
        if (!isHybrid && !isThinkingOnly) return; // Qwen2.5 — not supported

        if (lvl == null) {
            if (isThinkingOnly) {
                // cannot disable — set a low budget instead
                body.addProperty("enable_thinking", true);
                body.addProperty("thinking_budget", 512);
            } else {
                body.addProperty("enable_thinking", false);
            }
        } else {
            body.addProperty("enable_thinking", true);
            // Token budgets per level (max 81920 per official docs)
            int budget = switch (lvl) {
                case "minimal" -> 256;
                case "low" -> 1024;
                case "medium" -> 4096;
                case "high" -> 16384;
                case "xhigh" -> 40960;
                case "max" -> 81920;
                default -> 4096;
            };
            body.addProperty("thinking_budget", budget);
        }
    }

    // ── GLM ──
    // Per official docs (2026-08-01):
    //   GLM-5.2: thinking.type (enabled/disabled) + reasoning_effort
    //     reasoning_effort: max/xhigh(→max)/high/medium(→high)/low(→high)
    //     minimal/none → thinking.type=disabled (per official recommendation)
    //   GLM-5.1/5/5-Turbo/5V-Turbo/4.7/4.6/4.5: thinking.type only (no effort levels)
    //   GLM-4 and older: NOT supported
    private void injectGLM(JsonObject body, String m, String e, String lvl) {
        boolean supportsEffort = m.startsWith("glm-5.2");
        boolean supportsThinking =
                m.startsWith("glm-5") || m.startsWith("glm-4.7")
                        || m.startsWith("glm-4.6") || m.startsWith("glm-4.5");
        if (!supportsThinking) return;

        JsonObject thinking = new JsonObject();
        if (lvl == null) {
            // off → disable thinking
            thinking.addProperty("type", "disabled");
        } else if (supportsEffort && (lvl.equals("minimal") || lvl.equals("none"))) {
            // minimal/none → disable thinking (official recommendation)
            thinking.addProperty("type", "disabled");
        } else {
            // Enable thinking
            thinking.addProperty("type", "enabled");
            if (supportsEffort) {
                // Map effort levels per official docs
                String mapped = switch (lvl) {
                    case "low", "medium" -> "high";
                    case "xhigh" -> "max";
                    default -> lvl; // high, max pass through
                };
                body.addProperty("reasoning_effort", mapped);
            }
        }
        body.add("thinking", thinking);
    }

    // ── Kimi ──
    // Per official docs (2026-08-01):
    //   K3: reasoning_effort = low/high/max, default max, CANNOT disable (always thinking)
    //     off → low (closest to disabled)
    //   K2.6/K2.5: thinking.type = enabled/disabled, CAN disable
    //     off → thinking.type=disabled
    //   K2.7-code / K2-thinking: always thinking, CANNOT disable
    //     off → thinking.type=enabled (cannot send disabled, returns 400)
    //   moonshot-v1: NOT supported
    private void injectKimi(JsonObject body, String m, String e, String lvl) {
        // K3 — uses top-level reasoning_effort
        if (m.startsWith("kimi-k3")) {
            if (lvl == null) {
                // off → cannot disable, use lowest level
                body.addProperty("reasoning_effort", "low");
            } else {
                // K3 only supports low/high/max — map others
                String mapped = switch (lvl) {
                    case "minimal" -> "low";
                    case "medium" -> "high";
                    case "xhigh" -> "max";
                    default -> lvl; // low, high, max pass through
                };
                body.addProperty("reasoning_effort", mapped);
            }
            return;
        }
        // K2.7-code — always thinking, CANNOT disable (disabled returns 400)
        if (m.startsWith("kimi-k2.7")) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            body.add("thinking", thinking);
            return;
        }
        // K2-thinking — forced thinking, no params needed
        if (m.startsWith("kimi-k2-thinking")) {
            // This model always thinks — no thinking params needed
            return;
        }
        // K2.6 / K2.5 — thinking.type = enabled/disabled, CAN disable
        if (m.startsWith("kimi-k2.6") || m.startsWith("kimi-k2.5")) {
            JsonObject thinking = new JsonObject();
            if (lvl == null) {
                // off → disable thinking
                thinking.addProperty("type", "disabled");
            } else {
                thinking.addProperty("type", "enabled");
            }
            body.add("thinking", thinking);
            return;
        }
        // moonshot-v1-*: no thinking support — skip
    }

    // ── Grok ──
    // Per official docs (2026-08-01):
    //   Grok 4.5: reasoning_effort = low/medium/high, default high, CANNOT disable
    //   Grok 4.3: reasoning_effort = low/medium/high, default medium, CANNOT disable
    //   off → low (cannot disable, use lowest level)
    //   minimal → low, xhigh/max → high
    //   Grok 4.0 / 3.x: NOT supported (returns 400) — skip
    private void injectGrok(JsonObject body, String m, String e, String lvl) {
        boolean supported =
                m.startsWith("grok-4.5") || m.startsWith("grok-4.3")
                        || m.startsWith("grok-4.20") || m.startsWith("grok-4.1")
                        || m.startsWith("grok-build");
        if (!supported) return;

        if (lvl == null) {
            // off → cannot disable, use lowest level (low)
            body.addProperty("reasoning_effort", "low");
        } else {
            // Grok 4.3+ only supports low/medium/high — map higher/lower levels
            String mapped = switch (lvl) {
                case "minimal" -> "low";
                case "xhigh", "max" -> "high";
                default -> lvl; // low, medium, high pass through
            };
            body.addProperty("reasoning_effort", mapped);
        }
    }

    // ── MiniMax ──
    // M3: thinking.type = adaptive|disabled
    //   off → disabled
    //   any level → adaptive (no fine-grained effort control)
    // M2.x: thinking always on, cannot disable — set adaptive to be explicit
    private void injectMiniMax(JsonObject body, String m, String e, String lvl) {
        boolean isM3 = m.startsWith("minimax-m3");
        boolean isM2 = m.startsWith("minimax-m2") || m.startsWith("m2-");
        if (!isM3 && !isM2) return;

        JsonObject thinking = new JsonObject();
        if (lvl == null) {
            if (isM3) {
                thinking.addProperty("type", "disabled");
            } else {
                // M2.x cannot disable — keep adaptive (default)
                thinking.addProperty("type", "adaptive");
            }
        } else {
            thinking.addProperty("type", "adaptive");
        }
        body.add("thinking", thinking);
    }

    // ── Request building ───────────────────────────────────────────

    private JsonObject buildRequestBody(String model, List<ChatMessage> messages,
                                         List<Map<String, Object>> tools,
                                         double temperature, int maxTokens,
                                         boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        // Messages
        JsonArray msgsArr = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());

            // Content handling per role:
            //   - tool messages: content MUST be present (some APIs reject null)
            //   - assistant with tool_calls: OpenAI spec allows null content,
            //     BUT many relay/proxy sites (OpenRouter, 国内中转站) require a
            //     string. Set to "" for maximum compatibility across all
            //     OpenAI-compatible providers (DeepSeek/Qwen/GLM/Kimi/Grok/MiniMax).
            //   - assistant without tool_calls: must have content
            //   - user/system: must have content (shouldn't be null, but defensive)
            if ("tool".equals(msg.role())) {
                msgObj.addProperty("content",
                        msg.content() != null ? msg.content() : "");
            } else if (msg.content() != null) {
                msgObj.addProperty("content", msg.content());
            } else {
                // null content on non-tool message — use empty string
                msgObj.addProperty("content", "");
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                JsonArray tcArr = new JsonArray();
                for (var tc : msg.toolCalls()) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.id());
                    tcObj.addProperty("type", "function");
                    JsonObject fnObj = new JsonObject();
                    fnObj.addProperty("name", tc.name());
                    // Robust arguments: ensure it's a valid JSON string.
                    // Some upstream relays reject non-JSON arguments; if the
                    // caller passed a non-JSON string, wrap it as a JSON
                    // string literal so the API accepts the request.
                    String args = tc.arguments();
                    if (args == null || args.isBlank()) {
                        args = "{}";
                    } else {
                        args = ensureJsonArguments(args);
                    }
                    fnObj.addProperty("arguments", args);
                    tcObj.add("function", fnObj);
                    tcArr.add(tcObj);
                }
                msgObj.add("tool_calls", tcArr);
            }
            if (msg.toolCallId() != null) {
                msgObj.addProperty("tool_call_id", msg.toolCallId());
            }
            msgsArr.add(msgObj);
        }
        body.add("messages", msgsArr);

        // Tools
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (Map<String, Object> tool : tools) {
                toolsArr.add(GSON.toJsonTree(tool));
            }
            body.add("tools", toolsArr);
        }

        // o-series reasoning models (o3, o4-mini, o3-pro, ...) have special
        // constraints per the OpenAI API spec:
        //   1. They do NOT accept the "temperature" parameter (400 error).
        //   2. They use "max_completion_tokens" instead of "max_tokens"
        //      (the legacy field is rejected with 400).
        // Detection mirrors injectOpenAI(): "o3" / "o4-" prefixes on the
        // lower-cased model name. We use the raw prefix check here for
        // consistency with the rest of this class.
        String normalizedModel = model == null ? "" : model.toLowerCase(Locale.ROOT);
        boolean isOSeries = normalizedModel.startsWith("o3")
                || normalizedModel.startsWith("o4");
        if (!isOSeries) {
            body.addProperty("temperature", temperature);
            body.addProperty("max_tokens", maxTokens);
        } else {
            body.addProperty("max_completion_tokens", maxTokens);
        }
        if (stream) {
            body.addProperty("stream", true);
        }

        // ── Prompt cache affinity ──
        // OpenAI's prompt caching requires a stable prefix across requests
        // within the same session. The prompt_cache_key parameter (supported
        // by OpenAI and most OpenAI-compatible relays since 2025) tells the
        // server to group requests by this key for cache reuse, yielding
        // up to 80% latency reduction and 90% input token cost reduction.
        //
        // We derive a stable key from the provider+model pair. This is
        // per-AI-player (each AgentLoop has its own provider instance via
        // the factory methods above), so two different AI players won't
        // pollute each other's caches. Unknown relays that don't support
        // this field will simply ignore it (extra body fields are
        // permitted by the OpenAI spec).
        // prompt_cache_key is an OpenAI extension, not part of the shared
        // chat-completions schema. Strict DeepSeek/Qwen/GLM relays reject
        // unknown fields with 400, so only send it to the OpenAI provider.
        if ("openai".equals(providerId)) {
            body.addProperty("prompt_cache_key", providerId + ":" + model);
        }

        return body;
    }

    /**
     * Ensure a tool-call arguments string is valid JSON.
     *
     * <p>LLMs sometimes emit malformed arguments — bare words, unquoted
     * keys, trailing commas, or even natural language. This method:
     * <ol>
     *   <li>If the string already parses as JSON, return it unchanged.</li>
     *   <li>Otherwise, try lenient parsing (strip trailing commas, etc.).</li>
     *   <li>If all parsing fails, wrap the raw string as a JSON string
     *       literal under the key {@code "raw"} so the tool at least
     *       receives SOMETHING and the API accepts the request.</li>
     * </ol>
     *
     * <p>This prevents the entire request from being rejected by the API
     * because of a single malformed tool_call argument — a common failure
     * mode with smaller/cheaper models on relay sites.
     */
    private static String ensureJsonArguments(String args) {
        // Fast path: already valid JSON
        try {
            JsonParser.parseString(args);
            return args;
        } catch (JsonSyntaxException ignored) {
            // Fall through to lenient parsing
        }
        // Lenient: try removing trailing commas before } or ]
        String cleaned = args.replaceAll(",\\s*([}\\]])", "$1");
        try {
            JsonParser.parseString(cleaned);
            return cleaned;
        } catch (JsonSyntaxException ignored) {
            // Fall through to wrapping
        }
        // Last resort: wrap as a string literal
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("raw", args);
        return GSON.toJson(wrapper);
    }

    // ── Response parsing ───────────────────────────────────────────

    private LLMResponse parseResponse(String json) {
        // Robust JSON extraction: some relays prepend BOM, whitespace, or
        // status text before the JSON body. Find the first '{' and try to
        // parse from there. If the whole thing fails, try extracting a
        // code-fenced JSON block (```json ... ```).
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            // Try extracting the outermost JSON object
            String extracted = extractJsonObject(json);
            if (extracted == null) {
                throw new RuntimeException(providerId
                        + " API returned non-JSON response (lenient parse failed). "
                        + "Response: " + truncate(json, 500), e);
            }
            root = JsonParser.parseString(extracted).getAsJsonObject();
        }
        String id = root.has("id") ? root.get("id").getAsString() : "";
        String model = root.has("model") ? root.get("model").getAsString() : "";

        // Validate choices array — some upstream relays return empty/missing
        // choices when content moderation, quota exhaustion, or upstream
        // errors occur. Without this guard we'd NPE or throw
        // IndexOutOfBoundsException, masking the real cause.
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            throw new RuntimeException(providerId
                    + " API returned no 'choices' field (likely content moderation, "
                    + "quota exhaustion, or upstream error). Response: " + truncate(json, 500));
        }
        JsonArray choicesArr = root.getAsJsonArray("choices");
        if (choicesArr.isEmpty()) {
            throw new RuntimeException(providerId
                    + " API returned an empty 'choices' array (likely content "
                    + "moderation, quota exhaustion, or upstream error). Response: "
                    + truncate(json, 500));
        }
        if (!choicesArr.get(0).isJsonObject()) {
            throw new RuntimeException(providerId + " API returned a non-object choice");
        }
        JsonObject choiceObj = choicesArr.get(0).getAsJsonObject();
        String finishReason = choiceObj.has("finish_reason")
                && !choiceObj.get("finish_reason").isJsonNull()
                ? choiceObj.get("finish_reason").getAsString() : "";

        if (!choiceObj.has("message") || !choiceObj.get("message").isJsonObject()) {
            throw new RuntimeException(providerId + " API choice has no message object");
        }
        JsonObject messageObj = choiceObj.getAsJsonObject("message");
        String role = messageObj.has("role") ? messageObj.get("role").getAsString() : "assistant";
        String content = messageObj.has("content") && !messageObj.get("content").isJsonNull()
                ? (messageObj.get("content").isJsonPrimitive()
                    ? messageObj.get("content").getAsString()
                    : GSON.toJson(messageObj.get("content"))) : null;

        // Parse tool calls — with robust arguments handling
        List<ChatMessage.ToolCallRef> toolCalls = null;
        if (messageObj.has("tool_calls") && messageObj.get("tool_calls").isJsonArray()) {
            toolCalls = new ArrayList<>();
            for (var tcElem : messageObj.getAsJsonArray("tool_calls")) {
                try {
                    JsonObject tcObj = tcElem.getAsJsonObject();
                    // Some relays return null/missing id — generate one
                    String tcId = tcObj.has("id") && !tcObj.get("id").isJsonNull()
                            ? tcObj.get("id").getAsString()
                            : "call_" + System.nanoTime();
                    JsonObject fnObj = tcObj.has("function") && tcObj.get("function").isJsonObject()
                            ? tcObj.getAsJsonObject("function")
                            : new JsonObject();
                    String tcName = fnObj.has("name") && !fnObj.get("name").isJsonNull()
                            ? fnObj.get("name").getAsString() : "unknown";
                    // Arguments may be a string (per spec) or an object
                    // (some non-compliant relays). Normalize to a string.
                    String tcArgs = "{}";
                    if (fnObj.has("arguments") && !fnObj.get("arguments").isJsonNull()) {
                        var argsElem = fnObj.get("arguments");
                        if (argsElem.isJsonPrimitive()) {
                            tcArgs = argsElem.getAsString();
                            // Validate; if malformed, wrap it
                            tcArgs = ensureJsonArguments(tcArgs);
                        } else {
                            // Object/array — serialize to string
                            tcArgs = GSON.toJson(argsElem);
                        }
                    }
                    toolCalls.add(new ChatMessage.ToolCallRef(tcId, tcName, tcArgs));
                } catch (Exception tcEx) {
                    // Skip a single malformed tool_call rather than failing
                    // the entire response. Log so it's debuggable.
                    System.err.println("[MineAgent] Skipping malformed tool_call: "
                            + tcEx.getMessage());
                }
            }
            // If all tool_calls failed to parse, treat as no tool calls
            if (toolCalls.isEmpty()) toolCalls = null;
        }

        ChatMessage message = new ChatMessage(role, content, toolCalls, null);

        // Usage
        LLMResponse.Usage usage = null;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usageObj = root.getAsJsonObject("usage");
            usage = new LLMResponse.Usage(
                    usageObj.has("prompt_tokens") ? usageObj.get("prompt_tokens").getAsInt() : 0,
                    usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0,
                    usageObj.has("total_tokens") ? usageObj.get("total_tokens").getAsInt() : 0
            );
        }

        return new LLMResponse(id, model,
                new LLMResponse.Choice(0, message, finishReason), usage, finishReason);
    }

    /**
     * Extract the outermost JSON object from a string that may contain
     * leading/trailing non-JSON text (BOM, status text, code fences).
     *
     * <p>Strategy: find the first '{', then scan forward tracking brace
     * depth (respecting string literals and escape sequences). When depth
     * returns to 0, that's the end of the outermost object.
     *
     * @return the extracted JSON substring, or {@code null} if no balanced
     *         object was found.
     */
    private static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced
    }

    /** Truncate a string to {@code max} chars, appending "..." if truncated. */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    // ── Factory methods for each provider ──────────────────────────

    public static OpenAICompatibleProvider openai() {
        return new OpenAICompatibleProvider("openai", "OpenAI",
                "https://api.openai.com",
                List.of(
                        // GPT-5.6 系列 (2026年7月9日发布，旗舰家族)
                        "gpt-5.6-sol", "gpt-5.6-sol-pro",
                        "gpt-5.6-terra", "gpt-5.6-terra-pro",
                        "gpt-5.6-luna", "gpt-5.6-luna-pro",
                        // GPT-5.5 系列
                        "gpt-5.5", "gpt-5.5-instant", "gpt-5.5-instant-mini",
                        // GPT-5.4 系列
                        "gpt-5.4", "gpt-5.4-mini",
                        // GPT-5 系列
                        "gpt-5", "gpt-5-instant",
                        // GPT-4.1 系列
                        "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                        // GPT-4o 系列
                        "gpt-4o", "gpt-4o-mini",
                        // o-series 推理模型
                        "o3", "o3-mini", "o3-pro",
                        "o4-mini"
                ),
                Set.of("gpt-", "o3", "o4-"));
    }

    public static OpenAICompatibleProvider deepseek() {
        return new OpenAICompatibleProvider("deepseek", "DeepSeek",
                "https://api.deepseek.com",
                List.of(
                        // V4 系列 (V4-Flash正式版2026年7月31日上线，Agent能力大幅增强)
                        "deepseek-v4-pro", "deepseek-v4-flash",
                        // V3.2 系列 (即将下线，2026-10-10)
                        "deepseek-chat", "deepseek-reasoner",
                        "deepseek-v3", "deepseek-v3.2"
                ),
                Set.of("deepseek-"));
    }

    public static OpenAICompatibleProvider qwen() {
        return new OpenAICompatibleProvider("qwen", "Qwen (Alibaba)",
                "https://dashscope.aliyuncs.com/compatible-mode",
                List.of(
                        // Qwen3.8 系列 (2026年7月19日发布，2.4T参数，1M上下文)
                        "qwen3.8-max",
                        // Qwen3.7 系列 (2026年5月，Agent旗舰)
                        "qwen3.7-max", "qwen3.7-plus",
                        // Qwen3.6 系列
                        "qwen3.6-plus", "qwen3.6-flash", "qwen3.6-27b",
                        "qwen3.6-35b-a3b",
                        // Qwen3.5 系列
                        "qwen3.5-plus", "qwen3.5-flash",
                        "qwen3.5-122b-a10b", "qwen3.5-27b",
                        // Qwen3 系列
                        "qwen3-235b-a22b", "qwen3-30b-a3b",
                        // Qwen2.5 系列
                        "qwen-max", "qwen-plus", "qwen-turbo", "qwen-long",
                        // QwQ 推理系列
                        "qwq-plus", "qwq-32b"
                ),
                Set.of("qwen", "qwq-"));
    }

    public static OpenAICompatibleProvider glm() {
        return new OpenAICompatibleProvider("glm", "GLM (Zhipu)",
                "https://open.bigmodel.cn/api/paas",
                List.of(
                        // GLM-5 系列 (2026年最新旗舰)
                        "glm-5.2", "glm-5.1", "glm-5", "glm-5-turbo",
                        // GLM-4.7 系列
                        "glm-4.7", "glm-4.7-flash", "glm-4.7-flashx",
                        // GLM-4.6 系列
                        "glm-4.6",
                        // GLM-4.5 系列
                        "glm-4.5-air", "glm-4.5-airx", "glm-4.5-flash",
                        // GLM-4 系列
                        "glm-4-plus", "glm-4-flash", "glm-4-long",
                        "glm-4-air"
                ),
                Set.of("glm-"));
    }

    public static OpenAICompatibleProvider moonshot() {
        return new OpenAICompatibleProvider("moonshot", "Moonshot (Kimi)",
                "https://api.moonshot.cn",
                List.of(
                        // Kimi K3 系列 (2026年7月最新，2.8T参数，100万上下文)
                        "kimi-k3",
                        // Kimi K2.7 系列
                        "kimi-k2.7-code", "kimi-k2.7-code-highspeed",
                        // Kimi K2.6 系列
                        "kimi-k2.6",
                        // Kimi K2.5 系列
                        "kimi-k2.5",
                        // Kimi K2 Thinking 系列
                        "kimi-k2-thinking", "kimi-k2-thinking-turbo",
                        // Moonshot V1 系列 (即将下线 2026-08-31)
                        "moonshot-v1-8k", "moonshot-v1-32k",
                        "moonshot-v1-128k", "moonshot-v1-auto"
                ),
                Set.of("kimi-", "moonshot-"));
    }

    public static OpenAICompatibleProvider grok() {
        return new OpenAICompatibleProvider("grok", "Grok (xAI)",
                "https://api.x.ai",
                List.of(
                        // Grok 4.5 (2026年7月8日发布，1.5T参数，500K上下文)
                        "grok-4.5",
                        // Grok 4.3 (1M上下文)
                        "grok-4.3",
                        // Grok 4.20 系列 (2M上下文)
                        "grok-4.20", "grok-4.20-reasoning",
                        // Grok 4.1 系列
                        "grok-4.1", "grok-4.1-fast",
                        // Grok 4 系列
                        "grok-4", "grok-4-fast",
                        // Grok Build 编程系列
                        "grok-build-0.1",
                        // Grok 3 系列
                        "grok-3", "grok-3-mini", "grok-3-fast"
                ),
                Set.of("grok-"));
    }

    public static OpenAICompatibleProvider minimax() {
        return new OpenAICompatibleProvider("minimax", "MiniMax",
                "https://api.minimaxi.com",
                List.of(
                        // MiniMax-M3 (2026年6月最新，1M上下文，原生多模态)
                        "MiniMax-M3",
                        // MiniMax-M2.7 系列
                        "MiniMax-M2.7", "MiniMax-M2.7-highspeed",
                        // MiniMax-M2.5 系列
                        "MiniMax-M2.5", "MiniMax-M2.5-highspeed",
                        // MiniMax-M2.1 系列
                        "MiniMax-M2.1", "MiniMax-M2.1-highspeed",
                        // MiniMax-M2 系列
                        "MiniMax-M2",
                        // MiniMax 对话角色模型
                        "M2-her"
                ),
                Set.of("MiniMax-", "M2-"));
    }
}
