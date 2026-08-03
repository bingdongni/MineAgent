package com.mineagent.api.agent.skill;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Global registry of skills. Skills are loaded from Markdown files under
 * {@code config/mineagent/skills/} and looked up by name.
 */
public final class SkillRegistry {

    private static final LinkedHashMap<String, SkillInfo> SKILLS = new LinkedHashMap<>();

    private SkillRegistry() {}

    /** Register a skill. */
    public static void register(SkillInfo skill) {
        Objects.requireNonNull(skill, "skill must not be null");
        SKILLS.put(skill.name(), skill);
    }

    /** Look up a skill by name. */
    public static Optional<SkillInfo> get(String name) {
        return Optional.ofNullable(SKILLS.get(name));
    }

    /** All registered skills. */
    public static Collection<SkillInfo> all() {
        return Collections.unmodifiableCollection(SKILLS.values());
    }

    /** Number of registered skills. */
    public static int size() {
        return SKILLS.size();
    }

    /** Remove all registered skills (for testing). */
    public static void clear() {
        SKILLS.clear();
    }
}
