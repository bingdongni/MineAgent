package com.mineagent.engine.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillBindings;

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
            "interact_at", "interact_entity", "transfer_items", "transfer_port", "craft", "equip_item");

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
        JsonObject bindings = new JsonObject();
        try {
            JsonObject arguments = com.google.gson.JsonParser.parseString(rule.probeArguments())
                    .getAsJsonObject();
            for (String coordinate : java.util.List.of("x", "y", "z")) {
                if (arguments.has(coordinate)) {
                    bindings.add("target_" + coordinate, arguments.get(coordinate).deepCopy());
                    arguments.add(coordinate, SkillBindings.reference("target_" + coordinate));
                }
            }
            if (arguments.has("entity_id") && arguments.has("expected_uuid")) {
                bindings.add("target_entity_id", arguments.get("entity_id").deepCopy());
                bindings.add("target_uuid", arguments.get("expected_uuid").deepCopy());
                arguments.add("entity_id", SkillBindings.reference("target_entity_id"));
                arguments.add("expected_uuid", SkillBindings.reference("target_uuid"));
            }
            step.add("args", arguments);
        } catch (RuntimeException malformed) {
            step.add("args", new JsonObject());
        }
        JsonArray effects = new JsonArray();
        JsonObject effect = new JsonObject();
        bindings.addProperty("effect_subject", rule.expectedSubject());
        effect.add("subject", SkillBindings.reference("effect_subject"));
        effect.addProperty("predicate", rule.expectedPredicate());
        effect.addProperty("value", rule.expectedValue());
        effect.addProperty("minimum_confidence", 0.7);
        effects.add(effect);
        step.add("expected_effects", effects);
        JsonArray guards = new JsonArray();
        JsonObject validity = new JsonObject();
        validity.addProperty("subject", "mechanism_rule:" + rule.id());
        validity.addProperty("predicate", "reusable");
        validity.addProperty("value", "true");
        validity.addProperty("minimum_confidence", 0.99);
        guards.add(validity);
        for (var condition : rule.prerequisites()) {
            JsonObject guard = new JsonObject();
            if (condition.subject().equals(rule.expectedSubject())) {
                guard.add("subject", SkillBindings.reference("effect_subject"));
            } else {
                guard.addProperty("subject", condition.subject());
            }
            guard.addProperty("predicate", condition.predicate());
            guard.addProperty("value", condition.value());
            guard.addProperty("comparison", condition.comparison().name().toLowerCase(Locale.ROOT));
            guard.addProperty("minimum_confidence", condition.minimumConfidence());
            guards.add(guard);
        }
        step.add("preconditions", guards);
        step.add("bindings", bindings);
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
