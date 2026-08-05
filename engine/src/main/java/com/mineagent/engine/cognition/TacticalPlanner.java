package com.mineagent.engine.cognition;

import com.mineagent.engine.planning.ContextualDecisionEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Receding-horizon tactical policy for already-observed facts.
 *
 * <p>This is not one universal utility formula. Each frame contributes only
 * the factors that are currently present, hard safety constraints remove
 * impossible choices first, and the remaining alternatives are re-ranked on
 * every observation. Open-ended plan generation remains an LLM responsibility;
 * this policy covers reactions that must complete faster than a network call.
 */
public final class TacticalPlanner {
    private final ContextualDecisionEngine decisions = new ContextualDecisionEngine();

    public TacticalDecision decide(SituationSnapshot frame) {
        double survivalRisk = survivalRisk(frame);
        double ownerRisk = ownerRisk(frame);
        double threatPressure = threatPressure(frame);
        double cohesionNeed = cohesionNeed(frame);
        double informationNeed = informationNeed(frame);
        boolean blocked = frame.task().blocked();
        boolean activeTask = frame.task().active();
        boolean immediateEmergency = immediateEmergency(frame);

        LinkedHashMap<String, Double> salience = new LinkedHashMap<>();
        putPresent(salience, "survival", survivalRisk);
        putPresent(salience, "owner_safety", ownerRisk);
        putPresent(salience, "threat_control", threatPressure);
        putPresent(salience, "team_cohesion", cohesionNeed);
        putPresent(salience, "information_gain", informationNeed);
        if (activeTask) salience.put("goal_progress", blocked ? 0.8 : 1.0);
        if (salience.isEmpty()) salience.put("information_gain", 1.0);

        double uncertaintyCaution = clamp(0.15 + informationNeed * 0.7);
        var context = new ContextualDecisionEngine.DecisionContext(
                salience, uncertaintyCaution);
        List<ContextualDecisionEngine.Candidate> candidates = new ArrayList<>();

        candidates.add(candidate("survive_now", effects(
                        "survival", 1.0, "owner_safety", -0.15,
                        "goal_progress", -0.65, "information_gain", -0.15),
                violations(!immediateEmergency, "no immediate survival emergency"),
                0.05, "Local reflex must first restore a viable body state"));
        candidates.add(candidate("retreat_and_reassess", effects(
                        "survival", 0.85, "threat_control", 0.35,
                        "information_gain", 0.55, "goal_progress", -0.25),
                violations(immediateEmergency,
                        "survival reflex must resolve the immediate emergency first",
                        threatPressure < 0.15 && survivalRisk < 0.35,
                        "no pressure justifies retreat",
                        frame.vitals().armed() && frame.vitals().healthRatio() >= 0.7
                                && frame.immediateThreats().size() == 1
                                && frame.environment().immediateHazards() == 0,
                        "healthy armed actor has a grounded single-threat response"),
                0.2, "Create distance, regain options, then re-evaluate"));
        candidates.add(candidate("defend_owner", effects(
                        "owner_safety", 1.0, "team_cohesion", 0.7,
                        "threat_control", 0.65, "survival", -0.15),
                violations(ownerRisk < 0.15, "owner is not under observed pressure"),
                0.18, "Interrupt threats currently endangering the owner"));
        candidates.add(candidate("engage_priority_threat", effects(
                        "threat_control", 1.0, "owner_safety", 0.55,
                        "team_cohesion", 0.35, "survival", 0.15),
                violations(threatPressure < 0.1, "no grounded threat",
                        frame.threatsToOwner() > 0,
                        "owner-targeting threat requires the defend-owner posture",
                        !frame.vitals().armed(), "no carried combat-capable item",
                        frame.vitals().healthRatio() < 0.35, "health too low to commit"),
                clamp(0.15 + threatPressure * 0.25),
                "Control the most urgent grounded threat"));
        candidates.add(candidate("replan_blocked_goal", effects(
                        "goal_progress", 0.8, "information_gain", 0.85,
                        "survival", 0.2),
                violations(!blocked, "executor has not reported a blocker"),
                0.12, "The old action model is contradicted by executor evidence"));
        candidates.add(candidate("regroup", effects(
                        "team_cohesion", 1.0, "owner_safety", 0.35,
                        "survival", 0.25, "goal_progress", -0.15),
                violations(cohesionNeed < 0.2, "team is already within useful range"),
                0.1, "Restore mutual support and shared perception"));
        candidates.add(candidate("continue_verified_plan", effects(
                        "goal_progress", 1.0, "team_cohesion", 0.15,
                        "survival", 0.05),
                violations(!activeTask, "no active verified body task",
                        blocked, "executor reports the current task is blocked",
                        immediateEmergency, "survival hard constraint is active"),
                0.05, "Let the deterministic executor finish without LLM polling"));
        candidates.add(candidate("observe_before_commit", effects(
                        "information_gain", 1.0, "survival", 0.25,
                        "goal_progress", 0.05),
                violations(immediateEmergency, "observation cannot delay survival"),
                0.03, "Acquire evidence before choosing an irreversible action"));

        ContextualDecisionEngine.Decision ranked = decisions.decide(context, candidates);
        var selected = ranked.feasible().isEmpty() ? null : ranked.feasible().get(0);
        if (selected == null) {
            return new TacticalDecision(TacticalDecision.Posture.OBSERVE_BEFORE_COMMIT,
                    0.1, "all proposed actions violated a hard constraint", List.of(),
                    false, true, frame.gameTick());
        }

        double margin = ranked.feasible().size() < 2 ? 1.0
                : selected.score() - ranked.feasible().get(1).score();
        double confidence = clamp(0.45 + margin * 0.8 - informationNeed * 0.25);
        TacticalDecision.Posture posture = posture(selected.candidate().id());
        List<String> alternatives = ranked.feasible().stream().skip(1).limit(2)
                .map(value -> value.candidate().id() + "="
                        + String.format(Locale.ROOT, "%.2f", value.score()))
                .toList();
        String reason = evidenceReason(frame, posture, selected.candidate().rationale());
        boolean local = posture == TacticalDecision.Posture.SURVIVE_NOW
                || posture == TacticalDecision.Posture.RETREAT_AND_REASSESS
                || posture == TacticalDecision.Posture.DEFEND_OWNER
                || posture == TacticalDecision.Posture.ENGAGE_PRIORITY_THREAT;
        boolean deliberate = posture == TacticalDecision.Posture.REPLAN_BLOCKED_GOAL;
        return new TacticalDecision(posture, confidence, reason, alternatives,
                local, deliberate, frame.gameTick());
    }

