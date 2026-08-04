package com.mineagent.engine.client.ui;

import java.util.List;

/**
 * Provider shortcuts shared by the connection and companion creation screens.
 * Keeping the preferred model beside the provider prevents a shortcut from
 * selecting (for example) Anthropic while silently leaving a DeepSeek model in
 * the adjacent field.
 */
public final class ProviderPresets {

    public static final List<Preset> ALL = List.of(
            new Preset("deepseek", "DeepSeek", "deepseek-v4-flash"),
            new Preset("openai", "OpenAI", "gpt-5.6-sol"),
            new Preset("anthropic", "Anthropic", "claude-opus-5"),
            new Preset("gemini", "Gemini", "gemini-3.6-flash"),
            new Preset("qwen", "Qwen", "qwen3.8-max"),
            new Preset("glm", "GLM", "glm-5.2"),
            new Preset("grok", "Grok", "grok-4.5"),
            new Preset("moonshot", "Kimi", "kimi-k3"),
            new Preset("minimax", "MiniMax", "MiniMax-M3")
    );

    private ProviderPresets() {}

    public record Preset(String id, String label, String preferredModel) {}
}
