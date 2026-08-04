package com.mineagent.engine.skill;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能库 — Voyager 式的可复用动作序列。
 *
 * <p>灵感来自 Voyager (NVIDIA, TMLR 2024) 的技能库设计。
 * 核心思想：成功的动作序列封装成可复用技能，后续遇到相似任务
 * 直接检索执行，零 LLM 调用。
 *
 * <p>技能库越大，LLM 调用越少——这是终身学习的复利效应。
 *
 * <p>每个技能包含：
 * <ul>
 *   <li>名称和描述（用于 embedding 检索）</li>
 *   <li>动作序列（JSON 格式的工具调用序列）</li>
 *   <li>成功率和使用次数</li>
 *   <li>触发条件描述</li>
 * </ul>
 */
public class SkillLibrary {

    /**
     * 一个可复用技能。
     */
    public record Skill(
            String name,
            String description,
            String triggerCondition,
            String actionSequence, // JSON 格式的工具调用序列
            double successRate,
            int invocations,
            long createdTick
    ) {}

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    /** 简单关键词索引：keyword → 技能名列表。 */
    private final Map<String, Set<String>> keywordIndex = new ConcurrentHashMap<>();

    /**
     * 注册一个新技能。如果同名技能已存在且成功率更高，则更新。
     */
    public void register(String name, String description, String triggerCondition,
                          String actionSequence, boolean success) {
        Skill existing = skills.get(name);
        if (existing != null) {
            // 更新成功率和使用次数
            int newInvocations = existing.invocations() + 1;
            double newRate = (existing.successRate() * existing.invocations() + (success ? 1 : 0))
                    / newInvocations;
            Skill updated = new Skill(
                    name, description, triggerCondition, actionSequence,
                    newRate, newInvocations, existing.createdTick());
            skills.put(name, updated);
        } else {
            Skill skill = new Skill(
                    name, description, triggerCondition, actionSequence,
                    success ? 1.0 : 0.0, 1, System.currentTimeMillis());
            skills.put(name, skill);
        }

        // 更新关键词索引
        indexKeywords(name, description + " " + triggerCondition);
    }

    /**
     * 检索与任务描述最匹配的技能。
     *
     * <p>使用简单关键词匹配（无需 embedding 模型）。
     * 命中率高且成功率 >0.7 的技能才会返回。
     *
     * @param taskDescription 任务描述（如 "去挖石头"）
     * @return 匹配的技能列表（按匹配度排序），最多 3 个
     */
    public List<Skill> retrieve(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return Collections.emptyList();
        }

        String lowerDesc = taskDescription.toLowerCase();
        Map<String, Integer> scores = new HashMap<>();

        // 关键词匹配评分
        for (var entry : keywordIndex.entrySet()) {
            String keyword = entry.getKey();
            if (lowerDesc.contains(keyword)) {
                for (String skillName : entry.getValue()) {
                    scores.merge(skillName, keyword.length(), Integer::sum);
                }
            }
        }

        if (scores.isEmpty()) return Collections.emptyList();

