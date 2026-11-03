package com.mineagent.engine.exploration;

import com.google.gson.Gson;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveryReloadBoundaryTest {
    private final SemanticWorldModel world=new SemanticWorldModel();
    private final SkillLibrary skills=new SkillLibrary();
    private final EnvironmentFingerprint env=new EnvironmentFingerprint("1.21.1","fabric","mods","registry");
    private final MechanismExplorer explorer=new MechanismExplorer(world,(a,b,c,d)->{},skills,env,null);

    @Test void verifiedCounterexampleGrantsOneBoundedRecoveryWindow() {
        trial(1,"1",1); trial(2,"1",5); trial(3,"2",9);
        var budget=explorer.exportState().budgets().getFirst();
        assertEquals(8,budget.remaining()); assertEquals(1,budget.recoveryRefills());
        trial(4,"1",13); trial(5,"1",17);
        var rule=explorer.knowledgeBase().rules().iterator().next();
        assertTrue(rule.reusable(env)); assertEquals(2,rule.supports());
        var prediction=explorer.predict("mod:machine","interact_at",args(5),"stage","0","target:5",java.util.List.of(),19);
        assertEquals(2,prediction.independentContexts()); assertFalse(prediction.needsRevalidation());
        assertEquals(0,explorer.exportState().budgets().getFirst().remaining());
        explorer.onAdapterOutcome(rule.adapterSkill(),false,"postcondition missing",20);
        assertEquals(0,explorer.exportState().budgets().getFirst().remaining());
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
    }
    @Test void executionFailureCannotRefillTheLearningBudget() {
        trial(1,"1",1); trial(2,"1",5);
        var proposal=propose(3,9); assertTrue(proposal.accepted());
        explorer.onToolDispatched("failed","interact_at",args(3),9);
        explorer.onToolResult("failed",false,false,"navigation failed",10);
        assertEquals(0,explorer.exportState().budgets().getFirst().remaining());
        assertEquals(0,explorer.exportState().budgets().getFirst().recoveryRefills());
        assertFalse(propose(4,12).accepted());
    }
    @Test void recoveryQuotaSurvivesCheckpointAndReadOnlyQueries() {
        trial(1,"1",1); trial(2,"1",5); trial(3,"2",9);
        Gson gson=new Gson(); explorer.importState(gson.fromJson(gson.toJson(explorer.exportState()),MechanismExplorer.State.class));
        var before=explorer.exportState().budgets();
        for(int i=0;i<100;i++) { explorer.reflect("",11,4); explorer.curriculum("mod:machine",11,5); }
        assertEquals(before,explorer.exportState().budgets());
        assertEquals(1,before.getFirst().recoveryRefills());
    }
    @Test void dataReloadAbortsPendingWorkAndCannotBeUndoneByLateCallbacks() {
        trial(1,"1",1); trial(2,"1",5);
        var rule=explorer.knowledgeBase().rules().iterator().next();
        var proposal=propose(3,9); assertTrue(proposal.accepted());
        explorer.onToolDispatched("pending","interact_at",args(3),9);
        explorer.onRulesReloaded(10);
        explorer.onTaskFinished("pending",TaskState.SUCCESS,"old response",11);
        explorer.onToolResult("pending",true,false,"old response",11);
        assertEquals(MechanismExplorer.Status.ABORTED,explorer.get(proposal.experiment().id()).status());
        assertTrue(explorer.exportState().learning().trials().isEmpty());
        assertTrue(explorer.exportState().budgets().isEmpty());
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
        assertEquals("false",world.find("mechanism_rule:"+rule.id(),"reusable",11).orElseThrow().value());
    }
    @Test void reloadingAnIdleEmptyLearnerDoesNotInventRulesOrOutcomes() {
        explorer.onRulesReloaded(1); explorer.onRulesReloaded(2);
        assertTrue(explorer.all().isEmpty()); assertTrue(explorer.knowledgeBase().rules().isEmpty());
        assertTrue(explorer.exportState().learning().trials().isEmpty());
    }
    @Test void stateChangingProbeCannotUnderstateItsMinimumBudgetCost() {
        observe("target","0",1);
        var result=explorer.propose("mod:machine","changes state","interact_at",args(1),"target","stage","1",
                MechanismExplorer.Risk.MEDIUM,"ctx",true,"close_gui","{}",1,1,1);
        assertTrue(result.accepted()); assertEquals(4,result.experiment().estimatedCost());
        assertEquals(8,explorer.exportState().budgets().getFirst().remaining());
    }
    @Test void futureClockCheckpointDoesNotLockAResetWorldOutOfExploration() {
        explorer.importState(new MechanismExplorer.State(java.util.List.of(),null,
                java.util.List.of(new MechanismExplorer.Budget("mod:machine",0,8,1000,env,1)),0));
        assertTrue(propose(1,1).accepted());
        assertEquals(0,explorer.exportState().budgets().getFirst().recoveryRefills());
    }
    @Test void recommendationCannotBypassTheProbeCountLimitEvenWithUnitsRemaining() {
        for (int i = 0; i < 6; i++) {
            var proposal = explorer.propose("mod:machine", "inspect next observation", "look_around", "{}",
                    "tool:look_around", "outcome", "success", MechanismExplorer.Risk.LOW, i + 1L);
            assertTrue(proposal.accepted()); explorer.abort(proposal.experiment().id(), "fixture cancellation", i + 1L);
        }
        var candidate = new MechanismExplorer.ProbeCandidate("look_around", "{}", MechanismExplorer.Risk.LOW,
                false, null, "{}", 1, 1);
        assertEquals(6, explorer.exportState().budgets().getFirst().remaining());
        assertNull(explorer.recommend(java.util.List.of(candidate), "mod:machine", 10).candidate());
    }

    @Test void aChangedOutcomeCanBeRelearnedWithinTheRecoveryAllowance() {
        trial(1, "1", 1); trial(2, "1", 5); trial(3, "2", 9);
        replacementTrial(4, 13); replacementTrial(5, 17);
        var replacement = explorer.knowledgeBase().rules().stream().filter(rule -> rule.expectedValue().equals("2")).findFirst().orElseThrow();
        assertTrue(replacement.reusable(env));
        var prediction = explorer.predict("mod:machine", "interact_at", args(6), "stage", "0", "target:6", java.util.List.of(), 20);
        assertEquals(2, prediction.independentContexts()); assertFalse(prediction.needsRevalidation());
        assertFalse(prediction.outcomes().containsKey("1"));
        assertEquals(0, explorer.exportState().budgets().getFirst().remaining());
    }

    @Test void observingASupportedAlternativeImmediatelyRevokesTheContradictedOldRule() {
        trial(1, "1", 1); trial(2, "1", 5);
        var old = explorer.knowledgeBase().rules().iterator().next();
        replacementTrial(3, 9);
        assertTrue(skills.get(old.adapterSkill()).isEmpty());
        assertEquals("false", world.find("mechanism_rule:" + old.id(), "reusable", 10).orElseThrow().value());
        assertEquals(1, explorer.exportState().budgets().getFirst().recoveryRefills());
        replacementTrial(4, 13);
        var prediction = explorer.predict("mod:machine", "interact_at", args(5), "stage", "0", "target:5", java.util.List.of(), 16);
        assertEquals(2, prediction.independentContexts()); assertFalse(prediction.needsRevalidation());
    }

    private void replacementTrial(int x, long tick) {
        observe("target:" + x, "0", tick);
        var proposal = explorer.propose("mod:machine", "changed rule produces two", "interact_at", args(x),
                "target:" + x, "stage", "2", MechanismExplorer.Risk.MEDIUM, "fixture", true, "close_gui", "{}", 4, 1, tick);
        assertTrue(proposal.accepted(), proposal.message());
        String call = "replacement-" + x; explorer.onToolDispatched(call, "interact_at", args(x), tick);
        observe("target:" + x, "2", tick + 1); explorer.onToolResult(call, "interact_at", true, false, "{\"success\":true}", tick + 1);
        assertEquals(MechanismExplorer.Status.SUPPORTED, explorer.get(proposal.experiment().id()).status());
    }

    private void trial(int x,String result,long tick) {
        var proposal=propose(x,tick); assertTrue(proposal.accepted(),proposal.message());
        String call="call-"+x; explorer.onToolDispatched(call,"interact_at",args(x),tick);
        observe("target:"+x,result,tick+1); explorer.onToolResult(call,"interact_at",true,false,"{\"success\":true}",tick+1);
    }
    private MechanismExplorer.Proposal propose(int x,long tick) {
        observe("target:"+x,"0",tick);
        return explorer.propose("mod:machine","changes state","interact_at",args(x),"target:"+x,"stage","1",
                MechanismExplorer.Risk.MEDIUM,"ctx",true,"close_gui","{}",4,1,tick);
    }
    private void observe(String subject,String value,long tick) { world.observe(subject,"stage",value,null,1,"server",null,tick,1000,false); }
    private static String args(int x) { return "{\"x\":"+x+",\"y\":64,\"z\":0}"; }
}
