package com.mineagent.engine.exploration;

import com.mineagent.engine.world.SemanticWorldModel;
import java.util.Locale;

/** Deterministic next experiments grounded in recorded terminals, never invented failure causes. */
public record LearningReflection(String experimentId, Kind kind, String evidence,
                                 String nextStep, boolean mechanismCounterexample) {
    public enum Kind { VERIFY_RESULT, REPLICATE, TEST_ALTERNATIVE, OBSERVE, RESTORE_CONDITION,
        INTERFACE_GAP, EXECUTION_FAILURE, INCONCLUSIVE, CANCELLED }

    public static LearningReflection from(MechanismExplorer.Experiment experiment,
                                          SemanticWorldModel world, long tick) {
        String blocker = LearningConditions.blocker(experiment.prerequisites(), world, tick);
        Kind kind;
        String next;
        switch (experiment.status()) {
            case SUPPORTED -> { kind = Kind.REPLICATE;
                next = "Repeat on an independent physical setup with the same conditions; transfer remains conditional."; }
            case REFUTED -> { kind = Kind.TEST_ALTERNATIVE;
                next = "Stop relying on this predicted effect. Contrast one observable condition at a time and test an alternative."; }
            case ABORTED -> { kind = Kind.CANCELLED; next = "Preserve earlier evidence; cancellation supplies no negative mechanism evidence."; }
            case INCONCLUSIVE -> {
                String detail = experiment.evidence().toLowerCase(Locale.ROOT);
                if (blocker != null) {
                    kind = blocker.startsWith("Observe") ? Kind.OBSERVE : Kind.RESTORE_CONDITION;
                    next = blocker;
                } else if (detail.contains("unsupported") || detail.contains("not registered")
                        || detail.contains("unavailable") || detail.contains("unknown menu")) {
                    kind = Kind.INTERFACE_GAP;
                    next = "Inspect observation/action coverage or select a supported interaction; do not repeat the same unavailable operation.";
                } else if (detail.contains("action failed") || detail.contains("task ended")) {
                    kind = Kind.EXECUTION_FAILURE;
                    next = "Repair the executable action or its inputs before drawing a conclusion about the mechanism.";
                } else {
                    kind = Kind.INCONCLUSIVE;
                    next = "Inspect the declared result and its timing/correlation; unchanged or missing observations do not refute a rule.";
                }
            }
            default -> { kind = Kind.VERIFY_RESULT;
                next = "Finish the admitted probe and obtain a fresh declared result before proposing another experiment."; }
        }
        if (experiment.compensationAttempted() && !experiment.compensationSucceeded()) {
            next += " Compensation was unverified; inspect and restore the physical setup before reuse.";
        }
        return new LearningReflection(experiment.id(), kind, experiment.evidence(), next,
                experiment.status() == MechanismExplorer.Status.REFUTED);
    }
}
