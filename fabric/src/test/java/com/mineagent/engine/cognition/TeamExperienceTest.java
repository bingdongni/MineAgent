package com.mineagent.engine.cognition;

import com.mineagent.engine.exploration.*;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class TeamExperienceTest {
    private static final EnvironmentFingerprint ENV=new EnvironmentFingerprint("1.21.1","fabric","mods","registry");
    private final UUID owner=UUID.randomUUID(),teacher=UUID.randomUUID(),learner=UUID.randomUUID();
    @BeforeEach void clear() { TeamWorkBoard.clearAll(); }
    @Test void adoptingAConfirmedTeamRuleCreatesNoLocalEvidenceOrExecutor() {
        var source=rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED);
        assertTrue(TeamWorkBoard.share(owner,teacher,source,ENV,1));
        var hint=TeamWorkBoard.hints(owner,learner,ENV,"mod:machine",2).getFirst();
        var local=new MechanismKnowledgeBase(ENV);var candidate=local.seedCandidate(hint.rule(),2);
        assertNotNull(candidate);assertEquals(0,candidate.supports());assertTrue(candidate.supportingContexts().isEmpty());
        assertEquals(MechanismKnowledgeBase.RuleStatus.CANDIDATE,candidate.status());assertNull(candidate.adapterSkill());
        assertFalse(candidate.reusable(ENV));assertEquals(0.5,candidate.confidence());
        assertNull(local.seedCandidate(hint.rule(),3));assertEquals(1,local.rules().size());
    }
    @Test void sharedHintsCannotOverwriteNegativeLocalEvidence() {
        var local=new MechanismKnowledgeBase(ENV);
        local.importState(new MechanismKnowledgeBase.State(List.of(),List.of(rule(MechanismKnowledgeBase.RuleStatus.REFUTED)),1));
        assertNull(local.seedCandidate(rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED),2));
        assertEquals(MechanismKnowledgeBase.RuleStatus.REFUTED,local.rules().iterator().next().status());
    }
    @Test void hypothesesStayWithinOwnerAndEnvironmentAndExcludeSelf() {
        assertTrue(TeamWorkBoard.share(owner,teacher,rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED),ENV,1));
        assertTrue(TeamWorkBoard.hints(UUID.randomUUID(),learner,ENV,"",2).isEmpty());
        assertTrue(TeamWorkBoard.hints(owner,teacher,ENV,"",2).isEmpty());
        assertTrue(TeamWorkBoard.hints(owner,learner,new EnvironmentFingerprint("1.21.1","fabric","changed","registry"),"",2).isEmpty());
        assertTrue(TeamWorkBoard.hints(owner,learner,ENV,"unrelated fishing",2).isEmpty());
    }
    @Test void unverifiedHintsAreRejectedAndVerifiedHintsExpire() {
        assertFalse(TeamWorkBoard.share(owner,teacher,rule(MechanismKnowledgeBase.RuleStatus.CANDIDATE),ENV,1));
        assertTrue(TeamWorkBoard.share(owner,teacher,rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED),ENV,1));
        assertTrue(TeamWorkBoard.hints(owner,learner,ENV,"",24_002).isEmpty());
    }
    @Test void dataReloadWithdrawsPublishedEvidenceImmediately() {
        var explorer=new MechanismExplorer(new SemanticWorldModel(),(a,b,c,d)->{},null,ENV,null);
        explorer.knowledgeBase().importState(new MechanismKnowledgeBase.State(List.of(),List.of(rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED)),1));
        explorer.onInvalidation(id->TeamWorkBoard.withdraw(owner,teacher,id));
        assertTrue(TeamWorkBoard.share(owner,teacher,rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED),ENV,1));
        explorer.onRulesReloaded(2);assertTrue(TeamWorkBoard.hints(owner,learner,ENV,"",2).isEmpty());
    }
    @Test void memoryResetAlsoWithdrawsAnOldPublishedRule() {
        var explorer=new MechanismExplorer(new SemanticWorldModel(),(a,b,c,d)->{},null,ENV,null);
        explorer.knowledgeBase().importState(new MechanismKnowledgeBase.State(List.of(),List.of(rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED)),1));
        explorer.onInvalidation(id->TeamWorkBoard.withdraw(owner,teacher,id));
        TeamWorkBoard.share(owner,teacher,rule(MechanismKnowledgeBase.RuleStatus.CONFIRMED),ENV,1);
        explorer.importState(null);assertTrue(TeamWorkBoard.hints(owner,learner,ENV,"",2).isEmpty());
    }
    @Test void aTeammateStillNeedsTwoIndependentLocalPhysicalContexts() {
        var teacherWorld=new SemanticWorldModel();var teacherExplorer=new MechanismExplorer(teacherWorld,(a,b,c,d)->{},null,ENV,null);
        probe(teacherExplorer,teacherWorld,1,1);probe(teacherExplorer,teacherWorld,2,4);
        var source=teacherExplorer.knowledgeBase().rules().iterator().next();assertTrue(source.reusable(ENV));
        var ownWorld=new SemanticWorldModel();var ownExplorer=new MechanismExplorer(ownWorld,(a,b,c,d)->{},null,ENV,null);
        var candidate=ownExplorer.seedTeamCandidate(source,10);assertEquals(0,candidate.supports());
        probe(ownExplorer,ownWorld,3,11);
        var one=ownExplorer.knowledgeBase().rules().iterator().next();assertEquals(1,one.supports());assertFalse(one.reusable(ENV));
        probe(ownExplorer,ownWorld,4,14);
        var two=ownExplorer.knowledgeBase().rules().iterator().next();assertEquals(2,two.supports());assertTrue(two.reusable(ENV));
        assertEquals(1,ownExplorer.knowledgeBase().rules().size());
    }
    @Test void resourceGuardRejectsAnotherWorkerUntilRelease() {
        var condition=new com.mineagent.engine.planning.PlanGraph.GoalCondition("inventory:mod:out","count","1",null,0.7);
        assertTrue(TeamWorkBoard.post(owner,teacher,"work","operate machine","block:minecraft:overworld:1,64,2",List.of(),List.of(condition),1).accepted());
        assertTrue(TeamWorkBoard.claim(owner,teacher,"work",2).accepted());
        assertNotNull(TeamWorkBoard.resourceBlocker(owner,learner,"block:minecraft:overworld:1,64,2",2));
        assertNull(TeamWorkBoard.resourceBlocker(owner,teacher,"block:minecraft:overworld:1,64,2",2));
        assertNull(TeamWorkBoard.resourceBlocker(owner,learner,"block:minecraft:overworld:2,64,2",2));
        TeamWorkBoard.remove(owner,teacher);assertNull(TeamWorkBoard.resourceBlocker(owner,learner,"block:minecraft:overworld:1,64,2",3));
    }
    private static void probe(MechanismExplorer explorer,SemanticWorldModel world,int x,long tick) {
        String target="block:minecraft:overworld:"+x+",64,0";
        world.observe(target,"block_id","mod:machine",null,1,"server",null,tick,100,false);
        world.observe(target,"dimension","minecraft:overworld",null,1,"server",null,tick,100,false);
        world.observe(target,"stage","0",null,1,"server",null,tick,100,false);
        String args="{\"x\":"+x+",\"y\":64,\"z\":0}";
        var proposal=explorer.propose("mod:machine","advances an observed stage","interact_at",args,target,"stage","1",MechanismExplorer.Risk.MEDIUM,
                "local-context",true,"close_gui","{}",4,1,tick);
        assertTrue(proposal.accepted(),proposal.message());
        explorer.onToolDispatched("probe-"+x,"interact_at",args,tick);
        world.observe(target,"stage","1",null,1,"server","probe-"+x,tick+1,100,false);
        explorer.onToolResult("probe-"+x,"interact_at",true,false,"{\"success\":true}",tick+1);
    }
    private static MechanismKnowledgeBase.Rule rule(MechanismKnowledgeBase.RuleStatus status) {
        return new MechanismKnowledgeBase.Rule("shared-rule","mod:machine","opens a real port","interact_at","{}","target","state","open",
                "close_gui","{}",Set.of("first","second"),Set.of(),2,0,0,0.75,status,ENV,"learned_adapter",1,List.of());
    }
}
