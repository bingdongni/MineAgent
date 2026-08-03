package com.mineagent.api.llm.model;

import java.util.List;

/**
 * Specification for each model's thinking effort (reasoning depth) configuration.
 *
 * <p>This class centralizes the official documentation for every supported model
 * family so that providers and UI can both rely on a single source of truth.
 * All data below is verified against official docs as of 2026-08-01.
 *
 * <h3>Key design principle</h3>
 * <ul>
 *   <li>Thinking effort is <b>NEVER mandatory</b> — when the user leaves it
 *       empty/null, NO parameter is injected into the request body, so the API
 *       uses its own default behavior.</li>
 *   <li>"off" means "disable thinking" where the model supports it; for models
 *       that cannot disable thinking (o-series, Grok 4.x, Kimi K3, Fable 5,
 *       Mythos 5, Qwen3-Thinking, QwQ, MiniMax M2.x), "off" is mapped to the
 *       lowest supported level instead.</li>
 *   <li>Some models do not support reasoning_effort at all (GPT-4o, GPT-4.1,
 *       Grok 4.0 and earlier, Qwen2.5, moonshot-v1) — for these, the field is
 *       ignored entirely.</li>
 * </ul>
 *
 * <h3>Supported levels per family (per official docs as of 2026-08-01)</h3>
 * <pre>
 *   OpenAI:
 *     GPT-5.6 (sol/terra/luna): none/low/medium/high/xhigh/max, default medium
 *     GPT-5.5/5.4/5.2: none/low/medium/high/xhigh, default medium (5.2 default none)
 *     GPT-5.1: none/low/medium/high, default medium
 *     GPT-5/5-mini/5-nano: minimal/low/medium/high, default medium (cannot disable)
 *     o3/o3-mini/o3-pro: low/medium/high, default medium (cannot disable)
 *     o4-mini: low/medium/high, default low (cannot disable)
 *     GPT-4.1/4o/4/3.5: NOT supported
 *
 *   Claude (Anthropic):
 *     output_config.effort = low/medium/high/xhigh/max, default high
 *     Fable 5 / Mythos 5: all 5 levels, CANNOT disable thinking
 *     Opus 5 / Sonnet 5 / Opus 4.8 / Opus 4.7: all 5 levels, CAN disable
 *     Opus 4.6 / Sonnet 4.6: low/medium/high/max (no xhigh), CAN disable
 *     Opus 4.5: low/medium/high (no max/xhigh), CAN disable
 *     Sonnet 4.5 / Haiku 4.5 / Opus 4 / Sonnet 4 / 3.7: budget_tokens only, CAN disable
 *
 *   Gemini:
 *     3.x: thinkingLevel = minimal/low/medium/high (CANNOT fully disable)
 *       Pro: low/medium/high (no minimal), default high
 *       Flash/Flash-Lite: minimal/low/medium/high, default medium/minimal
 *     2.5: thinkingBudget = integer
 *       Pro: 128-32768, CANNOT disable (0 returns 400), default dynamic
 *       Flash: 1-24576, CAN disable (0), default dynamic
 *       Flash-Lite: default OFF
 *
 *   DeepSeek:
 *     V4-Pro/Flash: thinking.type=enabled/disabled + reasoning_effort=high/max
 *       off → thinking.type=disabled, default high, CAN disable
 *     V3/chat: NOT supported
 *     reasoner: always thinking, no effort control
 *
 *   Qwen3:
 *     Hybrid (qwen3.x/qwen3-): enable_thinking + thinking_budget (1-81920)
 *       off → enable_thinking=false, default medium, CAN disable
 *     Thinking-2507 / QwQ: thinking-only, CANNOT disable
 *     Instruct-2507: NOT supported (cannot enable thinking)
 *     Qwen2.5 (qwen-max/plus/turbo): NOT supported
 *
 *   GLM:
 *     GLM-5.2: thinking.type + reasoning_effort = max/xhigh/high/medium/low/minimal/none
 *       off → thinking.type=disabled, default max, CAN disable
 *     GLM-5.1/5/5-Turbo: thinking.type only (no effort levels), CAN disable
 *     GLM-4.7/4.6/4.5: thinking.type only, CAN disable
 *     GLM-4 and older: NOT supported
 *
 *   Kimi:
 *     K3: reasoning_effort = low/high/max, default max, CANNOT disable (always thinking)
 *     K2.6/K2.5: thinking.type = enabled/disabled, default on, CAN disable
 *     K2.7-code/K2-thinking: always thinking, CANNOT disable
 *     moonshot-v1: NOT supported
 *
 *   Grok:
 *     4.5: reasoning_effort = low/medium/high, default high, CANNOT disable
 *     4.3: reasoning_effort = low/medium/high, default medium, CANNOT disable
 *     4.0/3.x: NOT supported (returns 400)
 *
 *   MiniMax:
 *     M3: thinking.type = adaptive/disabled, default adaptive(on), CAN disable
 *     M2.x: thinking always on, CANNOT disable
 * </pre>
 */
