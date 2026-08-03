package com.mineagent.api.llm.model;

/**
 * Predefined model families with their provider mappings and default configs.
 */
public final class ModelFamilies {

    private ModelFamilies() {}

    // ── OpenAI family ──────────────────────────────────────────────
    public static final String OPENAI_GPT4O = "gpt-4o";
    public static final String OPENAI_GPT4O_MINI = "gpt-4o-mini";
    public static final String OPENAI_GPT41 = "gpt-4.1";
    public static final String OPENAI_GPT41_MINI = "gpt-4.1-mini";
    public static final String OPENAI_GPT41_NANO = "gpt-4.1-nano";
    public static final String OPENAI_O3 = "o3";
    public static final String OPENAI_O4_MINI = "o4-mini";

    // ── DeepSeek family ────────────────────────────────────────────
    public static final String DEEPSEEK_CHAT = "deepseek-chat";
    public static final String DEEPSEEK_REASONER = "deepseek-reasoner";

    // ── Google Gemini family ───────────────────────────────────────
    public static final String GEMINI_25_PRO = "gemini-2.5-pro";
    public static final String GEMINI_25_FLASH = "gemini-2.5-flash";

    // ── Qwen family ────────────────────────────────────────────────
    public static final String QWEN_MAX = "qwen-max";
    public static final String QWEN_PLUS = "qwen-plus";
    public static final String QWEN_TURBO = "qwen-turbo";

    // ── GLM family ─────────────────────────────────────────────────
    public static final String GLM_4_PLUS = "glm-4-plus";
    public static final String GLM_4_FLASH = "glm-4-flash";
    public static final String GLM_4_LONG = "glm-4-long";

    // ── Moonshot (Kimi) family ─────────────────────────────────────
    public static final String MOONSHOT_V1 = "moonshot-v1-auto";
    public static final String KIMI_K2 = "kimi-k2";

    // ── Anthropic (Claude) family ──────────────────────────────────
    public static final String CLAUDE_SONNET_4 = "claude-sonnet-4-20250514";
    public static final String CLAUDE_37_SONNET = "claude-3-7-sonnet-20250219";

    // ── xAI (Grok) family ──────────────────────────────────────────
    public static final String GROK_3 = "grok-3";
    public static final String GROK_3_MINI = "grok-3-mini";

    /**
     * Default max_tokens for each model family.
     *
     * <p>Tuned for an in-game AI player: a single turn usually needs
     * one or two tool calls plus a short chat line, so 1024–2048
     * output tokens is plenty for non-reasoning models. Bigger
     * budgets are reserved for models that emit an internal reasoning
     * trace (o-series, deepseek-reasoner, gemini-2.5 thinking), where
     * the thinking tokens count against the output limit.
     *
     * <p>Smaller budgets make the LLM return faster, which keeps the
     * companion responsive and avoids the "frozen while thinking"
     * feeling.
     */
    public static int defaultMaxTokens(String model) {
        if (model == null) return 2048;
        // Normalize to lowercase for case-insensitive prefix matching —
        // some providers (notably MiniMax: "MiniMax-M3") ship mixed-case
        // model names, and we don't want to silently fall through to the
        // 2048 default just because of casing.
        String m = model.toLowerCase();

        // ── Reasoning models: thinking tokens consume the output budget,
        //    so they need more headroom — but still bounded. ──
        // OpenAI o-series & GPT-5.x (always-on reasoning, large trace)
        if (m.startsWith("o3") || m.startsWith("o4")) return 8192;
        if (m.startsWith("gpt-5")) return 8192;
        // Anthropic Claude 5.x (Opus/Sonnet/Fable) — extended thinking,
        // 128K output ceiling; 8192 is a safe default for one agent turn.
        if (m.startsWith("claude-opus-5")
                || m.startsWith("claude-sonnet-5")
                || m.startsWith("claude-fable-5")) return 8192;
        // Moonshot Kimi K3 — always-thinking, 2.8T params, large trace.
        if (m.startsWith("kimi-k3")) return 8192;
        // 4K tier — reasoning models with smaller typical trace:
        if (m.startsWith("gemini-3")) return 4096;
        if (m.startsWith("grok-4")) return 4096;
        if (m.startsWith("glm-5")) return 4096;
        if (m.startsWith("qwen3")) return 4096;
        if (m.startsWith("deepseek-v4")) return 4096;
        if (m.startsWith("minimax-m3") || m.startsWith("m3-")) return 4096;
        // DeepSeek reasoner (V3 reasoning variant) — legacy reasoning path.
        if (m.startsWith("deepseek-reasoner")) return 4096;
        // Gemini 2.5 — explicit per-variant defaults.
        if (m.startsWith("gemini-2.5-pro")) return 4096;
        if (m.startsWith("gemini-2.5-flash")) return 2048;
        // Standard chat models: a tool call + short reply fits in 2048.
        return 2048;
    }
}
