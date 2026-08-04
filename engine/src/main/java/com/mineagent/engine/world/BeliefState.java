package com.mineagent.engine.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compact evidence-backed belief state for partially known worlds.
 *
 * <p>A belief is not promoted to a rule after one observation. Supporting and
 * contradicting outcomes are retained separately, allowing the agent to
 * notice changed recipes, drops or modded mechanics instead of endlessly
 * retrying its pretrained assumption.
 */
public final class BeliefState {
    public record Fact(String subject, String predicate, String value,
                       double confidence, String source, long gameTick) {
        public Fact {
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "unknown");
            confidence = unit(confidence);
            source = normalize(source, "observation");
            gameTick = Math.max(0L, gameTick);
        }
    }

    public record RuleBelief(String key, String context, String action,
                             String expectedOutcome, int successes, int failures,
                             String lastEvidence, long lastObservedTick) {
        public RuleBelief {
            key = normalize(key, "rule");
            context = normalize(context, "general");
            action = normalize(action, "unknown_action");
            expectedOutcome = normalize(expectedOutcome, "unknown_outcome");
            successes = Math.max(0, successes);
            failures = Math.max(0, failures);
            lastEvidence = normalize(lastEvidence, "No evidence detail");
            lastObservedTick = Math.max(0L, lastObservedTick);
        }

        /** Beta(1,1) posterior mean avoids false certainty from one sample. */
        public double confidence() {
            return (successes + 1.0) / (successes + failures + 2.0);
        }

        public boolean isContested() {
            return successes > 0 && failures > 0;
        }
    }

    public record State(List<Fact> facts, List<RuleBelief> rules, long revision) {}

    private static final int MAX_FACTS = 512;
    private static final int MAX_RULES = 256;

    private final LinkedHashMap<String, Fact> facts = new LinkedHashMap<>();
    private final LinkedHashMap<String, RuleBelief> rules = new LinkedHashMap<>();
    private long revision;

    public synchronized void observeFact(String subject, String predicate, String value,
                                         double confidence, String source, long gameTick) {
        Fact fact = new Fact(subject, predicate, value, confidence, source, gameTick);
        String key = canonical(subject) + "\u0000" + canonical(predicate);
        Fact existing = facts.get(key);
        if (existing == null || fact.gameTick() >= existing.gameTick()
                || fact.confidence() > existing.confidence()) {
            facts.put(key, fact);
            trimOldest(facts, MAX_FACTS);
            revision++;
        }
    }

    public synchronized void observeRuleOutcome(String context, String action,
                                                String expectedOutcome, boolean success,
                                                String evidence, long gameTick) {
        String key = canonical(context) + "|" + canonical(action)
                + "|" + canonical(expectedOutcome);
        RuleBelief old = rules.get(key);
        int successes = old == null ? 0 : old.successes();
        int failures = old == null ? 0 : old.failures();
        RuleBelief updated = new RuleBelief(key, context, action, expectedOutcome,
                successes + (success ? 1 : 0), failures + (success ? 0 : 1),
                evidence, gameTick);
        rules.put(key, updated);
        trimOldest(rules, MAX_RULES);
        revision++;
    }

    public synchronized String summarizeForPrompt() {
        if (rules.isEmpty() && facts.isEmpty()) return "Beliefs: no durable evidence yet";
        StringBuilder out = new StringBuilder("Evidence-backed beliefs:\n");
        rules.values().stream()
                .sorted(Comparator.comparing(RuleBelief::isContested).reversed()
                        .thenComparingLong(RuleBelief::lastObservedTick).reversed())
                .limit(8)
                .forEach(rule -> out.append("- ")
                        .append(rule.action()).append(" in ").append(rule.context())
                        .append(" -> ").append(rule.expectedOutcome())
                        .append(" confidence=")
                        .append(String.format(Locale.ROOT, "%.2f", rule.confidence()))
                        .append(rule.isContested() ? " CONTESTED" : "")
                        .append(" evidence=").append(rule.lastEvidence()).append('\n'));
        facts.values().stream()
                .sorted(Comparator.comparingLong(Fact::gameTick).reversed())
                .limit(5)
                .forEach(fact -> out.append("- observed ").append(fact.subject())
                        .append(' ').append(fact.predicate()).append(' ')
                        .append(fact.value()).append(" confidence=")
                        .append(String.format(Locale.ROOT, "%.2f", fact.confidence()))
                        .append('\n'));
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(List.copyOf(facts.values()), List.copyOf(rules.values()), revision);
    }

    public synchronized void importState(State state) {
        facts.clear();
        rules.clear();
        if (state == null) return;
        if (state.facts() != null) {
            for (Fact fact : state.facts()) {
                if (fact == null) continue;
                facts.put(canonical(fact.subject()) + "\u0000" + canonical(fact.predicate()), fact);
            }
        }
        if (state.rules() != null) {
            for (RuleBelief rule : state.rules()) {
                if (rule != null) rules.put(rule.key(), rule);
            }
        }
        trimOldest(facts, MAX_FACTS);
        trimOldest(rules, MAX_RULES);
        revision = Math.max(0L, state.revision()) + 1L;
    }

    private static <K, V> void trimOldest(LinkedHashMap<K, V> map, int max) {
        while (map.size() > max) map.remove(map.keySet().iterator().next());
    }

    private static String canonical(String value) {
        return normalize(value, "unknown").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
