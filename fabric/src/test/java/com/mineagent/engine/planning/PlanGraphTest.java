package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlanGraphTest {
    @Test void dependenciesBecomeReadyOnlyAfterVerifiedEvidence() {
        PlanGraph graph = new PlanGraph();
        assertTrue(graph.replacePlan("goal", List.of(
                draft("a", List.of()), draft("b", List.of("a"))), List.of()).accepted());
        assertEquals("a", graph.currentNode().id());
        graph.bindTask("ta", "goto", null, 1L);
        graph.recordOutcome("ta", TaskState.SUCCESS, TaskSnapshot.running("done", "done"),
                "verified", 2L);
        assertEquals("b", graph.currentNode().id());
    }

    @Test void cycleIsRejectedWithoutReplacingExistingPlan() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("valid", List.of(draft("safe", List.of())), List.of());
        var rejected = graph.replacePlan("cycle", List.of(
                draft("a", List.of("b")), draft("b", List.of("a"))), List.of());
        assertFalse(rejected.accepted());
        assertEquals("safe", graph.currentNode().id());
    }

    @Test void blockedNodeDoesNotOccupyIndependentReadyStep() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(
                draft("first", List.of()), draft("independent", List.of())), List.of());
        graph.bindTask("t1", "goto", null, 1L);
        graph.recordOutcome("t1", TaskState.FAILED, TaskSnapshot.running("failed", "failed"),
                "unreachable", 2L);
        assertEquals("independent", graph.currentNode().id());
        assertTrue(graph.hasActivePlan());
    }

    @Test void changedStepMeaningCannotInheritOldSuccessEvidence() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(draft("same-id", List.of())), List.of());
        graph.bindTask("t1", "goto", null, 1L);
        graph.recordOutcome("t1", TaskState.SUCCESS, TaskSnapshot.running("done", "done"),
                "verified", 2L);

        var changed = new PlanGraph.DraftNode("same-id", "different objective",
                "different evidence", "medium", List.of(), PlanGraph.NodeStatus.VERIFIED);
        var update = graph.replacePlan("new goal", List.of(changed), List.of());
        assertTrue(update.accepted());
        assertEquals(PlanGraph.NodeStatus.IN_PROGRESS, graph.currentNode().status());
        assertTrue(graph.currentNode().evidence().isEmpty());
    }

    private static PlanGraph.DraftNode draft(String id, List<String> dependencies) {
        return new PlanGraph.DraftNode(id, id, "executor verifies " + id,
                "medium", dependencies, PlanGraph.NodeStatus.PENDING);
    }
}
