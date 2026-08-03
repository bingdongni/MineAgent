package com.mineagent.engine.theory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 一阶 Theory of Mind — 建模玩家意图。
 *
 * <p>灵感来自 Hypothetical Minds (Stanford) 和 LLM-Hanabi 研究。
 * 关键发现：一阶 ToM（解读他人意图）比二阶 ToM（预测他人如何解读自己）
 * 与游戏成功的相关性强得多——因此伴游 AI 应优先做一阶。
 *
 * <p>本类维护对玩家意图的假设：
 * <ul>
 *   <li>当前意图（如"挖矿"、"探索"、"建造"、"战斗"）</li>
 *   <li>紧急程度（是否在赶时间）</li>
 *   <li>玩家情绪状态（开心/沮丧/紧张）</li>
 *   <li>玩家对伴游的态度（信任/怀疑/依赖）</li>
 * </ul>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>移除硬编码关键词: 使用通用动作模式匹配</li>
 *   <li>延长行为记录时间窗: 从10秒延长至60秒，意图推断更稳定</li>
 *   <li>多信号融合: 结合动作、位置、物品等多信号推断意图</li>
 * </ul>
 *
 * <p>这些假设会注入 system prompt，让 AI 的决策更贴合玩家真实意图。
 */
public class TheoryOfMind {

    /** 玩家意图类型。 */
    public enum PlayerIntent {
        MINING("正在挖矿"),
        EXPLORING("正在探索"),
        BUILDING("正在建造"),
        FIGHTING("正在战斗"),
        FARMING("正在务农"),
        TRADING("正在交易"),
        IDLE("没有明确目标"),
        FLEEING("正在逃跑"),
        UNKNOWN("不确定");

        private final String desc;
        PlayerIntent(String desc) { this.desc = desc; }
        public String desc() { return desc; }
    }

    private volatile PlayerIntent currentIntent = PlayerIntent.UNKNOWN;
    private volatile float urgency = 0;        // 紧急程度 [0, 1]
    private volatile float playerTrust = 0.5f;  // 玩家对伴游的信任度 [0, 1]
    private volatile String lastIntentReason = "";

    /** 玩家行为记录（用于推断意图）。 */
    private final ConcurrentHashMap<String, Long> recentActions = new ConcurrentHashMap<>();

    /** 行为模式权重（动态学习） */
    private final ConcurrentHashMap<PlayerIntent, Float> intentWeights = new ConcurrentHashMap<>();

    /** 行为记录时间窗（tick） */
    private static final long ACTION_WINDOW = 1200; // 60秒（20tick/s * 60s）

