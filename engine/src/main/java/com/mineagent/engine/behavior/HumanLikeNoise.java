package com.mineagent.engine.behavior;

import java.util.Random;

/**
 * 行为噪声层 — 让AI的动作更像真人，消除机械感。
 *
 * <p>真人玩家与机器人的核心差异（来自反作弊检测研究）：
 * <ul>
 *   <li>动作间隔有抖动（机器是毫秒级精确）</li>
 *   <li>转向是平滑曲线而非线性（机器瞬切）</li>
 *   <li>复杂局面会犹豫停顿（机器速度不变）</li>
 *   <li>偶尔犯小错再纠正（机器完美执行）</li>
 * </ul>
 *
 * <p>本类作为包裹层，为上层决策提供"人化"修饰：
 * <ul>
 *   <li>{@link #jitterInterval} — 给动作间隔加高斯抖动</li>
 *   <li>{@link #easeInOutTurn} — 用 ease-in-out 曲线平滑转向</li>
 *   <li>{@link #maybeHesitate} — 概率性注入思考停顿</li>
 *   <li>{@link #maybeMistake} — 概率性注入小错误+纠正</li>
 * </ul>
 *
 * <p>所有方法都是静态工具，无状态，可安全并发调用。
 */
public final class HumanLikeNoise {

    private HumanLikeNoise() {}

    private static final Random RNG = new Random();

    /** 高斯抖动的标准差（±15%间隔）。 */
    private static final double INTERVAL_JITTER_STD = 0.15;

    /** 思考停顿触发概率（3%）。 */
    private static final double HESITATE_PROB = 0.03;

    /** 小错误触发概率（1.5%）。 */
    private static final double MISTAKE_PROB = 0.015;

    /**
     * 给一个基准间隔加 ±15% 高斯抖动。
     *
     * @param baseIntervalMs 基准间隔（毫秒）
     * @return 加噪后的间隔
     */
    public static long jitterInterval(long baseIntervalMs) {
        double noise = RNG.nextGaussian() * INTERVAL_JITTER_STD;
        double adjusted = baseIntervalMs * (1.0 + noise);
        return Math.max(1, (long) adjusted);
    }

    /**
     * Ease-in-out 平滑转向曲线。
     *
     * <p>当前 Movement 用线性 15°/tick 转向，看起来像机器人。
     * 真人转向是"先快后慢"（接近目标角度时减速）。
     * 本方法把线性 progress (0→1) 映射为 ease-in-out 曲线，
     * 使得转向开始和结束时都更平滑。
     *
     * @param linearProgress 线性进度 [0, 1]
     * @return ease-in-out 调整后的进度 [0, 1]
     */
    public static float easeInOutTurn(float linearProgress) {
        // 经典 ease-in-out: 3*t^2 - 2*t^3 (smoothstep)
        float p = clamp(linearProgress, 0f, 1f);
        return p * p * (3f - 2f * p);
    }

    /**
     * 根据剩余角度差计算本 tick 应转多少度。
     *
     * <p>接近目标时减速（ease-out），远处时快速转（ease-in）。
     * 这模拟真人"甩鼠标"的行为：快速转向大致方向，然后微调对准。
     *
     * @param remainingDiff 剩余角度差（度，已 wrap 到 -180~180）
     * @param maxPerTick    每 tick 最大转角度
     * @return 本 tick 实际转角度
     */
    public static float adaptiveTurn(float remainingDiff, float maxPerTick) {
        float absDiff = Math.abs(remainingDiff);
        if (absDiff < 0.5f) return 0;

        // 远处快速转（满速），近处减速
        // 当角度差 > 45° 时满速转
        // 当角度差 < 45° 时按 ease-out 减速
        float speedRatio;
        if (absDiff > 45f) {
            speedRatio = 1.0f;
        } else {
            // ease-out: (diff/45)^0.5，开方让减速更自然
            float t = absDiff / 45f;
            speedRatio = (float) Math.sqrt(t);
        }

        float turn = Math.signum(remainingDiff) * Math.min(maxPerTick, absDiff * speedRatio);
        // 加微小抖动（真人手不可能完美稳定）
        turn += (RNG.nextFloat() - 0.5f) * 0.8f;
        return turn;
    }

    /**
     * 概率性决定是否需要"犹豫停顿"。
     *
     * <p>真人在复杂局面会短暂停顿"想一想"。
     * 约 3% 概率返回 true，调用方应在此时注入 200-500ms 延迟。
     *
     * @return true 表示应该犹豫一下
     */
    public static boolean maybeHesitate() {
        return RNG.nextDouble() < HESITATE_PROB;
    }

    /**
     * 犹豫停顿时长（200-500ms）。
     */
    public static int hesitationDurationMs() {
        return 200 + RNG.nextInt(301); // 200-500ms
    }

    /**
     * 概率性决定是否需要"小错误+纠正"。
     *
     * <p>约 1.5% 概率返回 true，调用方应注入一个小错误
     * （如挖错一块、多走一步），然后立即纠正。
     * 这是消除"完美执行"人机感的关键。
     *
     * @return true 表示应该犯个小错
     */
    public static boolean maybeMistake() {
        return RNG.nextDouble() < MISTAKE_PROB;
    }

    /**
     * 给移动速度加微小抖动，模拟真人手抖。
     *
     * @param baseSpeed 基准速度 [0, 1]
     * @return 加噪后速度（±5%）
     */
    public static float jitterSpeed(float baseSpeed) {
        float noise = (float) (RNG.nextGaussian() * 0.05);
        return clamp(baseSpeed + noise, 0f, 1f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