public final class ThinkingEffortSpec {

    /** Standard level names used across the UI. "off" disables thinking. */
    public static final String OFF = "off";
    public static final String MINIMAL = "minimal";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String XHIGH = "xhigh";
    public static final String MAX = "max";

    /** Canonical ordered list shown in the UI when nothing more specific is known. */
    public static final List<String> DEFAULT_LEVELS =
            List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX);

    private final String model;
    private final List<String> supportedLevels;
    private final String defaultLevel;
    private final boolean canDisable;
    private final boolean supportsEffort;

    private ThinkingEffortSpec(String model, List<String> supportedLevels,
                                  String defaultLevel, boolean canDisable,
                                  boolean supportsEffort) {
        this.model = model;
        this.supportedLevels = List.copyOf(supportedLevels);
        this.defaultLevel = defaultLevel;
        this.canDisable = canDisable;
        this.supportsEffort = supportsEffort;
    }

    /** The model name this spec was resolved for. */
    public String model() { return model; }

    /** Levels the UI should offer for this model (in display order). */
    public List<String> supportedLevels() { return supportedLevels; }

    /** The level used when the user leaves the field empty (never null). */
    public String defaultLevel() { return defaultLevel; }

    /** Whether "off" genuinely disables thinking for this model. */
    public boolean canDisable() { return canDisable; }

    /** Whether the model accepts any thinking-effort parameter at all. */
    public boolean supportsEffort() { return supportsEffort; }

    /**
     * Resolve the spec for a given model name. The matching is purely
     * string-based on the model id, so it works for both official endpoints
     * and relay/proxy base URLs.
     *
     * <p>The returned spec is always non-null — unknown models get a
     * permissive default that exposes all standard levels.
     */
    public static ThinkingEffortSpec forModel(String model) {
        if (model == null || model.isBlank()) {
            return new ThinkingEffortSpec("", DEFAULT_LEVELS, MEDIUM, true, false);
        }
        String m = model.toLowerCase();

        ThinkingEffortSpec spec = forOpenAI(m, model);
        if (spec != null) return spec;

        spec = forClaude(m, model);
        if (spec != null) return spec;

        spec = forGemini(m, model);
        if (spec != null) return spec;

        spec = forDeepSeek(m, model);
        if (spec != null) return spec;

        spec = forQwen(m, model);
        if (spec != null) return spec;

        spec = forGLM(m, model);
        if (spec != null) return spec;

        spec = forKimi(m, model);
        if (spec != null) return spec;

        spec = forGrok(m, model);
        if (spec != null) return spec;

        spec = forMiniMax(m, model);
        if (spec != null) return spec;

        // Unknown model — permissive default
        return new ThinkingEffortSpec(model, DEFAULT_LEVELS, MEDIUM, true, false);
    }

    // ── OpenAI ────────────────────────────────────────────────────

    private static ThinkingEffortSpec forOpenAI(String m, String model) {
        // GPT-5.6 (sol/terra/luna) — 2026-07-09, supports max, default medium
        if (m.startsWith("gpt-5.6")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    MEDIUM, true, true);
        }
        // GPT-5.5 / 5.4 / 5.2 — none/low/medium/high/xhigh, no max, no minimal
        if (m.startsWith("gpt-5.5") || m.startsWith("gpt-5.4")
                || m.startsWith("gpt-5.2")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH),
                    MEDIUM, true, true);
        }
        // GPT-5.1 — none/low/medium/high
        if (m.startsWith("gpt-5.1")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH),
                    MEDIUM, true, true);
        }
        // GPT-5 / 5-mini / 5-nano — minimal/low/medium/high, CANNOT disable (no none)
        if (m.startsWith("gpt-5")) {
            return new ThinkingEffortSpec(model,
                    List.of(MINIMAL, LOW, MEDIUM, HIGH),
                    MEDIUM, false, true);
        }
        // o4-mini — low/medium/high, default low, CANNOT disable
        if (m.startsWith("o4-")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    LOW, false, true);
        }
        // o3 / o3-mini / o3-pro — low/medium/high, default medium, CANNOT disable
        if (m.startsWith("o3")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    MEDIUM, false, true);
        }
        // GPT-4.1 / GPT-4o / GPT-4 / GPT-3.5 — non-reasoning, NOT supported
        if (m.startsWith("gpt-4") || m.startsWith("gpt-3")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── Claude (Anthropic) ────────────────────────────────────────

    private static ThinkingEffortSpec forClaude(String m, String model) {
        // Fable 5 / Mythos 5 — all 5 levels, CANNOT disable (always thinking)
        if (m.startsWith("claude-fable-5") || m.startsWith("claude-mythos")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, false, true);
        }
        // Opus 5 / Sonnet 5 — all 5 levels, CAN disable
        if (m.startsWith("claude-opus-5") || m.startsWith("claude-sonnet-5")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, true, true);
        }
        // Opus 4.8 / 4.7 — all 5 levels, CAN disable
        if (m.startsWith("claude-opus-4-8") || m.startsWith("claude-opus-4-7")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, true, true);
        }
        // Opus 4.6 / Sonnet 4.6 — low/medium/high/max (no xhigh), CAN disable
        if (m.startsWith("claude-opus-4-6") || m.startsWith("claude-sonnet-4-6")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, MAX),
                    HIGH, true, true);
        }
        // Opus 4.5 — low/medium/high (no max, no xhigh), CAN disable
        // Note: only match "claude-opus-4-5" to avoid catching Opus 4 (claude-opus-4-2025xxxx)
        if (m.startsWith("claude-opus-4-5")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH),
                    HIGH, true, true);
        }
        // Sonnet 4.5 / Haiku 4.5 / Opus 4 / Sonnet 4 — budget_tokens only, CAN disable
        // Catch all remaining Claude 4.x models that weren't matched above
        if (m.startsWith("claude-sonnet-4-5")
                || m.startsWith("claude-haiku-4")
                || m.startsWith("claude-opus-4-")
                || m.startsWith("claude-sonnet-4-")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // Claude 3.7 Sonnet — extended thinking (budget_tokens), CAN disable
        if (m.startsWith("claude-3-7")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // Claude 3.5 / 3 — no thinking support
        if (m.startsWith("claude-3-5") || m.startsWith("claude-3-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        if (m.startsWith("claude-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── Gemini ────────────────────────────────────────────────────

    private static ThinkingEffortSpec forGemini(String m, String model) {
        // Gemini 3.x Pro — low/medium/high (no minimal), CANNOT disable
        if (m.startsWith("gemini-3.1-pro") || m.startsWith("gemini-3-pro")
                || m.startsWith("gemini-3.6-pro")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    HIGH, false, true);
        }
        // Gemini 3.x Flash / Flash-Lite — minimal/low/medium/high, CANNOT fully disable
        if (m.startsWith("gemini-3.6") || m.startsWith("gemini-3.5-flash")
                || m.startsWith("gemini-3.1-flash") || m.startsWith("gemini-3-flash")
                || m.startsWith("gemini-3.5-flash-lite") || m.startsWith("gemini-3.1-flash-lite")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, MINIMAL, LOW, MEDIUM, HIGH),
                    MEDIUM, false, true);
        }
        // Gemini 3 catch-all (other 3.x) — same as Flash
        if (m.startsWith("gemini-3")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, MINIMAL, LOW, MEDIUM, HIGH),
                    HIGH, false, true);
        }
        // Gemini 2.5 Flash-Lite — default OFF, CAN disable
        if (m.startsWith("gemini-2.5-flash-lite")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH),
                    OFF, true, true);
        }
        // Gemini 2.5 Flash — CAN disable (thinkingBudget=0)
        if (m.startsWith("gemini-2.5-flash")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH),
                    HIGH, true, true);
        }
        // Gemini 2.5 Pro — CANNOT disable (thinkingBudget=0 returns 400)
        if (m.startsWith("gemini-2.5-pro") || m.startsWith("gemini-2.5")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    HIGH, false, true);
        }
        // Gemini 2.0 / 1.5 — no thinking support
        if (m.startsWith("gemini-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── DeepSeek ──────────────────────────────────────────────────

    private static ThinkingEffortSpec forDeepSeek(String m, String model) {
        // V4-Pro/Flash — thinking.type + reasoning_effort (high/max), CAN disable
        if (m.startsWith("deepseek-v4")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, true, true);
        }
        // V3.2 (Alibaba Cloud) — hybrid, enable_thinking + reasoning_effort
        if (m.startsWith("deepseek-v3.2") || m.startsWith("deepseek-v3.1")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, true, true);
        }
        // deepseek-reasoner — always thinking, no effort control
        if (m.startsWith("deepseek-reasoner") || m.startsWith("deepseek-r1")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        // deepseek-chat / V3 — no thinking support
        if (m.startsWith("deepseek-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── Qwen ──────────────────────────────────────────────────────

    private static ThinkingEffortSpec forQwen(String m, String model) {
        // Qwen3-Thinking-2507 / QwQ — thinking-only, CANNOT disable
        if (m.contains("thinking-2507") || m.startsWith("qwq")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH, XHIGH, MAX),
                    HIGH, false, true);
        }
        // Qwen3-Instruct-2507 — non-thinking only, NOT supported
        if (m.contains("instruct-2507")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        // Qwen3+ hybrid models — enable_thinking + thinking_budget, CAN disable
        if (m.startsWith("qwen3.8") || m.startsWith("qwen3.7")
                || m.startsWith("qwen3.6") || m.startsWith("qwen3.5")
                || m.startsWith("qwen3-") || m.startsWith("qwen3")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    MEDIUM, true, true);
        }
        // Qwen2.5 (qwen-max/plus/turbo/flash) — NOT supported
        if (m.startsWith("qwen-") || m.startsWith("qwen")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── GLM ───────────────────────────────────────────────────────

    private static ThinkingEffortSpec forGLM(String m, String model) {
        // GLM-5.2 — thinking.type + reasoning_effort (7 levels), CAN disable
        if (m.startsWith("glm-5.2")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX),
                    MAX, true, true);
        }
        // GLM-5.1 / 5 / 5-Turbo / 5V-Turbo — thinking.type only, CAN disable
        if (m.startsWith("glm-5")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // GLM-4.7 / 4.6 / 4.5 — thinking.type only, CAN disable
        if (m.startsWith("glm-4.7") || m.startsWith("glm-4.6")
                || m.startsWith("glm-4.5")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // GLM-4 and older — NOT supported
        if (m.startsWith("glm-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── Kimi / Moonshot ───────────────────────────────────────────

    private static ThinkingEffortSpec forKimi(String m, String model) {
        // Kimi K3 — reasoning_effort = low/high/max, CANNOT disable (always thinking)
        if (m.startsWith("kimi-k3")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH, XHIGH, MAX),
                    MAX, false, true);
        }
        // K2.7-code — always thinking, CANNOT disable
        if (m.startsWith("kimi-k2.7")) {
            return new ThinkingEffortSpec(model,
                    List.of(HIGH),
                    HIGH, false, true);
        }
        // K2.6 / K2.5 — thinking.type = enabled/disabled, CAN disable
        if (m.startsWith("kimi-k2.6") || m.startsWith("kimi-k2.5")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // K2-thinking — forced thinking, CANNOT disable
        if (m.startsWith("kimi-k2-thinking")) {
            return new ThinkingEffortSpec(model,
                    List.of(HIGH),
                    HIGH, false, true);
        }
        // moonshot-v1 — NOT supported
        if (m.startsWith("kimi-") || m.startsWith("moonshot-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── Grok (xAI) ────────────────────────────────────────────────

    private static ThinkingEffortSpec forGrok(String m, String model) {
        // Grok 4.5 — low/medium/high, default high, CANNOT disable
        if (m.startsWith("grok-4.5")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    HIGH, false, true);
        }
        // Grok 4.3 — low/medium/high, default medium, CANNOT disable
        if (m.startsWith("grok-4.3") || m.startsWith("grok-4.20")
                || m.startsWith("grok-4.1") || m.startsWith("grok-build")) {
            return new ThinkingEffortSpec(model,
                    List.of(LOW, MEDIUM, HIGH),
                    MEDIUM, false, true);
        }
        // Grok 4.0 / 3.x — NOT supported (returns 400)
        if (m.startsWith("grok-4") || m.startsWith("grok-3")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        if (m.startsWith("grok-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    // ── MiniMax ───────────────────────────────────────────────────

    private static ThinkingEffortSpec forMiniMax(String m, String model) {
        // MiniMax-M3 — thinking.type = adaptive/disabled, CAN disable
        if (m.equals("minimax-m3") || m.startsWith("minimax-m3")) {
            return new ThinkingEffortSpec(model,
                    List.of(OFF, HIGH),
                    HIGH, true, true);
        }
        // MiniMax-M2.x — thinking always on, CANNOT disable
        if (m.startsWith("minimax-m2") || m.startsWith("m2-")) {
            return new ThinkingEffortSpec(model,
                    List.of(HIGH),
                    HIGH, false, true);
        }
        if (m.startsWith("minimax-")) {
            return new ThinkingEffortSpec(model, List.of(), "", false, false);
        }
        return null;
    }

    /**
     * A short hint string for the UI text box, e.g.
     * "off/low/medium/high/xhigh/max (default high)".
     */
    public String hint() {
        if (supportedLevels.isEmpty()) {
            return "§7(no thinking support)";
        }
        StringBuilder sb = new StringBuilder("§7");
        for (int i = 0; i < supportedLevels.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(supportedLevels.get(i));
        }
        sb.append(" (default ").append(defaultLevel).append(")");
        return sb.toString();
    }
}
