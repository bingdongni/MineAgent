package com.mineagent.engine.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.engine.skill.SkillLibrary;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles confirmed black-box rules into ordinary verified skills.
 *
 * <p>This class intentionally has no body dispatcher.  Reuse enters through
 * {@code execute_skill}, so SkillRuntime still verifies preconditions/effects
 * one step at a time and AgentLoop still asks the normal scheduler for body
 * ownership.  Invalidated rules have their generated skill removed immediately.
 */
public final class AdaptationRuntime {
    private static final Set<String> ACTION_TOOLS = Set.of(
            "interact_at", "interact_entity", "transfer_items", "craft", "equip_item");

    private final MechanismKnowledgeBase knowledgeBase;
    private final SkillLibrary skillLibrary;

    public AdaptationRuntime(MechanismKnowledgeBase knowledgeBase,
                             SkillLibrary skillLibrary) {
        this.knowledgeBase = Objects.requireNonNull(knowledgeBase, "knowledgeBase");
        this.skillLibrary = skillLibrary;
    }

    public synchronized String synchronize(MechanismKnowledgeBase.Rule rule) {
        if (rule == null || skillLibrary == null) return null;
        if (!rule.reusable(knowledgeBase.currentFingerprint())
                || !ACTION_TOOLS.contains(rule.probeTool())) {
            if (rule.adapterSkill() != null) skillLibrary.remove(rule.adapterSkill());
            return null;
        }
        String skillName = rule.adapterSkill() == null
                ? adapterName(rule) : rule.adapterSkill();
        String sequence = sequence(rule);
        SkillLibrary.Skill existing = skillLibrary.get(skillName).orElse(null);
        if (existing == null || !existing.actionSequence().equals(sequence)) {
            skillLibrary.upsertVerifiedAdaptation(skillName,
                    "Verified adapter for " + rule.subject() + ": " + rule.hypothesis(),
                    rule.subject() + " " + rule.hypothesis(), sequence);
        }
        knowledgeBase.attachAdapter(rule.id(), skillName);
        return skillName;
    }

    public synchronized void rebuild(Collection<MechanismKnowledgeBase.Rule> rules) {
        if (rules == null || skillLibrary == null) return;
        for (MechanismKnowledgeBase.Rule rule : rules) synchronize(rule);
    }

    public synchronized void invalidate(MechanismKnowledgeBase.Rule rule) {
        if (rule != null && rule.adapterSkill() != null && skillLibrary != null) {
            skillLibrary.remove(rule.adapterSkill());
        }
    }

    private static String sequence(MechanismKnowledgeBase.Rule rule) {
        JsonObject step = new JsonObject();
        step.addProperty("tool", rule.probeTool());
        try {
            step.add("args", com.google.gson.JsonParser.parseString(rule.probeArguments())
                    .getAsJsonObject());
        } catch (RuntimeException malformed) {
            step.add("args", new JsonObject());
        }
        JsonArray effects = new JsonArray();
        JsonObject effect = new JsonObject();
        effect.addProperty("subject", rule.expectedSubject());
        effect.addProperty("predicate", rule.expectedPredicate());
        effect.addProperty("value", rule.expectedValue());
        effect.addProperty("minimum_confidence", 0.7);
        effects.add(effect);
        step.add("expected_effects", effects);
        step.addProperty("on_failure", "replan");
        step.addProperty("max_attempts", 1);
        step.addProperty("timeout_ticks", 6400);
        JsonArray sequence = new JsonArray();
        sequence.add(step);
        return sequence.toString();
    }

    private static String adapterName(MechanismKnowledgeBase.Rule rule) {
        String subject = rule.subject().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (subject.length() > 28) subject = subject.substring(0, 28);
        return "mechanism_adapter_" + subject + "_"
                + rule.id().replace("rule-", "");
    }
}
