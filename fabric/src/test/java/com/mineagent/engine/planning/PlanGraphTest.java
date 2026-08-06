package com.mineagent.engine.planning;

import com.google.gson.Gson;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;
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

    @Test void synchronousWorldActionCanVerifyCurrentNode() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(draft("craft", List.of())), List.of());
        graph.bindToolCall("call");
        graph.recordToolOutcome("call", "craft", true,
                "inventory contains the recipe output", 4L);
        assertEquals(100, graph.progressPercent());
        assertFalse(graph.hasActivePlan());
    }

    @Test void synchronousCallsBoundTogetherCannotConsumeNextNode() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(
                draft("prepare", List.of()), draft("travel", List.of("prepare"))), List.of());
        graph.bindToolCall("craft-call");
        graph.bindToolCall("equip-call");
        graph.recordToolOutcome("craft-call", "craft", true, "crafted", 1L);
        graph.recordToolOutcome("equip-call", "equip_item", true, "equipped", 1L);

        assertEquals("travel", graph.currentNode().id());
        assertEquals(50, graph.progressPercent());
    }

    @Test void laterSuccessCannotHideFailureInSameSynchronousBatch() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("goal", List.of(draft("prepare", List.of())), List.of());
        graph.bindToolCall("failed-call");
        graph.bindToolCall("successful-call");
        graph.recordToolOutcome("failed-call", "craft", false, "missing input", 1L);
        graph.recordToolOutcome("successful-call", "equip_item", true, "equipped", 1L);
        assertTrue(graph.hasActivePlan());
        assertEquals(PlanGraph.NodeStatus.BLOCKED,
                graph.exportState().nodes().getFirst().status());
    }

    @Test void identicalBlockedHeartbeatDoesNotAdvanceRevision() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("reach target", List.of(draft("move", List.of())), List.of());
        graph.bindTask("task", "goto", null, 1L);
        TaskSnapshot blocked = TaskSnapshot.progress("blocked", "waiting at wall",
                0L, 1L, null, null, null, "solid wall", "same evidence", 7L);

        graph.recordProgress("task", blocked, 2L);
        long revision = graph.exportState().revision();
        graph.recordProgress("task", blocked, 3L);

        assertEquals(revision, graph.exportState().revision());
    }

    @Test void suffixRepairRetainsVerifiedCheckpointAndItsDependencyIdentity() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("survive", List.of(
                draft("gather", List.of()), draft("old-route", List.of("gather"))),
                List.of());
        graph.bindTask("gather-task", "auto_mine", null, 1L);
        graph.recordOutcome("gather-task", TaskState.SUCCESS,
                TaskSnapshot.running("done", "done"), "logs acquired", 2L);
        graph.bindTask("route-task", "goto", null, 3L);
        graph.recordOutcome("route-task", TaskState.FAILED,
                TaskSnapshot.running("failed", "failed"), "route blocked", 4L);

        var repair = graph.repairPlan("survive", List.of(new PlanGraph.DraftNode(
                "new-route", "use another route", "arrive", "high",
                List.of("gather"), PlanGraph.NodeStatus.PENDING)), null, null);

        assertTrue(repair.accepted());
        assertEquals(List.of("gather", "new-route"), graph.exportState().nodes()
                .stream().map(PlanGraph.PlanNode::id).toList());
        assertEquals(PlanGraph.NodeStatus.VERIFIED,
                graph.exportState().nodes().getFirst().status());
        assertEquals("new-route", graph.currentNode().id());
    }

    @Test void recoveryActionBindsBackToBlockedMilestone() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("reach", List.of(draft("route", List.of())), List.of());
        graph.bindTask("first", "goto", null, 1L);
        graph.recordOutcome("first", TaskState.FAILED,
                TaskSnapshot.running("failed", "failed"), "wall", 2L);

        graph.bindTask("alternative", "goto", null, 3L);
        graph.recordOutcome("alternative", TaskState.SUCCESS,
                TaskSnapshot.running("done", "done"), "arrived", 4L);

        assertEquals(1, graph.exportState().nodes().size());
        assertEquals(PlanGraph.NodeStatus.VERIFIED,
                graph.exportState().nodes().getFirst().status());
    }

    @Test void explicitGoalConditionPreventsPrematureStrategicCompletion() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("obtain iron", List.of(draft("smelt", List.of())), List.of(),
                List.of(new PlanGraph.GoalCondition("inventory:minecraft:iron_ingot",
                        "count", "3", PlanGraph.Comparison.AT_LEAST, 0.9)));
        graph.bindTask("task", "wait_for", null, 1L);
        graph.recordOutcome("task", TaskState.SUCCESS,
                TaskSnapshot.running("done", "done"), "machine completed", 2L);
        assertTrue(graph.hasActivePlan());
        assertEquals(PlanGraph.GoalStatus.VERIFYING, graph.goalStatus());

        SemanticWorldModel world = new SemanticWorldModel();
        world.observe("inventory:minecraft:iron_ingot", "count", "3", null,
                1.0, "test", null, 3L, 40L, false);
        assertTrue(graph.verifyGoal(world, 3L));
        assertEquals(PlanGraph.GoalStatus.VERIFIED, graph.goalStatus());
        assertFalse(graph.hasActivePlan());
    }

    @Test void legacyPlanStateWithoutV10FieldsRestoresWithSafeDefaults() {
        PlanGraph.State legacy = new Gson().fromJson("""
                {"goal":"legacy","nodes":[],"constraints":[],"revision":7}
                """, PlanGraph.State.class);

        PlanGraph graph = new PlanGraph();
        graph.importState(legacy);

        assertEquals("legacy", graph.exportState().goal());
        assertEquals(List.of(), graph.goalConditions());
        assertEquals(PlanGraph.GoalStatus.UNPLANNED, graph.goalStatus());
        assertEquals(8L, graph.exportState().revision());
    }

    private static PlanGraph.DraftNode draft(String id, List<String> dependencies) {
        return new PlanGraph.DraftNode(id, id, "executor verifies " + id,
                "medium", dependencies, PlanGraph.NodeStatus.PENDING);
    }
}
