package com.mineagent.api.agent.skill;

/**
 * A loaded skill — a named Markdown document that teaches the AI how to
 * use a capability or play a mod.
 */
public record SkillInfo(String name, String content) {
    public SkillInfo {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (content == null) throw new IllegalArgumentException("content required");
    }
}