    private static double survivalRisk(SituationSnapshot frame) {
        var vitals = frame.vitals();
        double risk = 0.0;
        if (vitals.inWater() && vitals.air() < 160) {
            risk = Math.max(risk, clamp((160.0 - vitals.air()) / 160.0));
        }
        if (vitals.inLava()) risk = 1.0;
        if (vitals.onFire()) risk = Math.max(risk, 0.72);
        if (vitals.fallDistance() > 3.0f) {
            risk = Math.max(risk, clamp((vitals.fallDistance() - 3.0) / 14.0));
        }
        if (!frame.immediateThreats().isEmpty()) {
            risk = Math.max(risk, clamp((1.0 - vitals.healthRatio()) * 0.8
                    + threatPressure(frame) * 0.55));
        }
        if (frame.environment().immediateHazards() > 0) {
            risk = Math.max(risk, Math.min(0.9,
                    0.35 + frame.environment().immediateHazards() * 0.12));
        }
        return clamp(risk);
    }

    private static double ownerRisk(SituationSnapshot frame) {
        if (!frame.owner().present()) return 0.0;
        double direct = Math.min(1.0, frame.threatsToOwner() * 0.38);
        double health = frame.owner().hurt()
                ? (1.0 - frame.owner().healthRatio()) * 0.75 + 0.2 : 0.0;
        return clamp(Math.max(direct, health));
    }

