package com.mineagent.engine.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActionMetricsLifecycleTest {
    @Test void pendingAndQueriesAreNotFailedActionsAndLateCallbacksAreIdempotent() {
        CognitionMetrics metrics = new CognitionMetrics();
        metrics.recordToolCall();
        metrics.recordToolCall();
        metrics.recordActionDispatched("async");
        assertEquals(0, metrics.snapshot().actions());
        assertEquals(0, metrics.snapshot().failedActions());
        assertEquals(1, metrics.operations().pendingActions());
        assertTrue(metrics.recordActionOutcome("async", CognitionMetrics.ActionOutcome.SUCCEEDED, null));
        assertFalse(metrics.recordActionOutcome("async", CognitionMetrics.ActionOutcome.FAILED, "late"));
        metrics.recordActionDispatched("async");
        assertEquals(0, metrics.operations().pendingActions());
        assertEquals(2, metrics.operations().toolCalls());
        assertEquals(1, metrics.snapshot().successfulActions());
        assertEquals(1.0, metrics.snapshot().actionSuccessRate());
        assertTrue(metrics.snapshot().failureKinds().isEmpty());
    }

    @Test void cancellationAndMissingEvidenceHaveSeparateCounters() {
        CognitionMetrics metrics = new CognitionMetrics();
        metrics.recordActionDispatched("cancelled");
        metrics.recordActionOutcome("cancelled", CognitionMetrics.ActionOutcome.CANCELLED, "owner");
        metrics.recordActionOutcome("unknown", CognitionMetrics.ActionOutcome.UNVERIFIED, "timeout");
        metrics.recordActionOutcome("bad", CognitionMetrics.ActionOutcome.INVALID, "invalid_arguments");
        assertEquals(1, metrics.operations().cancelledActions());
        assertEquals(1, metrics.operations().unverifiedActions());
        assertEquals(1, metrics.snapshot().actions());
        assertEquals(1, metrics.snapshot().failedActions());
        assertEquals(1, metrics.snapshot().invalidActions());
    }
}
