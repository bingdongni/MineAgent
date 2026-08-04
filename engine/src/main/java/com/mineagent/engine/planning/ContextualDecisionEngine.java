package com.mineagent.engine.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic fallback for choosing among already-grounded candidates.
 *
 * <p>There is deliberately no universal factor enum or universal weight
 * formula. The caller names only the factors that exist in the current
 * situation and supplies their current salience. This lets cleanup reason
 * about an escape route and block scarcity without forcing those concepts
 * into combat, dialogue, exploration, or every future decision type. Hard
 * constraints are always applied before comparison. Deliberative LLM planning
 * remains responsible for generating and ranking complex long-horizon plans;
 * this class provides bounded low-level fallback behavior and an audit trail.
 */
public final class ContextualDecisionEngine {
    public record DecisionContext(Map<String, Double> factorSalience,
                                  double uncertaintyCaution) {
        public DecisionContext {
            LinkedHashMap<String, Double> normalized = new LinkedHashMap<>();
            if (factorSalience != null) {
                factorSalience.forEach((factor, value) -> {
                    String key = normalizeFactor(factor);
                    if (key != null && value != null && Double.isFinite(value)
                            && value > 0.0) {
                        normalized.put(key, value);
                    }
                });
            }
            if (normalized.isEmpty()) normalized.put("goal_progress", 1.0);
            double sum = normalized.values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            normalized.replaceAll((factor, value) -> value / sum);
            factorSalience = Map.copyOf(normalized);
            uncertaintyCaution = unit(uncertaintyCaution);
        }
    }

    public record Candidate(String id, Map<String, Double> effects,
                            List<String> hardViolations,
                            double outcomeUncertainty, String rationale) {
        public Candidate {
            id = id == null || id.isBlank() ? "candidate" : id.trim();
            LinkedHashMap<String, Double> normalized = new LinkedHashMap<>();
            if (effects != null) {
                effects.forEach((factor, value) -> {
                    String key = normalizeFactor(factor);
                    if (key != null && value != null && Double.isFinite(value)) {
                        normalized.put(key, signedUnit(value));
                    }
                });
            }
            effects = Map.copyOf(normalized);
            hardViolations = hardViolations == null ? List.of()
                    : hardViolations.stream().filter(Objects::nonNull)
                    .filter(value -> !value.isBlank()).toList();
            outcomeUncertainty = unit(outcomeUncertainty);
            rationale = rationale == null ? "" : rationale.trim();
        }
    }

    public record RankedCandidate(Candidate candidate, double score,
                                  Map<String, Double> appliedSalience) {}

    public record Decision(List<RankedCandidate> feasible,
                           List<Candidate> rejected, String reason) {
        public Candidate selected() {
            return feasible.isEmpty() ? null : feasible.get(0).candidate();
        }
    }

    public Decision decide(DecisionContext context, List<Candidate> candidates) {
        Objects.requireNonNull(context, "context");
        if (candidates == null || candidates.isEmpty()) {
            return new Decision(List.of(), List.of(), "No candidates were supplied");
        }

        List<RankedCandidate> feasible = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null) continue;
            if (!candidate.hardViolations().isEmpty()) {
                rejected.add(candidate);
                continue;
            }
            double score = 0.0;
            for (var factor : context.factorSalience().entrySet()) {
                score += factor.getValue()
                        * candidate.effects().getOrDefault(factor.getKey(), 0.0);
            }
            score -= candidate.outcomeUncertainty()
                    * context.uncertaintyCaution();
            feasible.add(new RankedCandidate(candidate, score,
                    context.factorSalience()));
        }
        feasible.sort(Comparator.comparingDouble(RankedCandidate::score).reversed()
                .thenComparing(value -> value.candidate().id()));
        return new Decision(List.copyOf(feasible), List.copyOf(rejected),
                feasible.isEmpty() ? "Every candidate violated a hard constraint"
                        : "Selected by factors declared salient in this situation");
    }

    private static String normalizeFactor(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double signedUnit(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
