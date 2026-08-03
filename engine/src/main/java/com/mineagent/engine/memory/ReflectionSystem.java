package com.mineagent.engine.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection 反思系统 — 从失败中提取教训，指导未来决策。
 *
 * <p>受启于 Reflexion (NeurIPS 2023) 和 Voyager 的自我改进机制：
 * 不是简单地记录"失败了"，而是分析"为什么失败"并生成可操作的教训。
 *
 * <p>三层次反思：
 * <ul>
 *   <li><b>Micro</b>：单次工具调用失败 → 参数修正建议</li>
 *   <li><b>Meso</b>：任务级别失败 → 策略调整建议</li>
 *   <li><b>Macro</b>：行为模式失败 → 决策风格调整</li>
 * </ul>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>移除硬编码关键词: 使用通用模式匹配，不依赖特定词汇</li>
 *   <li>改进失败分类: 基于错误结构而非关键词</li>
 *   <li>动态教训生成: 根据错误上下文生成针对性建议</li>
 * </ul>
 */
public class ReflectionSystem {

    /**
     * 一条反思记录。
     *
     * @param level      反思级别（micro/meso/macro）
     * @param trigger    触发原因（失败的工具/任务描述）
     * @param context    失败时的上下文摘要
     * @param lesson     提取的教训
     * @param timestamp  记录时间
     * @param applicability 适用范围（哪些场景适用此教训）
     */
    public record Reflection(
            String level,
            String trigger,
            String context,
            String lesson,
            long timestamp,
            String applicability
    ) {}

    /** 默认最大反思条数 */
    private static final int DEFAULT_MAX_REFLECTIONS = 30;

    private final Deque<Reflection> reflections = new ArrayDeque<>();
    private final ConcurrentMap<String, Integer> failurePatterns = new ConcurrentHashMap<>();
    private final int maxReflections;

    public ReflectionSystem() {
        this(DEFAULT_MAX_REFLECTIONS);
    }

    public ReflectionSystem(int maxReflections) {
        this.maxReflections = maxReflections;
    }

    /**
     * 记录一次工具调用失败。
     * 改进：使用通用模式匹配，不依赖硬编码关键词。
     */
    public void recordToolFailure(String toolName, String params, String error, String context) {
        String pattern = categorizeFailure(toolName, error);
        failurePatterns.merge(pattern, 1, Integer::sum);

        String lesson = generateMicroLesson(toolName, error, params);
        String trigger = toolName + "(" + truncate(params, 40) + ")";

        addReflection(new Reflection(
                "micro",
                trigger,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                toolName
        ));
    }

    /**
     * 记录一次任务失败。
     */
    public void recordTaskFailure(String taskDescription, String reason, String context) {
        String lesson = generateMesoLesson(taskDescription, reason);

        addReflection(new Reflection(
                "meso",
                taskDescription,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                "task"
        ));
    }

    /**
     * 记录行为模式失败（如反复在同一类任务上失败）。
     */
    public void recordPatternFailure(String pattern, int failureCount, String context) {
        String lesson = "检测到反复失败模式: " + pattern +
                "（" + failureCount + "次）。建议改变策略或请求帮助。";

        addReflection(new Reflection(
                "macro",
                pattern,
                truncate(context, 60),
                lesson,
                System.currentTimeMillis(),
                "pattern"
        ));
    }

    // ─── AgentLoop 兼容方法 ───

    /**
     * 记录失败（AgentLoop 调用）
     * @param taskDesc 任务描述
     * @param toolName 工具名称
     * @param failReason 失败原因
     */
    public void recordFailure(String taskDesc, String toolName, String failReason) {
        recordToolFailure(toolName, taskDesc, failReason, taskDesc);
    }

    /**
     * 记录失败任务（AgentLoop 调用）
     * @param taskDesc 任务描述
     * @param attemptedPlan 尝试的计划
     * @param failReason 失败原因
     */
    public void recordFailedTask(String taskDesc, String attemptedPlan, String failReason) {
        recordTaskFailure(taskDesc, failReason, attemptedPlan);
    }

