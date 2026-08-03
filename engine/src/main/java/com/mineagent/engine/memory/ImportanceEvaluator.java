package com.mineagent.engine.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态重要性评估器 — 取代硬编码的关键词列表。
 *
 * <p><b>设计理念</b>：不同情境下"重要"的定义不同。
 * 挖矿时钻石重要，战斗时血量重要，建造时材料重要。
 * 不能用固定关键词列表硬性决定。
 *
 * <p><b>动态学习机制</b>（三渠道）：
 * <ol>
 *   <li><b>LLM 自评</b>：LLM 在工具调用结果中可标注 importance 字段，
 *       系统据此学习哪些模式重要</li>
 *   <li><b>情绪反推</b>：触发了强情绪变化的事件自动提升权重
 *       （被攻击→该类事件+1，找到钻石→该类事件+1）</li>
 *   <li><b>失败关联</b>：导致任务失败的工具结果自动标记重要</li>
 * </ol>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>降低衰减率: 从0.01/tick降至0.002/tick，学到的权重保持更久</li>
 *   <li>改进特征提取: 多字段组合提取，减少特征冲突</li>
 *   <li>上下文感知: 结合当前任务类型动态调整重要性判断</li>
 * </ul>
 */
public class ImportanceEvaluator {

    /**
     * 事件特征 → 重要性权重 [0, 1]。
     * 特征 = 工具名 + 结果内容的前N个有意义token。
     */
    private final Map<String, Float> featureWeights = new ConcurrentHashMap<>();

    /** 权重调整步长。每次学习事件 +0.15，衰减 -0.002/tick。 */
    private static final float LEARN_STEP = 0.15f;
    private static final float DECAY_STEP = 0.002f; // 降低衰减率，从0.01降至0.002
    private static final float MAX_WEIGHT = 1.0f;
    private static final float MIN_WEIGHT = 0.0f;
    private static final float DEFAULT_WEIGHT = 0.5f;
    private static final float IMPORTANT_THRESHOLD = 0.65f;

    /** 学习次数统计（用于判断是否已有足够数据）。 */
    private final AtomicInteger learnCount = new AtomicInteger(0);

    /** 当前任务上下文（用于动态调整重要性） */
    private volatile String currentTaskContext = "";

    /**
     * 设置当前任务上下文。
     * 在挖矿任务中，矿石相关特征重要性临时提升。
     */
    public void setTaskContext(String taskDescription) {
        this.currentTaskContext = taskDescription != null ? taskDescription.toLowerCase() : "";
    }

    /**
     * 从工具结果中提取特征。
     * 改进：多字段组合提取，减少特征冲突。
     */
    private String extractFeature(String toolName, String resultContent) {
        if (resultContent == null) resultContent = "";

        // 尝试提取多个字段组合成更精确的特征
        String type = extractJsonField(resultContent, "type");
        String name = extractJsonField(resultContent, "name");
        String block = extractJsonField(resultContent, "block");
        String entity = extractJsonField(resultContent, "entity");
        String item = extractJsonField(resultContent, "item");
        String target = extractJsonField(resultContent, "target");

        // 组合特征：优先使用最具体的字段
        StringBuilder feature = new StringBuilder(toolName).append(":");

        if (block != null && !block.isEmpty()) {
            feature.append("block=").append(block);
        } else if (entity != null && !entity.isEmpty()) {
            feature.append("entity=").append(entity);
        } else if (item != null && !item.isEmpty()) {
            feature.append("item=").append(item);
        } else if (type != null && !type.isEmpty()) {
            feature.append("type=").append(type);
            if (name != null && !name.isEmpty()) {
                feature.append("|name=").append(name);
            }
        } else if (target != null && !target.isEmpty()) {
            feature.append("target=").append(target);
        } else {
            // 无字段匹配：使用结果内容的hash作为兜底特征
            // 取前20个字符的hash，避免长文本导致特征过于具体
            String content = resultContent.length() > 20 ?
                    resultContent.substring(0, 20) : resultContent;
            feature.append("content=").append(content.hashCode() % 1000);
        }

        return feature.toString();
    }