        // 按匹配分数排序，取 top-3
        return scores.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3)
                .map(e -> skills.get(e.getKey()))
                .filter(Objects::nonNull)
                .filter(s -> s.successRate() > 0.7) // 只推荐高成功率技能
                .toList();
    }

    /**
     * 标记技能执行结果。
     */
    public void recordResult(String name, boolean success) {
        Skill existing = skills.get(name);
        if (existing != null) {
            int newInvocations = existing.invocations() + 1;
            double newRate = (existing.successRate() * existing.invocations() + (success ? 1 : 0))
                    / newInvocations;
            Skill updated = new Skill(
                    existing.name(), existing.description(), existing.triggerCondition(),
                    existing.actionSequence(), newRate, newInvocations, existing.createdTick());
            skills.put(name, updated);

            // 成功率太低的技能标记为不可推荐（但不删除，保留失败教训）
            // 已经在 retrieve() 中通过 successRate > 0.7 过滤
        }
    }

    /**
     * Register a multi-step skill (a sequence of tool calls).
     *
     * <p>This is the Voyager-style skill library core: when the LLM
     * successfully completes a multi-step task (e.g. "mine 3 iron →
     * smelt → craft pickaxe → equip"), the entire sequence is saved
     * as a reusable skill. Next time a similar task comes up, the LLM
     * can call {@code load_skill} to retrieve and replay it, saving
     * multiple LLM round-trips.
     *
     * @param name skill name (unique key)
     * @param description human-readable description for retrieval
     * @param triggerCondition when to use this skill
     * @param actionSequenceJson JSON array of tool calls, e.g.
     *     {@code [{"tool":"auto_mine","args":"..."},{"tool":"smelt","args":"..."}]}
     * @param success whether the sequence completed successfully
     */
    public void registerSequence(String name, String description, String triggerCondition,
                                   String actionSequenceJson, boolean success) {
        register(name, description, triggerCondition, actionSequenceJson, success);
    }

    /**
     * Retrieve the full action sequence JSON for a skill by name.
     * Used by the load_skill tool to replay a skill.
     *
     * @return the action sequence JSON, or null if skill not found
     */
    public String getActionSequence(String skillName) {
        Skill skill = skills.get(skillName);
        return skill != null ? skill.actionSequence() : null;
    }

    /** Resolve a learned skill through the same name used by load_skill. */
    public Optional<Skill> get(String skillName) {
        return Optional.ofNullable(skills.get(skillName));
    }

    /** Stable snapshot used by memory persistence. */
    public List<Skill> exportAll() {
        return skills.values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    /** Replace the library with validated persisted entries and rebuild its index. */
    public void importAll(Collection<Skill> restored) {
        skills.clear();
        keywordIndex.clear();
        if (restored == null) return;
        for (Skill skill : restored) {
            if (skill == null || skill.name() == null || skill.name().isBlank()
                    || skill.actionSequence() == null || skill.actionSequence().isBlank()) {
                continue;
            }
            Skill normalized = new Skill(skill.name().trim(),
                    skill.description() == null ? "" : skill.description(),
                    skill.triggerCondition() == null ? "" : skill.triggerCondition(),
                    skill.actionSequence(),
                    Math.max(0.0, Math.min(1.0, skill.successRate())),
                    Math.max(1, skill.invocations()), Math.max(0L, skill.createdTick()));
            skills.put(normalized.name(), normalized);
            indexKeywords(normalized.name(), normalized.description() + " "
                    + normalized.triggerCondition());
        }
    }

    /**
     * 生成供 LLM 使用的技能列表摘要。
     */
    public String summarizeForPrompt() {
        if (skills.isEmpty()) return "（暂无已学技能）";

        StringBuilder sb = new StringBuilder();
        sb.append("## 已掌握的技能（可直接复用，无需重新规划）\n");
        for (Skill skill : skills.values()) {
            if (skill.successRate() > 0.5) {
                sb.append("- **").append(skill.name()).append("**: ")
                  .append(skill.description())
                  .append(" (成功率 ").append(String.format("%.0f%%", skill.successRate() * 100))
                  .append(", 使用 ").append(skill.invocations()).append("次)\n");
            }
        }
        sb.append("遇到相似任务时，优先复用已有技能。\n");
        return sb.toString();
    }

    /**
     * 技能库大小。
     */
    public int size() {
        return skills.size();
    }

    /**
     * 返回所有已学习的技能（用于 LLM 查询）。
     */
    public java.util.Collection<Skill> allSkills() {
        return skills.values();
    }

    // ── 内部方法 ──

    private void indexKeywords(String skillName, String text) {
        // 简单分词：按空格和标点分割，取长度 >1 的词
        String[] words = text.toLowerCase().split("[\\s,，。.;；:：!！?？()（）]+");
        for (String word : words) {
            if (word.length() > 1) {
                keywordIndex.computeIfAbsent(word, k -> ConcurrentHashMap.newKeySet()).add(skillName);
            }
        }
    }
}
