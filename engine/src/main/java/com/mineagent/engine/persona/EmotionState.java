package com.mineagent.engine.persona;

/**
 * PAD 情绪状态机 — Pleasure-Arousal-Dominance 三维情绪模型。
 *
 * <p>灵感来自 SENTIPOLIS (CMU 2026) 的 dual-speed 情绪动力学。
 * 解决 AI "情绪失忆"问题：被骂后下一轮仍然没有"积怨"。
 *
 * <p>三层设计：
 * <ul>
 *   <li><b>快变情绪</b>（秒级）：Pleasure-Arousal-Dominance 三维向量</li>
 *   <li><b>慢变心境</b>（小时级）：心境基调，影响所有快变情绪的基线</li>
 *   <li><b>情绪-记忆耦合</b>：每次事件与情绪标签一起记忆，检索时情绪反作用于解读</li>
 * </ul>
 *
 * <p>情绪影响：
 * <ul>
 *   <li>对话风格（开心→话多，悲伤→话少）</li>
 *   <li>动作选择（高唤醒→快速行动，低唤醒→缓慢）</li>
 *   <li>停顿时长（思考时受情绪影响）</li>
 *   <li>system prompt 注入（让 LLM "感受"到情绪）</li>
 * </ul>
 */
public class EmotionState {

    // ── 快变情绪 PAD 向量 [-1, 1] ──
    private float pleasure;  // -1=极度不快, +1=极度愉悦
    private float arousal;   // -1=平静, +1=高度兴奋/警觉
    private float dominance; // -1=受控/无力, +1=掌控/自信

    // ── 慢变心境基线 [-0.3, 0.3]（比快变弱，但持久）──
    private float moodPleasure;
    private float moodArousal;
    private float moodDominance;

    // ── 衰减率 ──
    /** 快变情绪每 tick 衰减率（向心境基线靠拢）。
     *  调整说明：从0.04降至0.015，使情绪持续约3秒而非1.25秒。
     *  这样情绪学习渠道（ImportanceEvaluator.learnFromEmotion）
     *  有足够时间检测到情绪变化，避免学习机制失效。
     *  同时保持情绪不会长期影响理性决策。 */
    private static final float EMOTION_DECAY = 0.015f;
    /** 心境每 tick 衰减率（向 0 靠拢）。 */
    private static final float MOOD_DECAY = 0.001f;
    /** 心境对快变情绪的拉力强度。 */
    private static final float MOOD_PULL = 0.005f;

    // ── 最近情绪标签（用于 prompt 注入）──
    private String lastEmotionLabel = "平静";

    public EmotionState() {
        this.pleasure = 0;
        this.arousal = 0;
        this.dominance = 0;
        this.moodPleasure = 0;
        this.moodArousal = 0;
        this.moodDominance = 0;
    }

    /**
     * 触发一个情绪事件。
     *
     * @param eventPleasure  事件带来的愉悦度变化 [-1, 1]
     * @param eventArousal    事件带来的唤醒度变化 [-1, 1]
     * @param eventDominance  事件带来的掌控度变化 [-1, 1]
     * @param intensity       事件强度 [0, 1]（1=极强）
     */
    public void triggerEvent(float eventPleasure, float eventArousal,
                              float eventDominance, float intensity) {
        pleasure = clamp(pleasure + eventPleasure * intensity);
        arousal = clamp(arousal + eventArousal * intensity);
        dominance = clamp(dominance + eventDominance * intensity);

        // 强烈事件也会影响心境
        if (intensity > 0.5f) {
            float moodImpact = (intensity - 0.5f) * 0.1f;
            moodPleasure = clampMood(moodPleasure + eventPleasure * moodImpact);
            moodArousal = clampMood(moodArousal + eventArousal * moodImpact);
            moodDominance = clampMood(moodDominance + eventDominance * moodImpact);
        }

        updateLabel();
    }

    /**
     * 每 tick 调用一次，让情绪自然衰减。
     * 快变情绪向心境基线靠拢，心境向 0 靠拢。
     */
    public void tick() {
        // 快变情绪向心境基线衰减
        pleasure = approach(pleasure, moodPleasure, EMOTION_DECAY);
        arousal = approach(arousal, moodArousal, EMOTION_DECAY);
        dominance = approach(dominance, moodDominance, EMOTION_DECAY);

        // 心境向 0 衰减（极慢）
        moodPleasure = approach(moodPleasure, 0, MOOD_DECAY);
        moodArousal = approach(moodArousal, 0, MOOD_DECAY);
        moodDominance = approach(moodDominance, 0, MOOD_DECAY);

        updateLabel();
    }