    /** 从 JSON 结果中提取指定字段值。 */
    private String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String key = "\"" + field + "\":\"";
        int idx = json.toLowerCase().indexOf(key);
        if (idx >= 0) {
            int start = idx + key.length();
            int end = json.indexOf('"', start);
            if (end > start) {
                return json.substring(start, end).toLowerCase();
            }
        }
        return null;
    }

    /**
     * 查询某工具结果的重要性权重。
     * 结合当前任务上下文动态调整。
     */
    public float getWeight(String toolName, String resultContent) {
        String feature = extractFeature(toolName, resultContent);
        float baseWeight = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);

        // 上下文感知：如果当前任务与特征相关，临时提升权重
        if (!currentTaskContext.isEmpty()) {
            String lowerFeature = feature.toLowerCase();
            // 任务关键词匹配
            if (isTaskRelevant(lowerFeature)) {
                // 临时提升20%权重
                return Math.min(MAX_WEIGHT, baseWeight * 1.2f);
            }
        }

        return baseWeight;
    }

    /**
     * 判断特征是否与当前任务相关
     */
    private boolean isTaskRelevant(String feature) {
        if (currentTaskContext.isEmpty()) return false;

        // 提取任务关键词
        String[] taskKeywords = currentTaskContext.split("\\s+");
        for (String keyword : taskKeywords) {
            if (keyword.length() >= 3 && feature.contains(keyword)) {
                return true;
            }
        }

        // 常见任务类型匹配
        if (currentTaskContext.contains("挖") || currentTaskContext.contains("mine") ||
            currentTaskContext.contains("采矿")) {
            return feature.contains("ore") || feature.contains("block") ||
                   feature.contains("stone") || feature.contains("diamond") ||
                   feature.contains("iron") || feature.contains("coal");
        }
        if (currentTaskContext.contains("建") || currentTaskContext.contains("build") ||
            currentTaskContext.contains("放置")) {
            return feature.contains("block") || feature.contains("place") ||
                   feature.contains("build") || feature.contains("craft");
        }
        if (currentTaskContext.contains("战斗") || currentTaskContext.contains("fight") ||
            currentTaskContext.contains("攻击")) {
            return feature.contains("entity") || feature.contains("mob") ||
                   feature.contains("damage") || feature.contains("health");
        }

        return false;
    }

    /**
     * 判断某工具结果是否"重要"（权重超过阈值）。
     */
    public boolean isImportant(String toolName, String resultContent) {
        return getWeight(toolName, resultContent) >= IMPORTANT_THRESHOLD;
    }

    /**
     * 通过 LLM 标注学习。
     * LLM 在工具结果中可包含 "importance": "high"/"medium"/"low" 字段。
     */
    public void learnFromLLMAnnotation(String toolName, String resultContent, String importanceLabel) {
        if (importanceLabel == null) return;
        String feature = extractFeature(toolName, resultContent);
        float target;
        switch (importanceLabel.toLowerCase()) {
            case "high", "重要", "critical" -> target = 0.9f;
            case "medium", "中等", "normal" -> target = 0.5f;
            case "low", "低", "trivial" -> target = 0.2f;
            default -> { return; }
        }
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        // 向目标值靠拢（学习率0.3）
        float updated = current + (target - current) * 0.3f;
        featureWeights.put(feature, clamp(updated));
        learnCount.incrementAndGet();
    }

    /**
     * 通过情绪强度反推学习。
     * 如果某事件触发了强情绪变化，提升其特征权重。
     */
    public void learnFromEmotion(String toolName, String resultContent, float emotionDelta) {
        // 阈值说明：EmotionState 的单次情绪调整幅度最大约 0.09
        // （onTaskFailed/onTaskComplete 等单步变化），加上负向 1.5x
        // 放大后最多约 0.135。原先的 0.2f 阈值永远无法达到，会让
        // 整个情绪学习渠道变成死代码。降到 0.05f 与 AgentLoop 中
        // 触发学习的阈值（emotionDelta > 0.05f）保持一致。
        if (Math.abs(emotionDelta) < 0.05f) return;  // 情绪变化太小不学
        String feature = extractFeature(toolName, resultContent);
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        // 情绪强度越大，权重提升越多
        // 注意：单次情绪幅度小（0.05~0.13），直接用作为 boost 会太小，
        // 所以放大 3 倍让学习更明显，但仍受 MAX_WEIGHT 上限约束。
        float boost = Math.abs(emotionDelta) * 3.0f * LEARN_STEP;
        // 负面情绪（被攻击等）提升更多——生存相关更重要
        if (emotionDelta < 0) boost *= 1.5f;
        featureWeights.put(feature, clamp(current + boost));
        learnCount.incrementAndGet();
    }

    /**
     * 通过失败关联学习。
     * 导致任务失败的工具结果自动标记重要。
     */
    public void learnFromFailure(String toolName, String resultContent) {
        String feature = extractFeature(toolName, resultContent);
        float current = featureWeights.getOrDefault(feature, DEFAULT_WEIGHT);
        featureWeights.put(feature, clamp(current + LEARN_STEP));
        learnCount.incrementAndGet();
    }

    /**
     * 定期衰减所有权重（向 0.5 中性靠拢）。
     * 防止某些特征权重永远卡在 1.0。
     * 建议：每 200 tick 调用一次。
     *
     * 注意：衰减率已从0.01降至0.002，学到的权重约5秒才衰减0.01，
     * 使学习成果能保持更久。
     */
    public void decayAll() {
        if (featureWeights.isEmpty()) return;
        featureWeights.replaceAll((k, v) -> {
            if (v > DEFAULT_WEIGHT) return Math.max(DEFAULT_WEIGHT, v - DECAY_STEP);
            if (v < DEFAULT_WEIGHT) return Math.min(DEFAULT_WEIGHT, v + DECAY_STEP);
            return v;
        });
    }

    /**
     * 获取供 system prompt 使用的简要描述。
     * 只列出权重最高的前5个特征，让 LLM 知道 AI "认为"什么重要。
     */
    public String summarizeForPrompt() {
        if (featureWeights.isEmpty() || learnCount.get() < 3) {
            return "";  // 学习数据不足时不注入
        }
        StringBuilder sb = new StringBuilder();
        featureWeights.entrySet().stream()
                .filter(e -> e.getValue() >= IMPORTANT_THRESHOLD)
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> {
                    if (sb.length() > 0) sb.append(", ");
                    // 提取特征的主题部分（去掉工具名前缀）
                    String feature = e.getKey();
                    int colonIdx = feature.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < feature.length() - 1) {
                        sb.append(feature.substring(colonIdx + 1));
                    } else {
                        sb.append(feature);
                    }
                });
        if (sb.length() == 0) return "";
        return "你认为重要的: " + sb;
    }

    public int learnCount() { return learnCount.get(); }
    public int featureCount() { return featureWeights.size(); }

    // ─── 持久化支持 ───

    /**
     * 导出学习到的特征权重（用于持久化保存）。
     */
    public Map<String, Float> exportWeights() {
        return new ConcurrentHashMap<>(featureWeights);
    }

    /**
     * 导入特征权重（用于持久化恢复），替换当前所有数据。
     */
    public void importWeights(Map<String, Float> weights, int savedLearnCount) {
        Map<String, Float> validWeights = new java.util.HashMap<>();
        if (weights != null) {
            // Deserialize into a temporary map first. ConcurrentHashMap rejects
            // nulls, and NaN/Infinity poison comparisons and decay; clearing
            // before validation previously lost all live weights on one bad
            // entry in a manually edited or partially corrupted file.
            weights.forEach((feature, weight) -> {
                if (feature != null && !feature.isBlank() && weight != null
                        && Float.isFinite(weight)) {
                    validWeights.put(feature, clamp(weight));
                }
            });
        }
        featureWeights.clear();
        featureWeights.putAll(validWeights);
        learnCount.set(Math.max(0, savedLearnCount));
    }

    private static float clamp(float v) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, v));
    }
}
