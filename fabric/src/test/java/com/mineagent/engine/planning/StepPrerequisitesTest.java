package com.mineagent.engine.planning;

import com.google.gson.Gson;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StepPrerequisitesTest {
    @Test void whitespaceInStepIdsCannotDetachExecutorEvidenceOrHideDuplicateIds() {
        PlanGraph graph = new PlanGraph();
        assertTrue(graph.replacePlan("goal", List.of(plain(" step ", List.of(), "medium")), List.of()).accepted());
        complete(graph, "step", 1);
        assertEquals(1, graph.exportState().nodes().size());
        assertEquals(100, graph.progressPercent());
        var before = graph.exportState();
        assertFalse(graph.replacePlan("duplicate", List.of(plain(" step", List.of(), "medium"),
                plain("step ", List.of(), "medium")), List.of()).accepted());
        assertEquals(before, graph.exportState());
    }

    @Test void thirtyTwoStepGoalRetainsProgressThroughResourceLossAndCheckpoint() {
        PlanGraph graph = new PlanGraph();
        List<PlanGraph.DraftNode> stages = new ArrayList<>();
        for (int i = 0; i < 32; i++) stages.add(guarded("stage-" + i,
                i == 0 ? List.of() : List.of("stage-" + (i - 1)), PlanGraph.NodeStatus.PENDING));
        graph.replacePlan("long production", stages, List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        HierarchicalRollingPlanner planner = new HierarchicalRollingPlanner(graph, world);
        planner.onPlanReplaced("long production", 1);
        long tick = 2;
        for (int i = 0; i < 32; i++) {
            observe(world, i == 16 ? "0" : "3", tick, 1_000);
            if (i == 16) {
                assertEquals(HierarchicalRollingPlanner.Reason.PREREQUISITE_CHANGED, planner.tick(tick).reason());
                for (int heartbeat = 1; heartbeat <= 50; heartbeat++) assertNull(planner.tick(tick + heartbeat));
                tick += 51;
                observe(world, "3", tick, 1_000);
            }
            assertNull(planner.tick(tick));
            assertNull(graph.actionBlocker(world, tick));
            complete(graph, "stage-" + i, tick);
            tick += 2;
            if (i == 8) {
                Gson gson = new Gson();
                graph.importState(gson.fromJson(gson.toJson(graph.exportState()), PlanGraph.State.class));
                planner.importState(planner.exportState());
            }
        }
        assertEquals(100, graph.progressPercent());
        assertEquals(32, graph.exportState().nodes().size());
        assertTrue(graph.exportState().nodes().stream().allMatch(node -> node.attempts() == 1));
    }

    @Test void distinguishesUnknownCounterexampleAndReadyWithoutTrustingModelStatus() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(guarded("craft", List.of(), PlanGraph.NodeStatus.IN_PROGRESS)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        assertNotNull(graph.actionBlocker(world, 1));
        assertEquals(PlanGraph.PrerequisiteStatus.UNKNOWN, graph.prerequisiteGap().status());
        assertNull(graph.nodeForAction());
        graph.bindTask("unadmitted", "craft", null, 1);
        graph.recordOutcome("unadmitted", TaskState.SUCCESS, null, "claimed success", 1);
        assertEquals(1, graph.exportState().nodes().size());
        assertNotEquals(PlanGraph.NodeStatus.VERIFIED, graph.exportState().nodes().getFirst().status());

        observe(world, "0", 2, 100);
        assertNotNull(graph.actionBlocker(world, 2));
        assertEquals(PlanGraph.PrerequisiteStatus.CONTRADICTED, graph.prerequisiteGap().status());
        observe(world, "3", 3, 100);
        assertNull(graph.actionBlocker(world, 3));
        assertEquals("craft", graph.nodeForAction().id());
    }

    @Test void consumedPrerequisiteDoesNotEraseAnAdmittedActionOrAHistoricalAchievement() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(guarded("craft", List.of(), PlanGraph.NodeStatus.PENDING)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        observe(world, "3", 1, 100);
        assertNull(graph.actionBlocker(world, 1));
        graph.bindTask("craft-task", "craft", null, 1);
        observe(world, "0", 2, 100);
        graph.refreshReadiness(world, 2);
        assertEquals("craft", graph.currentNode().id());
        graph.recordOutcome("craft-task", TaskState.SUCCESS, null, "output verified", 3);
        graph.refreshReadiness(world, 4);
        assertEquals(PlanGraph.NodeStatus.VERIFIED, graph.exportState().nodes().getFirst().status());
        assertEquals(100, graph.progressPercent());
    }

    @Test void synchronousBatchRemainsBoundToItsAdmittedMilestoneAfterInputConsumption() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("prepare", List.of(guarded("prepare", List.of(), PlanGraph.NodeStatus.PENDING)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        observe(world, "3", 1, 100);
        assertNull(graph.actionBlocker(world, 1));
        graph.bindToolCall("first");
        observe(world, "0", 2, 100);
        assertNull(graph.actionBlocker(world, 2));
        graph.bindToolCall("second");
        graph.recordToolOutcome("first", "craft", true, "crafted", 3);
        graph.recordToolOutcome("second", "equip_item", false, "cannot equip", 3);
        assertEquals(PlanGraph.NodeStatus.BLOCKED, graph.exportState().nodes().getFirst().status());
    }

    @Test void repairKeepsHistoricalProgressWhileRestoringAConsumedResourceBeforeContinuing() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(plain("gather", List.of(), "medium"),
                guarded("craft", List.of("gather"), PlanGraph.NodeStatus.PENDING)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        complete(graph, "gather", 1);
        observe(world, "0", 3, 100);
        assertNotNull(graph.actionBlocker(world, 3));
        assertEquals(PlanGraph.NodeStatus.VERIFIED, graph.exportState().nodes().getFirst().status());
        assertTrue(graph.repairPlan("produce", List.of(plain("gather-more", List.of("gather"), "high"),
                guarded("craft", List.of("gather-more"), PlanGraph.NodeStatus.PENDING)), null, null).accepted());
        graph.refreshReadiness(world, 4);
        assertEquals("gather-more", graph.currentNode().id());
        complete(graph, "gather-more", 4);
        observe(world, "3", 6, 100);
        assertNull(graph.actionBlocker(world, 6));
        assertEquals("craft", graph.currentNode().id());
        complete(graph, "craft", 6);
        assertEquals(100, graph.progressPercent());
        assertEquals(1, graph.exportState().nodes().getFirst().attempts());
    }

    @Test void expiredObservationAndCheckpointCannotRestoreStaleAdmission() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(guarded("craft", List.of(), PlanGraph.NodeStatus.PENDING)), List.of());
        SemanticWorldModel world = new SemanticWorldModel();
        observe(world, "3", 1, 4);
        assertNull(graph.actionBlocker(world, 1));
        assertNotNull(graph.actionBlocker(world, 20));
        assertEquals(PlanGraph.PrerequisiteStatus.UNKNOWN, graph.prerequisiteGap().status());
        observe(world, "3", 21, 100);
        assertNull(graph.actionBlocker(world, 21));
        Gson gson = new Gson();
        PlanGraph restored = new PlanGraph();
        restored.importState(gson.fromJson(gson.toJson(graph.exportState()), PlanGraph.State.class));
        assertNull(restored.currentNode());
        assertNotNull(restored.actionBlocker(new SemanticWorldModel(), 22));
        assertEquals(1, restored.exportState().nodes().getFirst().prerequisites().size());
        assertNull(restored.actionBlocker(world, 22));
    }

    @Test void aGuardedBranchDoesNotBlockIndependentReadyWork() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("produce", List.of(guarded("craft", List.of(), PlanGraph.NodeStatus.PENDING),
                plain("scout", List.of(), "low")), List.of());
        assertNull(graph.actionBlocker(new SemanticWorldModel(), 1));
        assertEquals("scout", graph.currentNode().id());
        complete(graph, "scout", 1);
        assertNotNull(graph.actionBlocker(new SemanticWorldModel(), 3));
    }

    @Test void priorityThenCriticalPathThenInsertionOrderSelectsTheReadyWindow() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("long goal", List.of(plain("short", List.of(), "medium"),
                plain("long", List.of(), "medium"), plain("middle", List.of("long"), "medium"),
                plain("end", List.of("middle"), "medium")), List.of());
        assertEquals("long", graph.currentNode().id());
        graph.replacePlan("long goal", List.of(plain("urgent", List.of(), "high"),
                plain("long", List.of(), "medium"), plain("end", List.of("long"), "medium")), List.of());
        assertEquals("urgent", graph.currentNode().id());
    }

    @Test void changedRequirementsAndNewGoalsCannotBorrowOldCompletionOrLateBindings() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("first", List.of(plain("craft", List.of(), "medium")), List.of());
        graph.bindToolCall("late");
        complete(graph, "craft", 1);
        assertTrue(graph.replacePlan("second", List.of(plain("craft", List.of(), "medium")), List.of()).accepted());
        assertFalse(graph.recordToolOutcome("late", "craft", true, "old result", 3));
        assertTrue(graph.currentNode().evidence().isEmpty());
        complete(graph, "craft", 4);
        assertTrue(graph.repairPlan("second", List.of(guarded("craft", List.of(), PlanGraph.NodeStatus.VERIFIED)), null, null).accepted());
        assertNotEquals(PlanGraph.NodeStatus.VERIFIED, graph.exportState().nodes().getFirst().status());
        assertNotNull(graph.actionBlocker(new SemanticWorldModel(), 6));
    }

    @Test void oversizedDraftIsRejectedAtomically() {
        PlanGraph graph = new PlanGraph();
        graph.replacePlan("existing", List.of(plain("one", List.of(), "medium")), List.of());
        var before = graph.exportState();
        List<PlanGraph.DraftNode> large = new ArrayList<>();
        for (int i = 0; i < 65; i++) large.add(plain("n" + i, List.of(), "medium"));
        assertFalse(graph.replacePlan("large", large, null).accepted());
        assertEquals(before, graph.exportState());
    }

    private static PlanGraph.DraftNode guarded(String id, List<String> dependencies, PlanGraph.NodeStatus status) {
        return new PlanGraph.DraftNode(id, id, "output observed", "medium", dependencies, status,
                List.of(new PlanGraph.GoalCondition("inventory:mod:input", "count", "3", PlanGraph.Comparison.AT_LEAST, 0.7)));
    }
    private static PlanGraph.DraftNode plain(String id, List<String> dependencies, String priority) {
        return new PlanGraph.DraftNode(id, id, "output observed", priority, dependencies, PlanGraph.NodeStatus.PENDING);
    }
    private static void observe(SemanticWorldModel world, String value, long tick, long ttl) {
        world.observe("inventory:mod:input", "count", value, null, 1.0, "server", null, tick, ttl, false);
    }
    private static void complete(PlanGraph graph, String id, long tick) {
        assertEquals(id, graph.currentNode().id());
        graph.bindTask(id, "craft", null, tick);
        graph.recordOutcome(id, TaskState.SUCCESS, null, "verified output", tick + 1);
    }
}
