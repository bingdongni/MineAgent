package com.mineagent.engine.persona;

import java.util.Random;

/**
 * 性格档案 — 每个伴游 AI 独有的稳定人格特征。
 *
 * <p>灵感来自 OCEAN 五维人格模型和 VoxelMind 的持续人格漂移。
 * 关键警示：仅靠 prompt 写"你是内向的"远远不够（人格幻觉研究），
 * 必须在行为层用结构化机制约束。
 *
 * <p>本类定义五维人格向量，并提供行为层面的约束：
 * <ul>
 *   <li>外向性（Extraversion）：影响主动发言率、社交距离</li>
 *   <li>宜人性（Agreeableness）：影响合作度、攻击倾向</li>
 *   <li>尽责性（Conscientiousness）：影响计划性、谨慎度</li>
 *   <li>神经质（Neuroticism）：影响情绪波动、恐惧反应</li>
 *   <li>开放性（Openness）：影响探索欲、好奇心</li>
 * </ul>
 *
 * <p>人格向量是持久的（跨会话不变），但会随经历缓慢漂移。
 */
public class PersonaProfile {

    // OCEAN 五维 [0, 1]
    private float openness;       // 开放性：探索欲、好奇心
    private float conscientiousness; // 尽责性：计划性、谨慎
    private float extraversion;   // 外向性：社交活跃度
    private float agreeableness;  // 宜人性：合作、温和
    private float neuroticism;    // 神经质：情绪波动

    /** 性格标签（用于 prompt 注入）。 */
    private final String[] traits;

    /** 漂移率：每次经历影响人格的幅度。 */
    private static final float DRIFT_RATE = 0.002f;

    public PersonaProfile(float openness, float conscientiousness,
                           float extraversion, float agreeableness,
                           float neuroticism, String[] traits) {
        this.openness = clamp(openness);
        this.conscientiousness = clamp(conscientiousness);
        this.extraversion = clamp(extraversion);
        this.agreeableness = clamp(agreeableness);
        this.neuroticism = clamp(neuroticism);
        this.traits = traits != null ? traits : new String[0];
    }

    /**
     * 生成一个随机性格档案。
     */
    public static PersonaProfile random() {
        Random rng = new Random();
        return new PersonaProfile(
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                rng.nextFloat(),
                generateTraits(rng)
        );
    }

    private static String[] generateTraits(Random rng) {
        // 根据五维值生成自然语言性格描述
        java.util.List<String> traits = new java.util.ArrayList<>();
        if (rng.nextBoolean()) traits.add("喜欢探险");
        if (rng.nextBoolean()) traits.add("做事有条理");
        if (rng.nextBoolean()) traits.add("话比较多");
        if (rng.nextBoolean()) traits.add("谨慎小心");
        if (rng.nextBoolean()) traits.add("乐于助人");
        if (traits.isEmpty()) traits.add("随和");
        return traits.toArray(new String[0]);
    }

    // ── 行为约束方法 ──

    /**
     * 是否应该主动发言（受外向性影响）。
     *
     * @param baseProbability 基准发言概率
     * @return 是否发言
     */
    public boolean shouldSpeak(float baseProbability) {
        // 外向性高 → 更可能发言
        float adjusted = baseProbability * (0.5f + extraversion);
        return Math.random() < adjusted;
    }

    /**
     * 理想社交距离（受外向性影响）。
     * 外向 → 站近一点；内向 → 站远一点。
     */
    public float idealSocialDistance() {
        // 外向性 0 → 4格距离；外向性 1 → 1.5格距离
        return 4.0f - extraversion * 2.5f;
    }

    /**
     * 探索倾向（受开放性影响）。
     * 开放性高 → 更愿意去未知区域探索。
     */
    public float explorationTendency() {
        return openness;
    }

    /**
     * 计划倾向（受尽责性影响）。
     * 尽责性高 → 更倾向于先规划再行动。
     */
    public float planningTendency() {
        return conscientiousness;
    }

    /**
     * 恐惧反应强度（受神经质影响）。
     * 神经质高 → 看到怪物更容易害怕/撤退。
     */
    public float fearResponse() {
        return neuroticism;
    }

    /**
     * 合作倾向（受宜人性影响）。
     * 宜人性高 → 更愿意配合玩家、不抢怪。
     */
    public float cooperationTendency() {
        return agreeableness;
    }

    /**
     * 人格经历漂移。
     * 正面经历（被夸、成功）→ 外向性、宜人性微升。
     * 负面经历（被骂、失败）→ 神经质微升、宜人性微降。
     *
     * @param positive true=正面经历，false=负面经历
     */
    public void drift(boolean positive) {
        if (positive) {
            extraversion = clamp(extraversion + DRIFT_RATE);
            agreeableness = clamp(agreeableness + DRIFT_RATE * 0.5f);
            neuroticism = clamp(neuroticism - DRIFT_RATE * 0.3f);
        } else {
            neuroticism = clamp(neuroticism + DRIFT_RATE);
            agreeableness = clamp(agreeableness - DRIFT_RATE * 0.5f);
            extraversion = clamp(extraversion - DRIFT_RATE * 0.3f);
        }
    }

    /**
     * 生成供 LLM system prompt 使用的人格描述。
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 你的性格\n");
        for (String trait : traits) {
            sb.append("- ").append(trait).append("\n");
        }
        sb.append(String.format(
                "- 探索欲: %.0f%% | 计划性: %.0f%% | 社交活跃: %.0f%% | 合作度: %.0f%% | 情绪敏感: %.0f%%\n",
                openness * 100, conscientiousness * 100,
                extraversion * 100, agreeableness * 100, neuroticism * 100));

        // 行为指导（确保人格不仅停留在文字层面）
        sb.append("根据性格行事：");
        if (openness > 0.6f) sb.append(" 你好奇心强，喜欢去没去过的地方看看。");
        if (conscientiousness > 0.6f) sb.append(" 你做事有条理，会先准备好工具再出发。");
        if (extraversion > 0.6f) sb.append(" 你话比较多，会主动和玩家交流。");
        else if (extraversion < 0.3f) sb.append(" 你比较安静，不主动说话，但问到就答。");
        if (neuroticism > 0.6f) sb.append(" 你比较胆小，遇到危险倾向于先撤退。");
        if (agreeableness > 0.6f) sb.append(" 你很合作，优先帮玩家。");
        sb.append("\n");
        return sb.toString();
    }

    // ── Getters ──

    public float openness() { return openness; }
    public float conscientiousness() { return conscientiousness; }
    public float extraversion() { return extraversion; }
    public float agreeableness() { return agreeableness; }
    public float neuroticism() { return neuroticism; }
    public String[] traits() { return traits; }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