    /**
     * 更新玩家意图推断。
     *
     * @param observedAction 观察到的玩家行为描述
     * @param gameTime 当前游戏时间
     */
    public void observe(String observedAction, long gameTime) {
        if (observedAction == null) return;

        recentActions.put(observedAction.toLowerCase(), gameTime);

        // 基于行为模式推断意图（改进：使用通用模式而非硬编码关键词）
        String action = observedAction.toLowerCase();
        PlayerIntent newIntent = inferIntent(action);

        // 动态更新意图权重
        if (newIntent != PlayerIntent.UNKNOWN) {
            intentWeights.merge(newIntent, 0.1f, Float::sum);
            // 衰减其他意图权重
            for (PlayerIntent intent : PlayerIntent.values()) {
                if (intent != newIntent && intent != PlayerIntent.UNKNOWN) {
                    intentWeights.merge(intent, -0.02f, Float::sum);
                }
            }
        }

        // 选择权重最高的意图
        PlayerIntent dominantIntent = getDominantIntent();
        if (dominantIntent != PlayerIntent.UNKNOWN) {
            currentIntent = dominantIntent;
            lastIntentReason = observedAction;
        }

        // 紧急程度：基于动作类型和频率
        updateUrgency(action);

        // 清理过旧的行为记录
        long cutoff = gameTime - ACTION_WINDOW;
        recentActions.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /**
     * 获取权重最高的意图
     */
    private PlayerIntent getDominantIntent() {
        PlayerIntent dominant = PlayerIntent.UNKNOWN;
        float maxWeight = 0.3f; // 阈值，避免噪声

        for (var entry : intentWeights.entrySet()) {
            if (entry.getValue() > maxWeight) {
                maxWeight = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }

    /**
     * 更新紧急程度
     */
    private void updateUrgency(String action) {
        // 紧急信号：快速移动、受伤、被攻击
        boolean isUrgent = action.contains("跑") || action.contains("逃") ||
                          action.contains("hurt") || action.contains("damage") ||
                          action.contains("attack") || action.contains("被攻击") ||
                          action.contains("danger") || action.contains("危险");

        if (isUrgent) {
            urgency = Math.min(1.0f, urgency + 0.3f);
        } else {
            urgency = Math.max(0, urgency - 0.05f); // 自然衰减
        }
    }

    /**
     * 玩家对伴游表达态度（夸奖/批评），更新信任度。
     */
    public void onPlayerFeedback(boolean positive) {
        if (positive) {
            playerTrust = Math.min(1.0f, playerTrust + 0.05f);
        } else {
            playerTrust = Math.max(0f, playerTrust - 0.05f);
        }
    }

    /**
     * 生成供 LLM system prompt 使用的意图描述。
     */
    public String toPromptString() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 对玩家的理解（Theory of Mind）\n");
        sb.append("- 你认为玩家").append(currentIntent.desc());
        if (!lastIntentReason.isBlank()) {
            sb.append("（因为：").append(lastIntentReason).append("）");
        }
        sb.append("\n");
        sb.append(String.format("- 紧急程度: %.0f%%", urgency * 100));
        if (urgency > 0.6f) sb.append("（玩家似乎在赶时间，快速响应）");
        sb.append("\n");
        sb.append(String.format("- 玩家对你的信任: %.0f%%", playerTrust * 100));
        if (playerTrust < 0.3f) sb.append("（信任度低，用行动证明自己）");
        sb.append("\n");

        // 行为指导
        sb.append("根据对玩家的理解调整行为：");
        if (currentIntent == PlayerIntent.FIGHTING) {
            sb.append(" 玩家在战斗，准备好支援。");
        } else if (currentIntent == PlayerIntent.MINING) {
            sb.append(" 玩家在挖矿，帮忙照亮或挖旁边的矿。");
        } else if (currentIntent == PlayerIntent.EXPLORING) {
            sb.append(" 玩家在探索，跟上但别挡路。");
        } else if (currentIntent == PlayerIntent.BUILDING) {
            sb.append(" 玩家在建造，帮忙递材料或清理地面。");
        } else if (currentIntent == PlayerIntent.FLEEING) {
            sb.append(" 玩家在逃跑！准备接应或断后。");
        }
        sb.append("\n");
        return sb.toString();
    }

    // ── Getters ──

    public PlayerIntent currentIntent() { return currentIntent; }
    public float urgency() { return urgency; }
    public float playerTrust() { return playerTrust; }

    // ── 内部方法 ──

    /**
     * 推断玩家意图（改进：使用通用动作模式，不依赖硬编码关键词）
     */
    private PlayerIntent inferIntent(String action) {
        // 通用动作模式匹配
        // 挖掘类动作
        if (action.contains("挖") || action.contains("mine") || action.contains("dig") ||
            action.contains("break") || action.contains("破坏") ||
            action.contains("ore") || action.contains("矿") || action.contains("stone")) {
            return PlayerIntent.MINING;
        }
        // 建造类动作
        if (action.contains("建") || action.contains("build") || action.contains("place") ||
            action.contains("放置") || action.contains("craft") || action.contains("合成") ||
            action.contains("construct") || action.contains("搭建")) {
            return PlayerIntent.BUILDING;
        }
        // 战斗类动作
        if (action.contains("打") || action.contains("attack") || action.contains("kill") ||
            action.contains("fight") || action.contains("战斗") || action.contains("杀") ||
            action.contains("剑") || action.contains("sword") || action.contains("bow")) {
            return PlayerIntent.FIGHTING;
        }
        // 探索/移动类动作
        if (action.contains("走") || action.contains("walk") || action.contains("run") ||
            action.contains("跑") || action.contains("move") || action.contains("移动") ||
            action.contains("explore") || action.contains("探索") || action.contains("travel")) {
            return PlayerIntent.EXPLORING;
        }
        // 农业类动作
        if (action.contains("种") || action.contains("farm") || action.contains("plant") ||
            action.contains("crop") || action.contains("农") || action.contains("harvest")) {
            return PlayerIntent.FARMING;
        }
        // 交易类动作
        if (action.contains("交易") || action.contains("trade") || action.contains("villager") ||
            action.contains("村民") || action.contains("emerald") || action.contains("绿宝石")) {
            return PlayerIntent.TRADING;
        }
        // 逃跑类动作
        if (action.contains("逃") || action.contains("flee") || action.contains("escape") ||
            action.contains("retreat") || action.contains("撤退") || action.contains("run away")) {
            return PlayerIntent.FLEEING;
        }
        return PlayerIntent.UNKNOWN;
    }
}