    /**
     * 生成供 LLM system prompt 使用的情绪描述。
     *
     * <p><b>设计原则：情绪 < 理性</b>
     * 情绪只作为上下文线索注入，让 LLM 知道"当前角色处于什么情绪状态"，
     * 但<b>不</b>指令 LLM 改变行为方式（如"话多一点""动作快一点"）。
     * LLM 应基于理性分析做决策，情绪只影响语气和用词色彩，
     * 不影响决策本身的合理性。
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 你现在的情绪\n");
        sb.append(String.format("当前情绪: %s (愉悦%.0f%% | 活跃%.0f%% | 自信%.0f%%)\n",
                lastEmotionLabel,
                (pleasure + 1) * 50, (arousal + 1) * 50, (dominance + 1) * 50));
        // 只描述情绪状态，不指令行为
        // 理性优先原则：情绪只影响语气，不影响决策
        sb.append("注意：情绪只影响你的语气和用词，");
        sb.append("不影响你的决策逻辑。");
        sb.append("无论情绪如何，始终基于理性分析做最优决策。\n");
        return sb.toString();
    }

    // ── 预定义情绪事件 ──
    // 强度已降低（约为原值的60%），确保情绪不会过强影响理性。
    // 设计原则：情绪是调味料，不是主菜。

    /** 被怪物攻击 */
    public void onAttacked() {
        triggerEvent(-0.4f, 0.5f, -0.3f, 0.5f);
    }

    /** 看到危险 */
    public void onSeeDanger() {
        triggerEvent(-0.2f, 0.4f, -0.1f, 0.3f);
    }

    /** 找到珍贵资源（钻石等） */
    public void onFindTreasure() {
        triggerEvent(0.5f, 0.3f, 0.2f, 0.4f);
    }

    /** 完成任务 */
    public void onTaskComplete() {
        triggerEvent(0.3f, 0.1f, 0.2f, 0.3f);
    }

    /** 被玩家夸奖 */
    public void onPraised() {
        triggerEvent(0.4f, 0.2f, 0.2f, 0.4f);
    }

    /** 被玩家批评 */
    public void onScolded() {
        triggerEvent(-0.3f, 0.1f, -0.2f, 0.3f);
    }

    /** 任务失败 */
    public void onTaskFailed() {
        triggerEvent(-0.2f, 0.2f, -0.2f, 0.3f);
    }

    /** 玩家受伤 */
    public void onOwnerHurt() {
        triggerEvent(-0.2f, 0.4f, -0.2f, 0.3f);
    }

    // ── Getters ──

    public float pleasure() { return pleasure; }
    public float arousal() { return arousal; }
    public float dominance() { return dominance; }
    public String label() { return lastEmotionLabel; }

    // ── 内部方法 ──

    private void updateLabel() {
        // 基于 PAD 值映射到语义情绪标签
        if (pleasure > 0.5f && arousal > 0.3f) lastEmotionLabel = "兴奋";
        else if (pleasure > 0.5f && arousal < -0.2f) lastEmotionLabel = "满足";
        else if (pleasure > 0.3f) lastEmotionLabel = "开心";
        else if (pleasure < -0.5f && arousal > 0.5f) lastEmotionLabel = "愤怒";
        else if (pleasure < -0.5f && arousal < -0.2f) lastEmotionLabel = "沮丧";
        else if (pleasure < -0.3f && arousal > 0.4f) lastEmotionLabel = "害怕";
        else if (pleasure < -0.3f) lastEmotionLabel = "不快";
        else if (arousal > 0.5f) lastEmotionLabel = "警觉";
        else if (arousal < -0.3f) lastEmotionLabel = "平静";
        else lastEmotionLabel = "平静";
    }

    private static float approach(float current, float target, float rate) {
        if (current > target) {
            return Math.max(target, current - rate);
        } else {
            return Math.min(target, current + rate);
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float clampMood(float v) {
        return Math.max(-0.3f, Math.min(0.3f, v));
    }
}