    /**
     * 生成供 prompt 使用的反思摘要（AgentLoop 调用）。
     * 返回最近的教训（不限匹配）——此前的实现走空串匹配，
     * 导致 micro/meso 级别的教训永远无法进入 prompt。
     * @return 反思摘要文本
     */
    public String summarizeForPrompt() {
        List<Reflection> recent;
        synchronized (reflections) {
            recent = new ArrayList<>(reflections);
        }
        if (recent.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 教训提醒\n");
        // 最近 3 条教训（最新的在最后）
        int start = Math.max(0, recent.size() - 3);
        for (int i = start; i < recent.size(); i++) {
            sb.append("- ").append(recent.get(i).lesson()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化失败记忆供 prompt 使用（AgentLoop 调用）
     * @param currentTask 当前任务描述
     * @return 格式化的失败记忆文本
     */
    public String formatFailuresForPrompt(String currentTask) {
        List<Reflection> relevant = recallRelevantFailures(currentTask, "");
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 相关失败记忆\n");
        for (Reflection r : relevant) {
            sb.append("- 任务: ").append(truncate(r.trigger(), 30))
              .append(" | 教训: ").append(r.lesson()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 添加反思记录。
     * 同步保护：exportAll（持久化线程）可能与本方法并发。
     */
    private void addReflection(Reflection r) {
        synchronized (reflections) {
            reflections.addLast(r);
            while (reflections.size() > maxReflections) {
                reflections.pollFirst();
            }
        }
    }

    /**
     * 获取与当前任务相关的反思。
     * 改进：使用更灵活的匹配，不依赖硬编码关键词。
     */
    public List<Reflection> recallRelevantFailures(String taskDescription, String toolName) {
        List<Reflection> relevant = new ArrayList<>();
        String lowerTask = taskDescription != null ? taskDescription.toLowerCase() : "";

        synchronized (reflections) {
            for (Reflection r : reflections) {
                // null 防御：反序列化或异常路径可能产生缺字段的记录
                if (r.applicability() == null || r.trigger() == null || r.level() == null) {
                    continue;
                }
                // 同类型工具的失败
                if (r.applicability().equals(toolName)) {
                    relevant.add(r);
                }
                // 模式匹配的失败（使用通用匹配而非硬编码关键词）
                else if (isContextMatch(lowerTask, r.trigger().toLowerCase())) {
                    relevant.add(r);
                }
                // macro 级别总是相关
                else if ("macro".equals(r.level())) {
                    relevant.add(r);
                }
            }
        }

        // 最多返回最近 3 条
        return relevant.size() > 3 ?
                new ArrayList<>(relevant.subList(relevant.size() - 3, relevant.size())) :
                relevant;
    }

    /**
     * 生成供 prompt 使用的反思摘要。
     */
    public String summarizeForPrompt(String currentTask) {
        List<Reflection> relevant = recallRelevantFailures(currentTask, "");
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 教训提醒\n");
        for (Reflection r : relevant) {
            sb.append("- ").append(r.lesson()).append("\n");
        }
        return sb.toString();
    }

    // ─── 私有方法 ───

    /**
     * 判断上下文是否匹配（通用模式匹配，不依赖硬编码关键词）
     */
    private boolean isContextMatch(String task, String trigger) {
        // 提取共同词汇（长度>=3的词）
        String[] taskWords = task.split("\\s+");
        for (String word : taskWords) {
            if (word.length() >= 3 && trigger.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 失败模式分类 — 使用通用模式，不依赖硬编码关键词。
     * 基于错误结构而非特定词汇。
     */
    private String categorizeFailure(String toolName, String error) {
        if (error == null) return toolName + ":unknown";
        String lower = error.toLowerCase();

        // 通用错误模式分类（基于错误类型而非关键词）
        // 1. 参数/验证错误
        if (lower.contains("invalid") || lower.contains("illegal") ||
            lower.contains("missing") || lower.contains("required") ||
            lower.contains("null") || lower.contains("empty")) {
            return toolName + ":invalid_params";
        }
        // 2. 资源/对象未找到
        if (lower.contains("not found") || lower.contains("no such") ||
            lower.contains("doesn't exist") || lower.contains("cannot find") ||
            lower.contains("unable to locate")) {
            return toolName + ":not_found";
        }
        // 3. 权限/访问错误
        if (lower.contains("cannot") || lower.contains("can't") ||
            lower.contains("denied") || lower.contains("forbidden") ||
            lower.contains("not allowed") || lower.contains("unreachable")) {
            return toolName + ":access_denied";
        }
        // 4. 超时/性能问题
        if (lower.contains("timeout") || lower.contains("timed out") ||
            lower.contains("too slow") || lower.contains("expired")) {
            return toolName + ":timeout";
        }
        // 5. 状态/条件不满足
        if (lower.contains("already") || lower.contains("not ready") ||
            lower.contains("busy") || lower.contains("locked") ||
            lower.contains("in use")) {
            return toolName + ":state_conflict";
        }

        return toolName + ":unknown";
    }

    /**
     * 生成 Micro 级别教训 — 基于错误类型生成通用建议。
     */
    private String generateMicroLesson(String toolName, String error, String params) {
        String lower = error != null ? error.toLowerCase() : "";

        // 参数错误
        if (lower.contains("invalid") || lower.contains("illegal") ||
            lower.contains("missing") || lower.contains("required")) {
            return "工具 " + toolName + " 参数无效。检查参数格式和必需字段，当前参数: " +
                    truncate(params, 30);
        }
        // 未找到
        if (lower.contains("not found") || lower.contains("no such") ||
            lower.contains("cannot find")) {
            return "未找到目标。使用 look_around 扫描环境，确认目标存在后再尝试 " + toolName + "。";
        }
        // 访问/权限问题
        if (lower.contains("cannot") || lower.contains("can't") ||
            lower.contains("unreachable") || lower.contains("denied")) {
            return "无法执行 " + toolName + "。检查目标是否可达，或尝试移动到更靠近的位置。";
        }
        // 超时
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return toolName + " 操作超时。目标可能太远或环境复杂，考虑分解任务或改变策略。";
        }
        // 状态冲突
        if (lower.contains("already") || lower.contains("busy") ||
            lower.contains("locked")) {
            return "目标状态冲突。等待当前操作完成或选择其他目标。";
        }

        // 通用建议
        return "工具 " + toolName + " 失败: " + truncate(error, 40) +
                "。检查参数并确认目标状态。";
    }

    /**
     * 生成 Meso 级别教训。
     */
    private String generateMesoLesson(String taskDescription, String reason) {
        String lowerReason = reason != null ? reason.toLowerCase() : "";

        // 任务分解问题
        if (lowerReason.contains("too complex") || lowerReason.contains("too big") ||
            lowerReason.contains("overwhelming")) {
            return "任务 '" + truncate(taskDescription, 25) + "' 过于复杂。尝试将其分解为更小的子任务。";
        }
        // 资源不足
        if (lowerReason.contains("lack") || lowerReason.contains("insufficient") ||
            lowerReason.contains("not enough") || lowerReason.contains("missing")) {
            return "资源不足。先收集所需材料，或使用 find_block 定位资源。";
        }
        // 路径/导航问题
        if (lowerReason.contains("stuck") || lowerReason.contains("cannot reach") ||
            lowerReason.contains("blocked") || lowerReason.contains("unreachable")) {
            return "导航受阻。使用 look_around 寻找替代路径，或清理障碍物。";
        }
        // 环境/外部因素
        if (lowerReason.contains("danger") || lowerReason.contains("hostile") ||
            lowerReason.contains("threat")) {
            return "环境危险。先确保安全（消除威胁或寻找安全区域）再继续任务。";
        }

        // 通用建议
        return "任务 '" + truncate(taskDescription, 25) + "' 失败: " +
                truncate(reason, 40) + "。考虑改变策略或分解任务。";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** 获取反思数量 */
    public int size() {
        synchronized (reflections) {
            return reflections.size();
        }
    }

    /** 清空所有反思 */
    public void clear() {
        synchronized (reflections) {
            reflections.clear();
        }
        failurePatterns.clear();
    }

    // ─── 持久化支持 ───

    /**
     * 导出所有反思记录（用于持久化保存）。
     */
    public List<Reflection> exportAll() {
        synchronized (reflections) {
            return new ArrayList<>(reflections);
        }
    }

    /**
     * 导出失败模式统计（用于持久化保存）。
     */
    public ConcurrentMap<String, Integer> exportPatterns() {
        return new ConcurrentHashMap<>(failurePatterns);
    }

    /**
     * 导入反思记录和失败模式（用于持久化恢复），替换当前所有数据。
     */
    public void importAll(List<Reflection> imported, Map<String, Integer> patterns) {
        synchronized (reflections) {
            reflections.clear();
            if (imported != null) {
                for (Reflection r : imported) {
                    // 过滤缺字段的记录（旧版本文件/手工编辑可能缺组件），
                    // 防止召回路径上 trigger/applicability 为 null 引发 NPE
                    if (r != null && r.lesson() != null && r.trigger() != null
                            && r.applicability() != null && r.level() != null) {
                        reflections.addLast(r);
                    }
                }
                while (reflections.size() > maxReflections) {
                    reflections.pollFirst();
                }
            }
        }
        Map<String, Integer> validPatterns = new java.util.HashMap<>();
        if (patterns != null) {
            // Validate before replacing live state. ConcurrentHashMap rejects
            // nulls, and non-positive/corrupt counts have no useful semantic
            // meaning in failure-frequency ranking.
            patterns.forEach((pattern, count) -> {
                if (pattern != null && !pattern.isBlank()
                        && count != null && count > 0) {
                    validPatterns.put(pattern, count);
                }
            });
        }
        failurePatterns.clear();
        failurePatterns.putAll(validPatterns);
    }
}