    private static double threatPressure(SituationSnapshot frame) {
        double pressure = 0.0;
        for (var threat : frame.immediateThreats()) {
            double proximity = 1.0 - Math.min(1.0, threat.distance() / 12.0);
            pressure += 0.2 + proximity * 0.35
                    + (threat.targetingSelf() ? 0.2 : 0.0)
                    + (threat.targetingOwner() ? 0.15 : 0.0);
        }
        return clamp(pressure);
    }

    private static double cohesionNeed(SituationSnapshot frame) {
        if (!frame.owner().present() || !Double.isFinite(frame.owner().distance())) return 0.0;
        return frame.owner().distance() <= 8.0 ? 0.0
                : clamp((frame.owner().distance() - 8.0) / 40.0);
    }

    private static double informationNeed(SituationSnapshot frame) {
        double uncertainty = frame.unknownPlayers() > 0 ? 0.5 : 0.15;
        if (!frame.task().active()) uncertainty += 0.2;
        if (frame.task().blocked()) uncertainty += 0.45;
        return clamp(uncertainty);
    }

    private static boolean immediateEmergency(SituationSnapshot frame) {
        var vitals = frame.vitals();
        return vitals.inLava()
                || (vitals.inWater() && vitals.air() < 80)
                || (vitals.onFire() && vitals.healthRatio() < 0.45)
                || vitals.fallDistance() >= 9.0f;
    }

    private static ContextualDecisionEngine.Candidate candidate(
            String id, Map<String, Double> effects, List<String> violations,
            double uncertainty, String rationale) {
        return new ContextualDecisionEngine.Candidate(id, effects, violations,
                uncertainty, rationale);
    }

    private static Map<String, Double> effects(Object... values) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), ((Number) values[i + 1]).doubleValue());
        }
        return result;
    }

    private static List<String> violations(Object... conditionsAndMessages) {
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i + 1 < conditionsAndMessages.length; i += 2) {
            if (Boolean.TRUE.equals(conditionsAndMessages[i])) {
                result.add(String.valueOf(conditionsAndMessages[i + 1]));
            }
        }
        return result;
    }

    private static TacticalDecision.Posture posture(String id) {
        return switch (id) {
            case "survive_now" -> TacticalDecision.Posture.SURVIVE_NOW;
            case "retreat_and_reassess" -> TacticalDecision.Posture.RETREAT_AND_REASSESS;
            case "defend_owner" -> TacticalDecision.Posture.DEFEND_OWNER;
            case "engage_priority_threat" -> TacticalDecision.Posture.ENGAGE_PRIORITY_THREAT;
            case "replan_blocked_goal" -> TacticalDecision.Posture.REPLAN_BLOCKED_GOAL;
            case "regroup" -> TacticalDecision.Posture.REGROUP;
            case "continue_verified_plan" -> TacticalDecision.Posture.CONTINUE_VERIFIED_PLAN;
            default -> TacticalDecision.Posture.OBSERVE_BEFORE_COMMIT;
        };
    }

    private static String evidenceReason(SituationSnapshot frame,
                                         TacticalDecision.Posture posture,
                                         String rationale) {
        return rationale + "; health="
                + Math.round(frame.vitals().healthRatio() * 100.0) + "% air="
                + frame.vitals().air() + " threats=" + frame.immediateThreats().size()
                + " owner_threats=" + frame.threatsToOwner()
                + " task=" + frame.task().stage()
                + (frame.task().blocked() ? " blocked=" + frame.task().blockedReason() : "")
                + " posture=" + posture.name().toLowerCase(Locale.ROOT);
    }

    private static void putPresent(Map<String, Double> factors,
                                   String name, double value) {
        if (Double.isFinite(value) && value > 0.01) factors.put(name, value);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
