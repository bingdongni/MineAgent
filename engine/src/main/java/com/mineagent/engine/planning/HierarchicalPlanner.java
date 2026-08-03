package com.mineagent.engine.planning;

import java.util.*;

/**
 * 分层规划器 — 宏观里程碑 + 微观步骤。
 *
 * <p>灵感来自 HIPLAN: 里程碑级是最佳检索/复用粒度，
 * 比动作级（太上下文相关）和任务级（噪声太多）都好。
 *
 * <p>设计：
 * <ul>
 *   <li><b>宏观里程碑</b>：任务的阶段性目标（如"获取铁矿"→"熔炼铁锭"→"制作铁镐"）</li>
 *   <li><b>微观步骤</b>：每个里程碑的具体执行步骤</li>
 *   <li><b>进度追踪</b>：当前处于哪个里程碑，已完成多少</li>
 *   <li><b>动态重规划</b>：环境变化时从当前里程碑重新规划</li>
 * </ul>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>移除硬编码里程碑模板: 不再提供固定模板，由LLM自主规划</li>
 *   <li>保留通用任务分析: 仅提供任务分析辅助，不预设具体步骤</li>
 *   <li>增强LLM自主权: 所有规划决策由LLM基于当前状态做出</li>
 * </ul>
 */
public class HierarchicalPlanner {

    /** 一个里程碑。 */
    public record Milestone(
            String id,
            String description,
            String successCriteria,
            boolean completed
    ) {}

    /** 当前计划。 */
    private List<Milestone> milestones = new ArrayList<>();
    private int currentMilestoneIndex = 0;
    private String currentTaskDescription = "";
    private long planCreatedAtTick = 0;

    /**
     * 创建新计划。
     *
     * @param taskDescription 任务描述
     * @param milestones      里程碑列表（由LLM生成，不再使用硬编码模板）
     */
    public void createPlan(String taskDescription, List<Milestone> milestones) {
        this.currentTaskDescription = taskDescription;
        this.milestones = new ArrayList<>(milestones);
        this.currentMilestoneIndex = 0;
        this.planCreatedAtTick = System.currentTimeMillis();
    }

    /**
     * 标记当前里程碑完成，前进到下一个。
     *
     * @return true 如果还有后续里程碑
     */
    public boolean advanceMilestone() {
        if (currentMilestoneIndex < milestones.size()) {
            Milestone current = milestones.get(currentMilestoneIndex);
            milestones.set(currentMilestoneIndex, new Milestone(
                    current.id(), current.description(),
                    current.successCriteria(), true));
            currentMilestoneIndex++;
        }
        return currentMilestoneIndex < milestones.size();
    }

    /**
     * 获取当前里程碑。
     */
    public Milestone currentMilestone() {
        if (currentMilestoneIndex < milestones.size()) {
            return milestones.get(currentMilestoneIndex);
        }
        return null;
    }

    /**
     * 计划是否已完成。
     */
    public boolean isComplete() {
        return currentMilestoneIndex >= milestones.size();
    }

    /**
     * 是否有活跃计划。
     */
    public boolean hasActivePlan() {
        return !milestones.isEmpty() && !isComplete();
    }

    /**
     * 清除当前计划。
     */
    public void clearPlan() {
        milestones.clear();
        currentMilestoneIndex = 0;
        currentTaskDescription = "";
    }

    /**
     * 生成供 LLM 使用的计划摘要。
     */
    public String summarizeForPrompt() {
        if (milestones.isEmpty()) {
            return "（当前无计划）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前计划: ").append(currentTaskDescription).append("\n");
        for (int i = 0; i < milestones.size(); i++) {
            Milestone m = milestones.get(i);
            sb.append(i + 1).append(". ");
            if (m.completed()) {
                sb.append("~~").append(m.description()).append("~~ ✓");
            } else if (i == currentMilestoneIndex) {
                sb.append("**").append(m.description()).append("** ← 当前");
            } else {
                sb.append(m.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取计划进度百分比。
     */
    public int progressPercent() {
        if (milestones.isEmpty()) return 0;
        return (int) ((currentMilestoneIndex * 100.0) / milestones.size());
    }

    /**
     * 生成任务分析提示（供LLM规划时参考）。
     * 注意：这不是硬编码模板，而是通用分析框架。
     * LLM应根据当前状态自主生成具体里程碑。
     */
    public static String generateTaskAnalysisPrompt(String task) {
        return """
            请为以下任务制定分层计划：

            任务: %s

            要求：
            1. 将任务分解为2-5个宏观里程碑
            2. 每个里程碑应有明确的成功标准
            3. 里程碑应按逻辑顺序排列
            4. 考虑可能的失败点和备选方案

            请以JSON格式返回里程碑列表：
            [
              {"id": "m1", "description": "里程碑描述", "successCriteria": "成功标准"},
              ...
            ]
            """.formatted(task);
    }

    /**
     * 解析LLM生成的里程碑JSON。
     */
    public static List<Milestone> parseMilestonesFromLLM(String llmResponse) {
        List<Milestone> result = new ArrayList<>();
        // 简单解析：假设LLM返回的是格式化的文本
        // 实际实现应该使用JSON解析库
        String[] lines = llmResponse.split("\n");
        int id = 1;
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("*") || line.matches("^\\d+\\..*")) {
                // 提取描述
                String desc = line.replaceAll("^[-*\\d.\\s]+", "").trim();
                if (!desc.isEmpty()) {
                    result.add(new Milestone("m" + id, desc, "完成: " + desc, false));
                    id++;
                }
            }
        }
        return result;
    }
}
