package com.mineagent.engine.cognition;

import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class TeamWorkBoardTest {
    private final UUID owner=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    private final SemanticWorldModel world=new SemanticWorldModel();
    private static final PlanGraph.GoalCondition OUTPUT=new PlanGraph.GoalCondition("inventory:mod:output","count","1",PlanGraph.Comparison.AT_LEAST,0.7);
    @BeforeEach void reset() { TeamWorkBoard.clearAll(); }
    @Test void completedIndependentJobsDoNotExhaustTheLifetimeJobBudget() {
        for(int i=0;i<70;i++) {
            assertTrue(TeamWorkBoard.post(owner,a,"work_"+i,"work","machine",List.of(),List.of(OUTPUT),i+1).accepted());
            assertTrue(TeamWorkBoard.claim(owner,a,"work_"+i,i+1).accepted());observe(i+1);
            assertTrue(TeamWorkBoard.complete(owner,a,"work_"+i,world,i+1).accepted());
        }
        assertEquals(64,TeamWorkBoard.jobs(owner,71).size());
    }
    @Test void aWorkerMustVerifyPredecessorsBeforeClaimingTheNextStage() {
        post("supply","stock",List.of());post("process","machine",List.of("supply"));
        assertFalse(TeamWorkBoard.claim(owner,b,"process",1).accepted());
        assertTrue(TeamWorkBoard.claim(owner,a,"supply",2).accepted());
        observe(3);assertTrue(TeamWorkBoard.complete(owner,a,"supply",world,3).accepted());
        assertTrue(TeamWorkBoard.claim(owner,b,"process",4).accepted());
    }
    @Test void aResourceCannotHaveTwoSimultaneousWorkers() {
        post("first","same_machine",List.of());post("second","same_machine",List.of());
        assertTrue(TeamWorkBoard.claim(owner,a,"first",1).accepted());
        assertFalse(TeamWorkBoard.claim(owner,b,"second",1).accepted());
    }
    @Test void workerCannotClaimTwoJobsSimultaneously() {
        post("first","one",List.of());post("second","two",List.of());
        assertTrue(TeamWorkBoard.claim(owner,a,"first",1).accepted());assertFalse(TeamWorkBoard.claim(owner,a,"second",2).accepted());
    }
    @Test void expiredLeasesPermitRecoveryByAnotherWorker() {
        post("first","machine",List.of());assertTrue(TeamWorkBoard.claim(owner,a,"first",1).accepted());
        assertTrue(TeamWorkBoard.claim(owner,b,"first",202).accepted());
    }
    @Test void activeExecutionHeartbeatExtendsTheLease() {
        post("first","machine",List.of());TeamWorkBoard.claim(owner,a,"first",1);
        TeamWorkBoard.heartbeat(owner,a,100,true);assertFalse(TeamWorkBoard.claim(owner,b,"first",250).accepted());
    }
    @Test void idleHeartbeatsDoNotStrandAResource() {
        post("first","machine",List.of());TeamWorkBoard.claim(owner,a,"first",1);
        TeamWorkBoard.heartbeat(owner,a,100,false);assertTrue(TeamWorkBoard.claim(owner,b,"first",202).accepted());
    }
    @Test void staleOrMissingFactsCannotCompleteAJob() {
        post("first","machine",List.of());observe(1);TeamWorkBoard.claim(owner,a,"first",2);
        assertFalse(TeamWorkBoard.complete(owner,a,"first",world,3).accepted());
        observe(4);assertTrue(TeamWorkBoard.complete(owner,a,"first",world,4).accepted());
    }
    @Test void foreignWorkersAndOwnersCannotCompleteSomeoneElsesJob() {
        post("first","machine",List.of());TeamWorkBoard.claim(owner,a,"first",1);observe(2);
        assertFalse(TeamWorkBoard.complete(owner,b,"first",world,2).accepted());
        assertFalse(TeamWorkBoard.complete(UUID.randomUUID(),a,"first",world,2).accepted());
        assertTrue(TeamWorkBoard.jobs(UUID.randomUUID(),2).isEmpty());
    }
    @Test void contractsCannotBeSilentlyRewrittenAndCyclesAreRejected() {
        post("first","machine",List.of());
        assertFalse(TeamWorkBoard.post(owner,b,"first","changed","other",List.of(),List.of(OUTPUT),2).accepted());
        assertFalse(TeamWorkBoard.post(owner,a,"cycle","cycle","machine",List.of("cycle"),List.of(OUTPUT),2).accepted());
    }
    @Test void lossOfWorkerReleasesItsLease() {
        post("first","machine",List.of());TeamWorkBoard.claim(owner,a,"first",1);TeamWorkBoard.remove(owner,a);
        assertTrue(TeamWorkBoard.claim(owner,b,"first",2).accepted());
    }
    @Test void clockRollbackCannotKeepAnOldLeaseAlive() {
        post("first","machine",List.of());TeamWorkBoard.claim(owner,a,"first",100);
        assertTrue(TeamWorkBoard.claim(owner,b,"first",1).accepted());
    }
    @Test void completionReleasesResourceButDoesNotVerifyAnotherJob() {
        post("first","machine",List.of());post("second","machine",List.of("first"));
        TeamWorkBoard.claim(owner,a,"first",1);observe(2);TeamWorkBoard.complete(owner,a,"first",world,2);
        assertEquals(TeamWorkBoard.Status.OPEN,TeamWorkBoard.jobs(owner,2).getLast().status());
        assertTrue(TeamWorkBoard.claim(owner,b,"second",3).accepted());
        assertFalse(TeamWorkBoard.complete(owner,b,"second",world,3).accepted());
    }
    private void post(String id,String resource,List<String> dependencies) {
        var result=TeamWorkBoard.post(owner,a,id,id,resource,dependencies,List.of(OUTPUT),0);assertTrue(result.accepted(),result.message());
    }
    private void observe(long tick) { world.observe(OUTPUT.subject(),OUTPUT.predicate(),"1",null,1,"server",null,tick,100,false); }
}
