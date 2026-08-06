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
}
