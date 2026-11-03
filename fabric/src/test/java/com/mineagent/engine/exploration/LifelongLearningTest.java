package com.mineagent.engine.exploration;

import com.google.gson.Gson;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.memory.*;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class LifelongLearningTest {
    private static final EnvironmentFingerprint ENV=new EnvironmentFingerprint("1.21.1","fabric","mods","ids");
    @Test void coldArchiveSurvivesActiveRulePressure() {
        var kb=knowledge(300);
        assertEquals(256,kb.rules().size());assertEquals(300,kb.archivedRules().size());
        assertTrue(kb.rules().stream().noneMatch(r->r.id().equals("case-0")));
        assertTrue(kb.restoreArchived("case-0").isPresent());
        assertEquals(256,kb.rules().size());assertTrue(kb.rules().stream().anyMatch(r->r.id().equals("case-0")));
    }
    @Test void archiveRoundTripRetainsOldTasksWithoutActivatingEveryExecutor() {
        var kb=knowledge(300);var state=new Gson().fromJson(new Gson().toJson(kb.exportState()),MechanismKnowledgeBase.State.class);
        var restored=new MechanismKnowledgeBase(ENV);restored.importState(state);
        assertEquals(300,restored.archivedRules().size());assertEquals(256,restored.rules().size());
        assertTrue(restored.restoreArchived("case-0").orElseThrow().reusable(ENV));
    }
    @Test void reloadCannotResurrectAColdConfirmedRule() {
        var kb=knowledge(300);kb.invalidateAll();
        assertTrue(kb.restoreArchived("case-0").isEmpty());
        assertEquals(300,kb.archivedRules().size());
        assertTrue(kb.archivedRules().stream().noneMatch(r->r.reusable(ENV)));
    }
    @Test void environmentChangesRequireNewEvidenceForColdStorage() {
        var kb=knowledge(300);var other=new MechanismKnowledgeBase(new EnvironmentFingerprint("1.21.1","fabric","changed","ids"));
        other.importState(kb.exportState());assertTrue(other.restoreArchived("case-0").isEmpty());
    }
    @Test void legacyContextCannotPromoteArchivedConfirmations() {
        var kb=knowledge(300);var s=kb.exportState();
        kb.importState(new MechanismKnowledgeBase.State(s.profiles(),s.rules(),s.revision(),1,s.archive()));
        assertTrue(kb.restoreArchived("case-0").isEmpty());
        assertTrue(kb.archivedRules().stream().allMatch(r->r.supports()==0));
    }
    @Test void archiveRetentionProtectsRareTasksAgainstARepeatedFamily() {
        var rules=new ArrayList<MechanismKnowledgeBase.Rule>();rules.add(rule(0,"mod:rare"));
        for(int i=1;i<=1_100;i++)rules.add(rule(i,"mod:common"));
        var kb=new MechanismKnowledgeBase(ENV);kb.importState(new MechanismKnowledgeBase.State(List.of(),rules,0));
        assertEquals(1_024,kb.archivedRules().size());
        assertTrue(kb.archivedRules().stream().anyMatch(r->r.subject().equals("mod:rare")));
    }
    @Test void archiveRecallIsRelevantAndDoesNotActivateByReading() {
        var kb=knowledge(300);var before=kb.rules();
        assertTrue(kb.recallArchived("unrelated fishing ocean",8).isEmpty());
        assertEquals(before,kb.rules());
        assertTrue(kb.recallArchived("mod:machine",2).size()<=2);
    }
    @Test void timelinesDistinguishVerifiedActionsFromGoalAcceptance() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);
        memory.outcome("one","smelt steel",true,"actual ingot observed",2);
        String view=memory.render("steel","world",500);
        assertTrue(view.contains("verified_action"));assertTrue(view.contains("status=ACTIVE"));
        assertFalse(view.contains("status=VERIFIED"));
    }
    @Test void repeatedTerminalCallbacksDoNotDuplicateMilestones() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);
        memory.outcome("same","smelt",true,"observed",2);memory.outcome("same","smelt",true,"observed",3);
        assertEquals(1,memory.exportState().episodes().getFirst().milestones().size());
    }
    @Test void restoredActiveTasksBecomeInterruptedAndCannotHoldOwnership() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);
        var restored=new TaskEpisodeMemory();restored.importState(new Gson().fromJson(new Gson().toJson(memory.exportState()),TaskEpisodeMemory.State.class));
        assertEquals("",restored.currentGoal());assertEquals(TaskEpisodeMemory.Status.INTERRUPTED,restored.exportState().episodes().getFirst().status());
    }
    @Test void timelineCannotLeakAcrossEnvironmentsOrIrrelevantTasks() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);memory.outcome("one","smelt steel",true,"ingot",2);
        assertEquals("",memory.render("steel","other world",1_000));
        assertEquals("",memory.render("ocean fishing","world",1_000));
    }
    @Test void exactTextBudgetIsRespectedEvenForFirstOversizedTask() {
        var memory=new TaskEpisodeMemory();memory.begin("steel "+"x".repeat(500),"world",1);
        assertTrue(memory.render("steel","world",100).length()<=100);
    }
    @Test void successfulTaskNeedsFreshWorldGoalEvidence() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);
        var world=new SemanticWorldModel();var graph=completedPlan();
        assertFalse(memory.verify(graph.exportState(),world,2));
        world.observe("inventory:mod:steel","count","1",null,1,"server",null,3,20,false);
        assertTrue(memory.verify(graph.exportState(),world,3));
        assertEquals(TaskEpisodeMemory.Status.VERIFIED,memory.exportState().episodes().getFirst().status());
        assertFalse(memory.verify(graph.exportState(),world,3));
    }
    @Test void timelineReadsNeverChangeEvidenceOrTimestamps() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",1);memory.outcome("one","steel",true,"ingot",2);
        var before=memory.exportState();for(int i=0;i<100;i++)memory.render("steel","world",500);
        assertEquals(before,memory.exportState());
    }
    @Test void aNewTaskCannotBeVerifiedByAnObservationFromBeforeItsStart() {
        var memory=new TaskEpisodeMemory();memory.begin("steel production","world",10);
        var world=new SemanticWorldModel();world.observe("inventory:mod:steel","count","1",null,1,"server",null,1,100,false);
        assertFalse(memory.verify(completedPlan().exportState(),world,11));
        world.observe("inventory:mod:steel","count","1",null,1,"server",null,12,100,false);
        assertTrue(memory.verify(completedPlan().exportState(),world,12));
    }
    @Test void repeatedNewTasksDoNotEvictTheOnlyRareTaskFirst() {
        var memory=new TaskEpisodeMemory();memory.begin("rare mining task","world",1);
        for(int i=0;i<150;i++)memory.begin("common task "+(i%2),"world",i+2);
        assertEquals(128,memory.exportState().episodes().size());
        assertTrue(memory.exportState().episodes().stream().anyMatch(e->e.goal().equals("rare mining task")));
    }
    @Test void memoryRecallDoesNotExceedBudgetWithAnOversizedFirstEntry() {
        var memory=new LayeredMemory();memory.remember(LayeredMemory.Tier.SEMANTIC,"steel "+"key".repeat(300),"v".repeat(900),"server",1,1,0,"world","dimension");
        assertTrue(memory.recall("steel",2,10).isEmpty());
    }
    private static MechanismKnowledgeBase knowledge(int count) {
        var rules=new ArrayList<MechanismKnowledgeBase.Rule>();for(int i=0;i<count;i++)rules.add(rule(i,"mod:machine"));
        var kb=new MechanismKnowledgeBase(ENV);kb.importState(new MechanismKnowledgeBase.State(List.of(),rules,0));return kb;
    }
    private static MechanismKnowledgeBase.Rule rule(int i,String subject) {
        return new MechanismKnowledgeBase.Rule("case-"+i,subject,"observed stage "+i,"interact_at","{\"x\":"+i+",\"y\":64,\"z\":0}",
                "block:minecraft:overworld:"+i+",64,0","stage","1","close_gui","{}",Set.of("a-"+i,"b-"+i),Set.of(),
                2,0,0,0.75,MechanismKnowledgeBase.RuleStatus.CONFIRMED,ENV,null,i,List.of());
    }
    private static PlanGraph completedPlan() {
        var graph=new PlanGraph();var condition=new PlanGraph.GoalCondition("inventory:mod:steel","count","1",PlanGraph.Comparison.AT_LEAST,0.7);
        graph.replacePlan("steel production",List.of(new PlanGraph.DraftNode("make","make steel","ingot","medium",List.of(),PlanGraph.NodeStatus.PENDING)),List.of(),List.of(condition));
        graph.bindTask("exec","craft",null,1);graph.recordOutcome("exec",TaskState.SUCCESS,null,"verified",2);return graph;
    }
}
