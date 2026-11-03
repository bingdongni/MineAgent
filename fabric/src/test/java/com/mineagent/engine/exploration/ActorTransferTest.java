package com.mineagent.engine.exploration;

import com.google.gson.JsonObject;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ActorTransferTest {
    private final SemanticWorldModel world=new SemanticWorldModel();
    private final SkillLibrary skills=new SkillLibrary();
    private final MechanismExplorer explorer=new MechanismExplorer(world,(a,b,c,d)->{},skills,
            new EnvironmentFingerprint("1.21.1","fabric","mods","ids"),null);
    @Test void independentActorsLearnOneTypedContractAndBindTheNewIdAndUuid() {
        support(1,1); support(2,5); observe(3,20,10);
        var rule=explorer.knowledgeBase().rules().iterator().next();
        assertEquals(1,explorer.knowledgeBase().rules().size()); assertEquals(2,rule.supports());
        var transfer=explorer.transfer(rule.id(),subject(3),"minecraft:overworld",10);
        assertTrue(transfer.eligible(),transfer.message());
        var bindings=transfer.overrides().getAsJsonObject("bindings");
        assertEquals(43,bindings.get("target_entity_id").getAsInt()); assertEquals(uuid(3),bindings.get("target_uuid").getAsString());
        var runtime=new SkillRuntime(skills,world,(id,tool,args)->{
            assertEquals(43,args.get("entity_id").getAsInt()); assertEquals(uuid(3),args.get("expected_uuid").getAsString());
            observe(3,18,11); return new SkillRuntime.DispatchResult(true,false,true,"accepted");
        },ignored->{});
        assertTrue(runtime.start(transfer.skillName(),transfer.overrides(),10).accepted()); runtime.tick(11); runtime.tick(12);
        assertEquals(SkillRuntime.Status.SUCCEEDED,runtime.snapshot().status());
    }
    @Test void sameDisplayNameDoesNotAuthorizeADifferentActorType() {
        support(1,1); support(2,5); observe(3,20,10);
        world.observe(subject(3),"entity_type","other:npc",null,1,"inspect_entity",null,10,80,false);
        var rule=explorer.knowledgeBase().rules().iterator().next();
        assertFalse(explorer.transfer(rule.id(),subject(3),"minecraft:overworld",10).eligible());
    }
    @Test void mismatchedObservedIdRejectsBeforeSpendingProbeBudget() {
        observe(1,20,1); var args=new JsonObject(); args.addProperty("entity_id",999); args.addProperty("button","use");
        var result=explorer.propose("mod:npc","interaction changes health","interact_entity",args.toString(),subject(1),"health","18.0",
                MechanismExplorer.Risk.MEDIUM,"fixture",true,"close_gui","{}",4,0.5,1);
        assertFalse(result.accepted()); assertTrue(explorer.exportState().budgets().isEmpty());
    }
    @Test void actorObservationsAreEphemeralAndExcludeUnknownPrivateFields() {
        observe(1,20,1);
        assertTrue(world.find(subject(1),"entity_type",1).isPresent());
        assertTrue(world.find(subject(1),"quest_secret",1).isEmpty());
        assertTrue(world.exportState().facts().isEmpty());
        assertTrue(world.find(subject(1),"entity_type",1000).isEmpty());
    }
    @Test void typedConditionalPredictionUsesTheLearnedActorRole() {
        support(1,1); support(2,5); observe(3,20,10);
        String args="{\"entity_id\":43,\"button\":\"use\"}";
        var prediction=explorer.predict("mod:npc","interact_entity",args,"health","20.0",subject(3),List.of(),10);
        assertEquals(2,prediction.independentContexts());
    }
    private void support(int actor,long tick) {
        observe(actor,20,tick);
        String args="{\"entity_id\":"+(40+actor)+",\"button\":\"use\"}";
        var result=explorer.propose("mod:npc","interaction changes health","interact_entity",args,subject(actor),"health","18.0",
                MechanismExplorer.Risk.MEDIUM,"fixture",true,"close_gui","{}",4,0.5,tick);
        assertTrue(result.accepted(),result.message()); String actual=result.experiment().probeArguments();
        assertTrue(actual.contains(uuid(actor))); explorer.onToolDispatched("call-"+actor,"interact_entity",actual,tick);
        observe(actor,18,tick+1); explorer.onToolResult("call-"+actor,"interact_entity",true,false,"{\"success\":true}",tick+1);
    }
    @Test void aCompleteActorObservationRetractsUnavailableFieldsRatherThanKeepingOldHealth() {
        observe(1, 20, 1);
        assertTrue(world.find(subject(1), "health", 1).isPresent());
        explorer.onToolResult("inspect", "inspect_entity", true, false,
                "{\"fact_subject\":\"" + subject(1) + "\",\"entity_id\":41,\"entity_type\":\"mod:nonliving_actor\"}", 2);
        assertTrue(world.find(subject(1), "health", 2).isEmpty());
        assertEquals("mod:nonliving_actor", world.find(subject(1), "entity_type", 2).orElseThrow().value());
    }

    private void observe(int actor,int health,long tick) {
        String raw="{\"fact_subject\":\""+subject(actor)+"\",\"entity_id\":"+(40+actor)
                +",\"entity_type\":\"mod:npc\",\"dimension\":\"minecraft:overworld\",\"health\":"+health+".0,\"quest_secret\":\"hidden\"}";
        explorer.onToolResult("inspect","inspect_entity",true,false,raw,tick);
    }
    private static String uuid(int actor) { return "00000000-0000-0000-0000-"+String.format(java.util.Locale.ROOT,"%012d",actor); }
    private static String subject(int actor) { return "entity:"+uuid(actor); }
}
