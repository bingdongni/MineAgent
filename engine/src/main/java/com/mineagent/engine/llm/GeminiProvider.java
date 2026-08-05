package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Google Gemini provider — uses the Gemini REST API format.
 * Gemini has its own API structure but supports OpenAI-compatible
 * function calling.
 */
public class GeminiProvider implements LLMProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String providerId() { return "gemini"; }

    @Override
    public String displayName() { return "Google Gemini"; }

    @Override
    public String defaultBaseUrl() { return "https://generativelanguage.googleapis.com"; }

    @Override
    public boolean supportsModel(String model) {
        return model != null && (model.startsWith("gemini-") || model.startsWith("models/gemini-"));
    }

    @Override
    public List<String> defaultModels() {
        return List.of(
                // Gemini 3.6 Flash (2026年7月21日最新发布，更高效token利用率)
                "gemini-3.6-flash",
                // Gemini 3.5 Flash / Flash-Lite (2026年5月，1M上下文)
                "gemini-3.5-flash", "gemini-3.5-flash-lite",
                // Gemini 3.1 系列
                "gemini-3.1-pro", "gemini-3.1-flash",
                "gemini-3.1-flash-lite",
                // Gemini 3 系列
                "gemini-3-flash", "gemini-3-pro",
                // Gemini 2.5 系列
                "gemini-2.5-pro", "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                // Gemini 2.0 系列
                "gemini-2.0-flash", "gemini-2.0-flash-lite",
                // Gemini 1.5 系列
                "gemini-1.5-pro", "gemini-1.5-flash"
        );
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                 List<ChatMessage> messages,
                                 List<Map<String, Object>> tools,
                                 double temperature, int maxTokens,
                                 String reasoningEffort) {
        // Fail fast on null/blank model — otherwise model.startsWith() below
        // would throw NPE, masking the real configuration issue.
        ProviderSupport.validateRequest(
                "Gemini", apiKey, model, messages, temperature, maxTokens);
        String resolvedBaseUrl = ProviderSupport.validatedBaseUrl(
                baseUrl, defaultBaseUrl(), "Gemini");
        try {
            String modelPath = model.startsWith("models/") ? model : "models/" + model;
            // URL-encode the API key — keys containing &, =, +, /, or other
            // reserved characters would otherwise corrupt the query string
            // (e.g., a key with '&' splits into two params, a key with '='
            // breaks the key=value parsing). StandardCharsets.UTF_8 is the
            // recommended charset overload (no checked exception).
            String encodedKey = apiKey != null
                    ? URLEncoder.encode(apiKey, StandardCharsets.UTF_8) : "";
            String url = ProviderSupport.endpoint(resolvedBaseUrl,
                    "/v1beta/" + modelPath)
                    + ":generateContent?key=" + encodedKey;

            JsonObject body = new JsonObject();

            // Convert messages to Gemini format
            //
            // Gemini's functionResponse REQUIRES the function name, but our
            // ChatMessage.toolResult only stores the toolCallId. So we first
            // scan all messages to build a toolCallId → toolName map from
            // earlier assistant tool_call messages, then use it when emitting
            // functionResponse blocks. (Without this Gemini returns 400.)
            Map<String, String> toolCallIdToName = new HashMap<>();
            for (ChatMessage msg : messages) {
                if (msg.toolCalls() != null) {
                    for (var tc : msg.toolCalls()) {
                        if (tc.id() != null && tc.name() != null) {
                            toolCallIdToName.put(tc.id(), tc.name());
                        }
                    }
                }
            }

            JsonArray contents = new JsonArray();
            for (ChatMessage msg : messages) {
                if ("system".equals(msg.role())) continue; // system handled separately

                String role = translateRole(msg.role());
                JsonArray parts = new JsonArray();

                // Tool results — Gemini requires the function name here.
                // Look it up from the assistant tool_call that produced this id.
                // IMPORTANT: Tool messages must ONLY contain functionResponse,
                // not text parts, otherwise Gemini returns 400.
                // Also: Gemini's response field MUST be a JSON object.
                // If tool content is not valid JSON, wrap it in {"result": "..."}.
                if ("tool".equals(msg.role()) && msg.toolCallId() != null) {
                    String funcName = toolCallIdToName.get(msg.toolCallId());
                    if (funcName == null) {
                        System.err.println("[MineAgent] Skipping orphan Gemini tool result id="
                                + msg.toolCallId());
                        continue;
                    }
                    JsonObject responsePart = new JsonObject();
                    JsonObject funcResponse = new JsonObject();
                    funcResponse.addProperty("name", funcName);
                    String toolContent = msg.content() != null ? msg.content() : "{}";
                    JsonObject responseObj;
                    try {
                        JsonElement parsed = JsonParser.parseString(toolContent);
                        responseObj = parsed.isJsonObject() ? parsed.getAsJsonObject()
                                : wrapAsResultObject(toolContent);
                    } catch (JsonSyntaxException ex) {
                        // Tool returned non-JSON content (e.g., plain text error)
                        responseObj = wrapAsResultObject(toolContent);
                    }
                    funcResponse.add("response", responseObj);
                    responsePart.add("functionResponse", funcResponse);
                    parts.add(responsePart);
                } else {
                    // Regular text content (skip for tool messages)
                    if (msg.content() != null) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("text", msg.content());
                        parts.add(textPart);
                    }

                    // Tool calls in assistant messages
                    if (msg.toolCalls() != null) {
                        for (var tc : msg.toolCalls()) {
                            JsonObject funcPart = new JsonObject();
                            JsonObject funcCall = new JsonObject();
                            funcCall.addProperty("name", tc.name());
                            funcCall.add("args",
                                    ProviderSupport.toolArgumentsObject(tc.arguments()));
                            funcPart.add("functionCall", funcCall);
                            parts.add(funcPart);
                        }
                    }
                }

                // Skip messages with empty parts (Gemini rejects empty parts array)
                if (parts.isEmpty()) continue;

                appendContent(contents, role, parts);
            }
            body.add("contents", contents);

            // System instruction — merge ALL system messages into one (M9 fix)
            // IMPORTANT: skip null/empty system content — StringBuilder.append(null)
            // would otherwise append the literal string "null", corrupting the
            // system instruction sent to Gemini and confusing the model.
            // This mirrors the same defensive check used in AnthropicProvider.
            StringBuilder systemText = new StringBuilder();
            for (ChatMessage msg : messages) {
                if ("system".equals(msg.role())) {
                    if (msg.content() == null || msg.content().isEmpty()) continue;
                    if (systemText.length() > 0) systemText.append("\n\n");
                    systemText.append(msg.content());
                }
            }
            if (systemText.length() > 0) {
                JsonObject sysInstr = new JsonObject();
                JsonArray parts = new JsonArray();
                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", systemText.toString());
                parts.add(textPart);
                sysInstr.add("parts", parts);
                body.add("systemInstruction", sysInstr);
            }

            // Generation config
            JsonObject genConfig = new JsonObject();
            genConfig.addProperty("temperature", temperature);
            genConfig.addProperty("maxOutputTokens", maxTokens);
            body.add("generationConfig", genConfig);

            // Tool declarations
            if (tools != null && !tools.isEmpty()) {
                JsonArray toolDecls = new JsonArray();
                for (Map<String, Object> tool : tools) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>) tool.get("function");
                    if (function != null) {
                        JsonObject funcDecl = new JsonObject();
                        funcDecl.addProperty("name", (String) function.get("name"));
                        funcDecl.addProperty("description", (String) function.get("description"));
                        if (function.get("parameters") != null) {
                            funcDecl.add("parameters", GSON.toJsonTree(function.get("parameters")));
                        }
                        toolDecls.add(funcDecl);
                    }
                }
                JsonObject toolsObj = new JsonObject();
                toolsObj.add("functionDeclarations", toolDecls);
                JsonArray toolsArr = new JsonArray();
                toolsArr.add(toolsObj);
                body.add("tools", toolsArr);
            }

            // Inject thinking config per official Gemini docs (2026-08-01):
            //   - Gemini 3.x: thinkingConfig.thinkingLevel = minimal|low|medium|high
            //     * Pro: low/medium/high (NO minimal), default high
            //     * Flash/Flash-Lite: minimal/low/medium/high, default medium/minimal
            //     * CANNOT fully disable thinking (minimal still may think)
            //     * off → minimal (Flash) / low (Pro) — lowest possible
            //   - Gemini 2.5: thinkingConfig.thinkingBudget = integer
            //     * Pro: 128-32768, CANNOT disable (thinkingBudget=0 returns 400)
            //       off → 128 (minimum), high → 32768
            //     * Flash: 1-24576, CAN disable (0 = off)
            //       off → 0, high → 24576
            //     * Flash-Lite: default OFF
            //   - thinkingLevel and thinkingBudget CANNOT be set together (400)
            //   - When reasoningEffort is null/blank, inject nothing (default applies)
            if (reasoningEffort != null && !reasoningEffort.isBlank()
                    && model != null && !model.isBlank()) {
                String e = reasoningEffort.toLowerCase();
                String m = model.toLowerCase();
                JsonObject generationConfig = body.has("generationConfig")
                        ? body.getAsJsonObject("generationConfig") : new JsonObject();
                JsonObject thinkingConfig = new JsonObject();

                boolean isGemini3 = m.startsWith("gemini-3");
                boolean isGemini25FlashLite = m.startsWith("gemini-2.5-flash-lite");
                boolean isGemini25Flash = m.startsWith("gemini-2.5-flash")
                        && !isGemini25FlashLite;
                // Pro includes bare "gemini-2.5" and any 2.5 variant that isn't Flash
                boolean isGemini25Pro = m.startsWith("gemini-2.5-pro")
                        || (m.startsWith("gemini-2.5") && !m.startsWith("gemini-2.5-flash"));
                boolean isPro3 = m.startsWith("gemini-3.1-pro")
                        || m.startsWith("gemini-3-pro")
                        || m.startsWith("gemini-3.6-pro");

                if (isGemini3) {
                    // Gemini 3.x uses thinkingLevel
                    if ("off".equals(e)) {
                        // 3.x cannot fully disable — use lowest level
                        if (isPro3) {
                            thinkingConfig.addProperty("thinkingLevel", "low");
                        } else {
                            thinkingConfig.addProperty("thinkingLevel", "minimal");
                        }
                    } else {
                        String level = switch (e) {
                            case "minimal" -> isPro3 ? "low" : "minimal";
                            case "low" -> "low";
                            case "medium" -> "medium";
                            case "high", "xhigh", "max" -> "high";
                            default -> "medium";
                        };
                        thinkingConfig.addProperty("thinkingLevel", level);
                    }
                } else if (isGemini25Pro) {
                    // Gemini 2.5 Pro — CANNOT disable (0 returns 400)
                    // Range: 128-32768
                    if ("off".equals(e)) {
                        thinkingConfig.addProperty("thinkingBudget", 128); // minimum
                    } else {
                        int budget = switch (e) {
                            case "minimal" -> 128;
                            case "low" -> 1024;
                            case "medium" -> 8192;
                            case "high", "xhigh", "max" -> 32768; // Pro max
                            default -> 8192;
                        };
                        thinkingConfig.addProperty("thinkingBudget", budget);
                    }
                } else if (isGemini25Flash || isGemini25FlashLite) {
                    // Gemini 2.5 Flash / Flash-Lite — CAN disable (0)
                    // Range: 0-24576
                    if ("off".equals(e)) {
                        thinkingConfig.addProperty("thinkingBudget", 0);
                    } else {
                        int budget = switch (e) {
                            case "minimal" -> 128;
                            case "low" -> 1024;
                            case "medium" -> 8192;
                            case "high", "xhigh", "max" -> 24576; // Flash max
                            default -> 8192;
                        };
                        thinkingConfig.addProperty("thinkingBudget", budget);
                    }
                }
                // Older Gemini (1.5/2.0): no thinking config — skip

                if (thinkingConfig.size() > 0) {
                    generationConfig.add("thinkingConfig", thinkingConfig);
                    body.add("generationConfig", generationConfig);
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http("Gemini",
                        response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            }

            try {
                return parseGeminiResponse(response.body(), model);
            } catch (LLMProviderException e) {
                throw e;
            } catch (RuntimeException e) {
                throw ProviderSupport.malformedResponse("Gemini", response.body(), e);
            }
        } catch (java.io.IOException e) {
            // HttpTimeoutException is an IOException and is safe to retry.
            throw LLMProviderException.transport("Gemini", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMProviderException("Gemini API call interrupted",
                    null, false, e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private String translateRole(String role) {
        return switch (role) {
            case "assistant" -> "model";
            // generateContent only accepts user/model conversation roles.
            // functionResponse is a part within a user turn, not a role.
            case "tool" -> "user";
            default -> role;
        };
    }

    private static void appendContent(JsonArray contents, String role, JsonArray parts) {
        if (!contents.isEmpty()) {
            JsonObject previous = contents.get(contents.size() - 1).getAsJsonObject();
            if (role.equals(previous.get("role").getAsString())) {
                JsonArray previousParts = previous.getAsJsonArray("parts");
                for (JsonElement part : parts) {
                    previousParts.add(part);
                }
                return;
            }
        }
        JsonObject content = new JsonObject();
        content.addProperty("role", role);
        content.add("parts", parts);
        contents.add(content);
    }

    /**
     * Wrap a non-JSON or non-object tool result into a JSON object that
     * Gemini's functionResponse.response field can accept.
     * Gemini REQUIRES the response to be a JSON object — arrays, primitives,
     * and plain strings cause a 400 error.
     */
    private JsonObject wrapAsResultObject(String content) {
        JsonObject obj = new JsonObject();
        // Truncate extremely long content to avoid token overflow
        String trimmed = content.length() > 4000 ? content.substring(0, 4000) + "..." : content;
        obj.addProperty("result", trimmed);
        return obj;
    }

    private LLMResponse parseGeminiResponse(String json, String model) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String content = null;
        List<ChatMessage.ToolCallRef> toolCalls = new ArrayList<>();
        String finishReason = "stop";

        // Validate candidates array — Gemini returns no/empty candidates when
        // safety filters, content moderation, or upstream errors occur.
        // Without this guard we'd NPE or throw IndexOutOfBoundsException,
        // masking the real cause.
        if (!root.has("candidates") || !root.get("candidates").isJsonArray()) {
            if (root.has("promptFeedback") && root.get("promptFeedback").isJsonObject()) {
                JsonObject feedback = root.getAsJsonObject("promptFeedback");
                if (feedback.has("blockReason")
                        && !feedback.get("blockReason").isJsonNull()) {
                    throw new LLMProviderException(
                            "Gemini request blocked: " + feedback.get("blockReason").getAsString(),
                            null, false, null);
                }
            }
            throw new RuntimeException("Gemini API returned no 'candidates' field "
                    + "(likely safety filter, content moderation, or upstream "
                    + "error). Response: " + ProviderSupport.truncate(json, 500));
        }
        JsonArray candidatesArr = root.getAsJsonArray("candidates");
        if (candidatesArr.isEmpty()) {
            throw new RuntimeException("Gemini API returned an empty 'candidates' "
                    + "array (likely safety filter, content moderation, or "
                    + "upstream error). Response: " + ProviderSupport.truncate(json, 500));
        }
        {
            if (!candidatesArr.get(0).isJsonObject()) {
                throw new RuntimeException("Gemini API returned a non-object candidate");
            }
            JsonObject candidate = candidatesArr.get(0).getAsJsonObject();
            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                JsonObject contentObj = candidate.getAsJsonObject("content");
                JsonArray parts = contentObj.has("parts") && contentObj.get("parts").isJsonArray()
                        ? contentObj.getAsJsonArray("parts") : new JsonArray();
                StringBuilder textContent = new StringBuilder();

                for (var elem : parts) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject part = elem.getAsJsonObject();
                    if (part.has("text") && part.get("text").isJsonPrimitive()) {
                        if (textContent.length() > 0) textContent.append("\n");
                        textContent.append(part.get("text").getAsString());
                    }
                    if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                        JsonObject fc = part.getAsJsonObject("functionCall");
                        if (!fc.has("name") || !fc.get("name").isJsonPrimitive()) continue;
                        String name = fc.get("name").getAsString();
                        String args = fc.has("args") ? GSON.toJson(fc.get("args")) : "{}";
                        toolCalls.add(new ChatMessage.ToolCallRef(
                                "call_" + UUID.randomUUID().toString().substring(0, 8),
                                name, args));
                    }
                }
                content = textContent.length() > 0 ? textContent.toString() : null;
            }
            if (candidate.has("finishReason")) {
                finishReason = candidate.get("finishReason").getAsString();
            }
            if ((content == null || content.isBlank()) && toolCalls.isEmpty()
                    && isBlockedFinishReason(finishReason)) {
                throw new LLMProviderException(
                        "Gemini response blocked: " + finishReason,
                        null, false, null);
            }
        }

        if (!toolCalls.isEmpty()) finishReason = "tool_calls";
        ChatMessage message = new ChatMessage("assistant", content,
                toolCalls.isEmpty() ? null : toolCalls, null);

        LLMResponse.Usage usage = null;
        if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("usageMetadata");
            usage = new LLMResponse.Usage(
                    meta.has("promptTokenCount") ? meta.get("promptTokenCount").getAsInt() : 0,
                    meta.has("candidatesTokenCount") ? meta.get("candidatesTokenCount").getAsInt() : 0,
                    meta.has("totalTokenCount") ? meta.get("totalTokenCount").getAsInt() : 0,
                    meta.has("cachedContentTokenCount")
                            ? meta.get("cachedContentTokenCount").getAsInt() : 0,
                    0
            );
        }

        return new LLMResponse("", model,
                new LLMResponse.Choice(0, message, finishReason), usage, finishReason);
    }

    private static boolean isBlockedFinishReason(String reason) {
        return "SAFETY".equals(reason) || "RECITATION".equals(reason)
                || "BLOCKLIST".equals(reason) || "PROHIBITED_CONTENT".equals(reason)
                || "SPII".equals(reason);
    }
}
