package com.mineagent.engine.client.ui;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Optional connection shortcuts for the unified setup screen.
 *
 * <p>Presets only fill protocol, model, and URL fields. They are deliberately
 * not a model allow-list: users can edit both model and endpoint afterward,
 * and the three protocol adapters remain the actual compatibility boundary.
 */
public final class ProviderPresets {

    public static final Preset CUSTOM = new Preset(
            "custom", Component.translatable("screen.mineagent.preset.custom"),
            "openai-compatible", "", "");

    public static final List<Preset> ALL = List.of(
            CUSTOM,
            preset("deepseek", "DeepSeek", "openai-compatible",
                    "deepseek-chat", "https://api.deepseek.com"),
            preset("openai", "OpenAI", "openai-compatible",
                    "gpt-4.1-mini", "https://api.openai.com"),
            preset("anthropic", "Anthropic", "anthropic-compatible",
                    "claude-sonnet-4-20250514", "https://api.anthropic.com"),
            preset("gemini", "Google Gemini", "gemini-compatible",
                    "gemini-2.5-flash", "https://generativelanguage.googleapis.com"),
            preset("qwen", "Alibaba Qwen", "openai-compatible",
                    "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode"),
            preset("glm", "Zhipu GLM", "openai-compatible",
                    "glm-4.5", "https://open.bigmodel.cn/api/paas"),
            preset("moonshot", "Moonshot / Kimi", "openai-compatible",
                    "kimi-k2.5", "https://api.moonshot.cn"),
            preset("grok", "xAI Grok", "openai-compatible",
                    "grok-4", "https://api.x.ai"),
            preset("minimax", "MiniMax", "openai-compatible",
                    "MiniMax-M2.5", "https://api.minimaxi.com"),
            preset("openrouter", "OpenRouter", "openai-compatible",
                    "openai/gpt-4.1-mini", "https://openrouter.ai/api"),
            preset("mistral", "Mistral AI", "openai-compatible",
                    "mistral-small-latest", "https://api.mistral.ai"),
            preset("groq", "Groq", "openai-compatible",
                    "llama-3.3-70b-versatile", "https://api.groq.com/openai"),
            preset("together", "Together AI", "openai-compatible",
                    "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                    "https://api.together.xyz")
    );

    private ProviderPresets() {}

    /** Translate a persisted pre-v0.3.5 vendor id to its wire protocol. */
    public static String protocolForProvider(String providerId) {
        if (providerId == null) return "openai-compatible";
        String normalized = providerId.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "anthropic", "anthropic-compatible" -> "anthropic-compatible";
            case "gemini", "gemini-compatible" -> "gemini-compatible";
            case "openai", "deepseek", "qwen", "glm", "moonshot", "grok",
                    "minimax", "openai-compatible" -> "openai-compatible";
            // Preserve third-party adapter ids registered through
            // LLMProviderRegistry instead of silently changing their protocol.
            default -> normalized.isEmpty() ? "openai-compatible" : normalized;
        };
    }

    /** Find the shortcut represented by persisted legacy or generic settings. */
    public static Optional<Preset> find(String providerId, String baseUrl) {
        String provider = providerId == null ? "" : providerId.trim();
        String base = normalizeBase(baseUrl);
        for (Preset preset : ALL) {
            if (preset == CUSTOM) continue;
            if (preset.legacyId().equalsIgnoreCase(provider)) {
                return Optional.of(preset);
            }
            if (!base.isEmpty() && normalizeBase(preset.baseUrl()).equalsIgnoreCase(base)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    private static Preset preset(String legacyId, String label, String protocolId,
                                 String preferredModel, String baseUrl) {
        return new Preset(legacyId, Component.literal(label), protocolId,
                preferredModel, baseUrl);
    }

    private static String normalizeBase(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record Preset(String legacyId, Component label, String protocolId,
                         String preferredModel, String baseUrl) {}
}
