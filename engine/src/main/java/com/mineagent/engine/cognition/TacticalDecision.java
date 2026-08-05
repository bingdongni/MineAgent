package com.mineagent.engine.cognition;

import java.util.List;
import java.util.Locale;

/** A bounded tactical choice backed by the current evidence frame. */
public record TacticalDecision(Posture posture, double confidence,
                               String reason, List<String> alternatives,
                               boolean localControlRequired,
                               boolean deliberationRequired, long gameTick) {
    public enum Posture {
        SURVIVE_NOW,
        RETREAT_AND_REASSESS,
        DEFEND_OWNER,
        ENGAGE_PRIORITY_THREAT,
        REPLAN_BLOCKED_GOAL,
        REGROUP,
        CONTINUE_VERIFIED_PLAN,
        OBSERVE_BEFORE_COMMIT
    }

    public TacticalDecision {
        posture = posture == null ? Posture.OBSERVE_BEFORE_COMMIT : posture;
        confidence = Double.isFinite(confidence)
                ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0;
        reason = reason == null || reason.isBlank()
                ? "insufficient evidence" : reason.trim();
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        gameTick = Math.max(0L, gameTick);
    }

    public String compact() {
        return posture.name().toLowerCase(Locale.ROOT)
                + " confidence=" + String.format(Locale.ROOT, "%.2f", confidence)
                + " reason=" + reason;
    }

    public static TacticalDecision initial() {
        return new TacticalDecision(Posture.OBSERVE_BEFORE_COMMIT, 0.0,
                "no server observation yet", List.of(), false, false, 0L);
    }
}
