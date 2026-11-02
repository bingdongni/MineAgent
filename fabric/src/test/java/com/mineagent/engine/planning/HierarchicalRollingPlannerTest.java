package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HierarchicalRollingPlannerTest {
    @Test void prerequisiteGapsWakeOnlyOnChangedDecisionEvidenceAndRearmAfterRecovery() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(new PlanGraph.DraftNode("craft", "craft", "output",
                "medium", List.of(), PlanGraph.NodeStatus.PENDING,
                List.of(new PlanGraph.GoalCondition("inventory:input", "count", "3", PlanGraph.Comparison.AT_LEAST, 0.7)))), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(graph, world);
        planner.onPlanReplaced("produce", 1);
        assertEquals(HierarchicalRollingPlanner.Reason.PREREQUISITE_UNKNOWN, planner.tick(2).reason());
        assertNull(planner.tick(3));
        world.observe("inventory:input", "count", "0", null, 1, "server", null, 4, 100, false);
        var changed = planner.tick(4);
        assertEquals(HierarchicalRollingPlanner.Reason.PREREQUISITE_CHANGED, changed.reason());
        assertEquals("craft", changed.activeStep());
        assertTrue(changed.detail().contains("observed 0"));
        world.observe("inventory:input", "count", "1", null, 1, "server", null, 5, 100, false);
        assertNull(planner.tick(5));
        for (int i = 0; i < 50; i++) world.observe("unrelated:" + i, "x", "changed", null, 1, "server", null, 6, 100, false);
        assertNull(planner.tick(6));
        world.observe("inventory:input", "count", "3", null, 1, "server", null, 7, 100, false);
        assertNull(planner.tick(7));
        world.observe("inventory:input", "count", "0", null, 1, "server", null, 8, 100, false);
        assertEquals(HierarchicalRollingPlanner.Reason.PREREQUISITE_CHANGED, planner.tick(8).reason());
    }

    @Test void expiredEvidenceRequestsObservationRatherThanDeclaringAContradiction() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(new PlanGraph.DraftNode("craft", "craft", "output",
                "medium", List.of(), PlanGraph.NodeStatus.PENDING,
                List.of(new PlanGraph.GoalCondition("inventory:input", "count", "3", PlanGraph.Comparison.AT_LEAST, 0.7)))), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        world.observe("inventory:input", "count", "3", null, 1, "server", null, 1, 4, false);
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(graph, world);
        planner.onPlanReplaced("produce", 1);
        assertNull(planner.tick(1));
        assertEquals(HierarchicalRollingPlanner.Reason.PREREQUISITE_UNKNOWN, planner.tick(20).reason());
        assertNull(planner.tick(21));
    }

    @Test void stalledExecutorProducesOneSignalForUnchangedState() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("collect resources", List.of(new PlanGraph.DraftNode(
                "collect", "collect logs", "inventory increases", "high",
                List.of(), PlanGraph.NodeStatus.PENDING)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(graph, world);
        planner.onPlanReplaced("collect resources", 1L);
        planner.onTaskAccepted("task", "CollectItems",
                TaskSnapshot.running("moving", "moving"), 2L);

        var signal = planner.tick(243L);
        assertNotNull(signal);
        assertEquals(HierarchicalRollingPlanner.Reason.EXECUTOR_STALLED,
                signal.reason());
        assertNull(planner.tick(244L));
    }

    @Test void blockedOnlyWindowRequestsRepair() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("reach target", List.of(new PlanGraph.DraftNode(
                "move", "reach target", "arrive", "high", List.of(),
                PlanGraph.NodeStatus.PENDING)), List.of());
        graph.bindTask("task", "goto", null, 1L);
        graph.recordOutcome("task", TaskState.FAILED,
                TaskSnapshot.running("failed", "failed"), "unreachable", 2L);
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(
                graph, new SemanticWorldModel());
        planner.onPlanReplaced("reach target", 2L);

        assertEquals(HierarchicalRollingPlanner.Reason.ACTIVE_STEP_BLOCKED,
                planner.tick(3L).reason());
    }

    @Test void restoredPlannerNeverRestoresLiveBodyOwnership() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(new PlanGraph.DraftNode(
                "step", "step", "done", "high", List.of(),
                PlanGraph.NodeStatus.PENDING)), List.of());
        HierarchicalRollingPlanner source = new HierarchicalRollingPlanner(
                graph, new SemanticWorldModel());
        source.onPlanReplaced("goal", 1L);
        source.onTaskAccepted("task", "goto", TaskSnapshot.running("move", "move"), 2L);
        HierarchicalRollingPlanner restored = new HierarchicalRollingPlanner(
                graph, new SemanticWorldModel());
        restored.importState(source.exportState());
        assertTrue(restored.summarizeForPrompt().contains("execution=idle"));
    }

    @Test void acceptedSameGoalRepairClearsWindowFailureCounter() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(new PlanGraph.DraftNode(
                "step", "step", "done", "high", List.of(),
                PlanGraph.NodeStatus.PENDING)), List.of());
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(
                graph, new SemanticWorldModel());
        planner.onPlanReplaced("goal", 1L);
        planner.onSynchronousOutcome(false, 2L);
        planner.onSynchronousOutcome(false, 3L);
        assertEquals(HierarchicalRollingPlanner.Reason.REPEATED_FAILURE,
                planner.tick(4L).reason());

        planner.onPlanReplaced("goal", 5L);
        assertNull(planner.tick(6L));
        assertEquals(0, planner.exportState().consecutiveFailures());
    }

    @Test void nullImportClearsStrategicGoalAndRuntimeOwnership() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("persisted goal", List.of(new PlanGraph.DraftNode(
                "step", "step", "done", "high", List.of(),
                PlanGraph.NodeStatus.PENDING)), List.of());
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(
                graph, new SemanticWorldModel());
        planner.onPlanReplaced("persisted goal", 1L);
        planner.onTaskAccepted("task", "goto", TaskSnapshot.running("moving", "moving"), 2L);

        planner.importState(null);

        assertEquals("", planner.exportState().strategicGoal());
        assertTrue(planner.summarizeForPrompt().contains("idle"));
    }
}
