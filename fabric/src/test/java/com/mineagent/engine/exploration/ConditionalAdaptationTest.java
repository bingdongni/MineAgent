package com.mineagent.engine.exploration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class ConditionalAdaptationTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final EnvironmentFingerprint ENV = new EnvironmentFingerprint("1.21.1", "fabric", "mods", "registry");
    private static final PlanGraph.GoalCondition INPUT = new PlanGraph.GoalCondition("inventory:mod:raw", "count", "2", PlanGraph.Comparison.AT_LEAST, 0.7);
    private final SemanticWorldModel world = new SemanticWorldModel();
    private final SkillLibrary skills = new SkillLibrary();
    private final MechanismExplorer explorer = new MechanismExplorer(world, (a,b,c,d) -> {}, skills, ENV,
            (id, tool, args) -> new MechanismExplorer.CompensationResult(true, false, true, "closed"));

    @Test void missingConditionsRejectTheProposalWithoutSpendingAProbe() {
        var result = propose(1, 1, List.of(INPUT), "{}");
        assertFalse(result.accepted()); assertTrue(result.message().startsWith("Observe"));
        assertTrue(explorer.all().isEmpty());
    }

    @Test void contradictedConditionsRequireRestoration() {
        input("1", 1);
        var result = propose(1, 1, List.of(INPUT), "{}");
        assertFalse(result.accepted()); assertTrue(result.message().startsWith("Restore"));
    }

    @Test void changedPrerequisiteBlocksBeforeDispatchAndDoesNotRefuteTheMechanism() {
        input("3", 1); target(1, "0", 1);
        var result = propose(1, 1, List.of(INPUT), arguments(1));
        assertTrue(result.accepted()); input("0", 2);
        assertNotNull(explorer.actionBlocker("interact_at", arguments(1), 2));
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE, explorer.get(result.experiment().id()).status());
        assertEquals(0, explorer.knowledgeBase().rules().iterator().next().refutations());
        assertFalse(explorer.reflect("", 2, 1).getFirst().mechanismCounterexample());
    }

    @Test void independentTargetsShareOnlyTheirBoundIdentityAndConditions() {
        var rule = learn();
        assertEquals(2, rule.supports()); assertTrue(rule.reusable(ENV));
        assertTrue(rule.prerequisites().stream().anyMatch(value -> value.predicate().equals("block_id")));
        assertTrue(rule.prerequisites().stream().anyMatch(value -> value.predicate().equals("property.stage")));
        assertTrue(rule.prerequisites().contains(INPUT));
    }

    @Test void differentNonSpatialArgumentsNeverConfirmEachOther() {
        input("3", 1);
        support(1, 2, List.of(INPUT), "{\"x\":1,\"y\":64,\"z\":0,\"item\":\"mod:red\"}");
        support(2, 5, List.of(INPUT), "{\"x\":2,\"y\":64,\"z\":0,\"item\":\"mod:blue\"}");
        assertEquals(2, explorer.knowledgeBase().rules().size());
        assertTrue(explorer.knowledgeBase().rules().stream().noneMatch(rule -> rule.reusable(ENV)));
    }

    @Test void differentConditionContractsRemainSeparateCohorts() {
        input("3", 1);
        support(1, 2, List.of(INPUT), arguments(1));
        var stronger = new PlanGraph.GoalCondition(INPUT.subject(), INPUT.predicate(), "3", INPUT.comparison(), 0.7);
        support(2, 5, List.of(stronger), arguments(2));
        assertEquals(2, explorer.knowledgeBase().rules().size());
        assertTrue(explorer.knowledgeBase().rules().stream().noneMatch(rule -> rule.reusable(ENV)));
    }

    @Test void typedTransferBindsBodyAndVerificationToTheSameObservedTarget() {
        var rule = learn(); target(3, "0", 10);
        var transfer = explorer.transfer(rule.id(), subject(3), DIMENSION, 10);
        assertTrue(transfer.eligible(), transfer.message());
        var bindings = transfer.overrides().getAsJsonObject("bindings");
        assertEquals(3, bindings.get("target_x").getAsInt());
        assertEquals(subject(3), bindings.get("effect_subject").getAsString());
    }

    @Test void identicalNamesDoNotAuthorizeDifferentRegisteredTypes() {
        var rule = learn(); target(3, "0", 10);
        fact(subject(3), "block_id", "other:machine", 10);
        assertFalse(explorer.transfer(rule.id(), subject(3), DIMENSION, 10).eligible());
    }

    @Test void transferRequiresObservedTargetConditions() {
        var rule = learn();
        assertFalse(explorer.transfer(rule.id(), subject(3), DIMENSION, 10).eligible());
        target(3, "2", 10);
        assertFalse(explorer.transfer(rule.id(), subject(3), DIMENSION, 10).eligible());
    }

    @Test void crossDimensionBindingsDoNotDispatchInTheWrongWorld() {
        var rule = learn(); target(3, "0", 10);
        assertFalse(explorer.transfer(rule.id(), subject(3), "mod:other_dimension", 10).eligible());
    }

    @Test void transferAdmissionDoesNotBypassLaterLiveConditions() {
        var rule = learn(); target(3, "0", 10);
        var transfer = explorer.transfer(rule.id(), subject(3), DIMENSION, 10);
        assertTrue(transfer.eligible());
        AtomicInteger dispatched = new AtomicInteger();
        var runtime = new SkillRuntime(skills, world, (id,tool,args) -> {
            dispatched.incrementAndGet(); return new SkillRuntime.DispatchResult(true,false,true,"ok");
        }, ignored -> {});
        assertTrue(runtime.start(transfer.skillName(), transfer.overrides(), 10).accepted());
        input("0", 11); runtime.tick(11);
        assertEquals(0, dispatched.get()); assertEquals(SkillRuntime.Status.NEEDS_REPLAN, runtime.snapshot().status());
    }

    @Test void counterexampleInvalidatesAnAlreadyPreparedSkillAndItsDependencyFact() {
        var rule = learn(); target(3, "0", 10);
        var transfer = explorer.transfer(rule.id(), subject(3), DIMENSION, 10);
        AtomicInteger dispatched = new AtomicInteger();
        var runtime = new SkillRuntime(skills, world, (id,tool,args) -> {
            dispatched.incrementAndGet(); return new SkillRuntime.DispatchResult(true,false,true,"ok");
        }, ignored -> {});
        assertTrue(runtime.start(transfer.skillName(), transfer.overrides(), 10).accepted());
        counterexample(4, 11);
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
        assertEquals("false", world.find("mechanism_rule:" + rule.id(), "reusable", 12).orElseThrow().value());
        runtime.tick(12);
        assertEquals(0, dispatched.get()); assertEquals(SkillRuntime.Status.NEEDS_REPLAN, runtime.snapshot().status());
    }

    @Test void restartRestoresEvidenceButRequiresFreshTargetObservations() {
        var rule = learn();
        var restoredWorld = new SemanticWorldModel(); var restoredSkills = new SkillLibrary();
        var restored = new MechanismExplorer(restoredWorld,(a,b,c,d)->{},restoredSkills,ENV,null);
        Gson gson = new Gson(); restored.importState(gson.fromJson(gson.toJson(explorer.exportState()),MechanismExplorer.State.class));
        assertTrue(restored.knowledgeBase().rules().iterator().next().reusable(ENV));
        assertFalse(restored.transfer(rule.id(), subject(3), DIMENSION, 10).eligible());
        assertFalse(restoredWorld.exportState().facts().stream().anyMatch(value -> value.subject().startsWith("mechanism_rule:")));
    }

    @Test void previousContextFormatCannotRestoreUnscopedAutomaticReplay() {
        var rule = learn(); Gson gson = new Gson();
        var state = gson.toJsonTree(explorer.exportState()).getAsJsonObject();
        state.getAsJsonObject("knowledge").addProperty("contextVersion", 1);
        explorer.importState(gson.fromJson(state, MechanismExplorer.State.class));
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
        assertTrue(explorer.knowledgeBase().rules().stream().noneMatch(value -> value.reusable(ENV)));
        assertTrue(explorer.exportState().learning().trials().isEmpty());
    }

    @Test void clearingLearningRevokesPreviouslyCompiledSkillAuthority() {
        var rule = learn();
        explorer.importState(null);
        assertTrue(skills.get(rule.adapterSkill()).isEmpty());
        assertEquals("false", world.find("mechanism_rule:" + rule.id(), "reusable", 10).orElseThrow().value());
        assertTrue(explorer.knowledgeBase().rules().isEmpty());
    }

    @Test void failedActionReflectionDoesNotInventAMechanismCounterexample() {
        input("3", 1); target(1,"0",1);
        var proposal = propose(1,1,List.of(INPUT),arguments(1));
        explorer.onToolDispatched("failed","interact_at",arguments(1),2);
        explorer.onToolResult("failed",false,false,"unsupported interface",2);
        var reflection = explorer.reflect("",2,1).getFirst();
        assertEquals(LearningReflection.Kind.INTERFACE_GAP,reflection.kind());
        assertFalse(reflection.mechanismCounterexample());
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE,explorer.get(proposal.experiment().id()).status());
    }

    @Test void blockObservationsProjectIdentityAndStateWithoutPrivateNbt() {
        explorer.onToolResult("inspect","inspect_block",true,false,
                "{\"fact_subject\":\"block:minecraft:overworld:1,64,0\",\"block_id\":\"mod:machine\",\"properties\":{\"stage\":0},\"block_entity_data\":\"secret rule\"}",1);
        assertEquals("0",world.find(subject(1),"property.stage",1).orElseThrow().value());
        assertTrue(world.factsForSubject(subject(1),1).stream().noneMatch(value -> value.value().contains("secret")));
    }

    @Test void unavailablePortRetractsOldValuesAndDoesNotInventZeroEnergy() {
        String prefix="{\"fact_subject\":\""+subject(1)+"\",\"block_id\":\"mod:machine\",\"capabilities\":";
        explorer.onToolResult("one","inspect_block",true,false,prefix
                +"{\"channels\":{\"energy\":\"available\"},\"fields\":{\"energy.amount\":\"50\"}}}",1);
        assertEquals("50",world.find(subject(1),"capability.energy.amount",1).orElseThrow().value());
        explorer.onToolResult("two","inspect_block",true,false,prefix
                +"{\"channels\":{\"energy\":\"unavailable_on_face\"},\"fields\":{}}}",2);
        assertTrue(world.find(subject(1),"capability.energy.amount",2).isEmpty());
    }

    @Test void contextSignaturesBindOnlyTheExplicitTargetAndRetainUnits() {
        var a=new PlanGraph.GoalCondition(subject(1),"capability.fluids.unit","millibuckets",PlanGraph.Comparison.EQUALS,0.7);
        var b=new PlanGraph.GoalCondition(subject(2),a.predicate(),a.value(),a.comparison(),0.7);
        assertEquals(LearningConditions.signature(List.of(a),subject(1)),LearningConditions.signature(List.of(b),subject(2)));
        assertNotEquals(LearningConditions.signature(List.of(a),subject(1)),LearningConditions.signature(List.of(
                new PlanGraph.GoalCondition(subject(1),a.predicate(),"fabric_droplets",a.comparison(),0.7)),subject(1)));
    }

    @Test void attackVariantsCannotMasqueradeAsReversibleExperiments() {
        for (String tool : List.of("interact_at", "interact_entity")) {
            String args = "{\"button\":\"attack\",\"x\":1,\"y\":64,\"z\":0,\"entity_id\":7}";
            var proposal = explorer.propose("mod:actor", "attack changes state", tool, args,
                    "target", "state", "changed", MechanismExplorer.Risk.MEDIUM, "fixture", true,
                    "close_gui", "{}", 4, 1, 1);
            assertFalse(proposal.accepted(), tool);
            var candidate = new MechanismExplorer.ProbeCandidate(tool, args, MechanismExplorer.Risk.MEDIUM,
                    true, "close_gui", "{}", 4, 1);
            assertNull(explorer.recommend(List.of(candidate), "mod:actor", 1).candidate());
        }
        assertTrue(explorer.exportState().budgets().isEmpty());
    }

    @Test void conditionedTransitionsComposeWithoutConflatingTheirBeforeStates() {
        input("3", 1);
        // Component fixture: a read-only poll sees an externally advanced public machine state.
        for (int before = 0; before < 2; before++) for (int repeat = 0; repeat < 2; repeat++) {
            int x = before * 2 + repeat + 1;
            pollTransition(x, Integer.toString(before), Integer.toString(before + 1), x * 3L);
        }
        target(5, "0", 20);
        var route = explorer.plan("mod:machine", "property.stage", "0", "2", 4,
                subject(5), List.of(INPUT), 20);
        assertTrue(route.found(), route.message()); assertEquals(2, route.steps().size());
        assertEquals("0", route.steps().getFirst().before()); assertEquals("2", route.steps().getLast().after());
        input("0", 21);
        assertThrows(IllegalArgumentException.class, () -> explorer.plan("mod:machine", "property.stage", "0", "2", 4,
                subject(5), List.of(INPUT), 21));
    }

    @Test void explorationRankingUsesApplicableConditionalEvidenceInsteadOfTheModelEstimate() {
        input("3", 1); pollTransition(1, "0", "1", 2); pollTransition(2, "0", "1", 5); target(3, "0", 10);
        var probe = new MechanismExplorer.ProbeCandidate("inspect_block", arguments(3), MechanismExplorer.Risk.LOW,
                false, null, "{}", 1, 1);
        var prediction = explorer.predict("mod:machine", "inspect_block", arguments(3), "property.stage", "0",
                subject(3), List.of(INPUT), 10);
        assertEquals(2, prediction.independentContexts());
        double expected = PredictiveLearning.explorationValue(prediction);
        assertTrue(expected < 1.0);
        assertEquals(expected, explorer.recommend(List.of(probe), "mod:machine", 10).score(), 0.000001);
        input("0", 11);
        assertEquals(1.0, explorer.recommend(List.of(probe), "mod:machine", 11).score(), 0.000001);
    }

    @Test void itemDescriptionsPersistAsBoundedObservationsAndReplaceAnUnavailableSnapshot() {
        explorer.onToolResult("item", "inspect_item", true, false,
                "{\"item_id\":\"mod:catalyst\",\"tooltip\":[\"Observed hint\"],\"tags\":[\"mod:catalysts\"],\"components\":{\"private\":\"secret\"}}", 1);
        var saved = new Gson().fromJson(new Gson().toJson(explorer.exportState()), MechanismExplorer.State.class);
        explorer.importState(saved);
        var profile = explorer.knowledgeBase().profile("mod:catalyst");
        assertEquals("Observed hint", profile.attributes().get("tooltip.0"));
        assertEquals("mod:catalysts", profile.attributes().get("tags.0"));
        assertFalse(profile.attributes().toString().contains("secret"));
        explorer.onToolResult("item-2", "inspect_item", true, false,
                "{\"item_id\":\"mod:catalyst\",\"tooltip_status\":\"adapter_unavailable\"}", 2);
        assertFalse(explorer.knowledgeBase().profile("mod:catalyst").attributes().containsKey("tooltip.0"));
    }

    @Test void renamingAnObservedTypedMechanismCannotCreateAnotherExplorationBudget() {
        target(1, "0", 1);
        var proposal = explorer.propose("renamed:machine", "activation", "inspect_block", arguments(1),
                subject(1), "property.stage", "1", MechanismExplorer.Risk.LOW, 1);
        assertFalse(proposal.accepted()); assertTrue(proposal.message().contains("mod:machine"));
        assertTrue(explorer.exportState().budgets().isEmpty());
    }

    @Test void differentInventoryOutputsCannotConfirmOrPredictEachOthersRules() {
        int index = 0;
        for (String target : List.of("inventory:mod:red", "inventory:mod:blue")) {
            long tick = ++index * 3L;
            fact(target, "count", "0", tick);
            var proposal = explorer.propose("mod:machine", "produces output", "inspect_block", arguments(index),
                    target, "count", "1", MechanismExplorer.Risk.LOW, tick);
            assertTrue(proposal.accepted());
            explorer.onToolDispatched("output-" + index, "inspect_block", arguments(index), tick);
            fact(target, "count", "1", tick + 1);
            explorer.onToolResult("output-" + index, "inspect_block", true, false, "{\"success\":true}", tick + 1);
        }
        assertEquals(2, explorer.knowledgeBase().rules().size());
        assertTrue(explorer.knowledgeBase().rules().stream().noneMatch(rule -> rule.reusable(ENV)));
        var prediction = explorer.predict("mod:machine", "inspect_block", arguments(3), "count", "0",
                "inventory:mod:red", List.of(), 10);
        assertEquals(1, prediction.independentContexts());
    }

    private void pollTransition(int x, String before, String after, long tick) {
        target(x, before, tick);
        var proposal = explorer.propose("mod:machine", "poll sees next stage", "inspect_block", arguments(x), subject(x),
                "property.stage", after, MechanismExplorer.Risk.LOW, "fixture", false, null, "{}", 1, 1, tick, List.of(INPUT));
        assertTrue(proposal.accepted(), proposal.message());
        String call = "poll-" + x; explorer.onToolDispatched(call, "inspect_block", arguments(x), tick);
        fact(subject(x), "property.stage", after, tick + 1);
        explorer.onToolResult(call, "inspect_block", true, false, "{\"success\":true}", tick + 1);
        assertEquals(MechanismExplorer.Status.SUPPORTED, explorer.get(proposal.experiment().id()).status());
    }

    private MechanismKnowledgeBase.Rule learn() {
        input("3",1); support(1,2,List.of(INPUT),arguments(1)); support(2,5,List.of(INPUT),arguments(2));
        return explorer.knowledgeBase().rules().iterator().next();
    }
    private void support(int x,long tick,List<PlanGraph.GoalCondition> conditions,String args) {
        target(x,"0",tick); var proposal=propose(x,tick,conditions,args); assertTrue(proposal.accepted(),proposal.message());
        String call="call-"+tick; explorer.onToolDispatched(call,"interact_at",args,tick+1);
        fact(subject(x),"property.stage","1",tick+1);
        explorer.onToolResult(call,"interact_at",true,false,"{\"success\":true}",tick+1);
        assertEquals(MechanismExplorer.Status.SUPPORTED,explorer.get(proposal.experiment().id()).status());
    }
    private void counterexample(int x,long tick) {
        target(x,"0",tick); var proposal=propose(x,tick,List.of(INPUT),arguments(x)); assertTrue(proposal.accepted(),proposal.message());
        explorer.onToolDispatched("counter","interact_at",arguments(x),tick);
        fact(subject(x),"property.stage","2",tick);
        explorer.onToolResult("counter","interact_at",true,false,"{\"success\":true}",tick);
    }
    private MechanismExplorer.Proposal propose(int x,long tick,List<PlanGraph.GoalCondition> conditions,String args) {
        return explorer.propose("mod:machine","activation produces stage one","interact_at",args,subject(x),"property.stage","1",
                MechanismExplorer.Risk.MEDIUM,"annotation",true,"close_gui","{}",4,0.5,tick,conditions);
    }
    private void input(String value,long tick) { fact(INPUT.subject(),INPUT.predicate(),value,tick); }
    private void target(int x,String stage,long tick) { fact(subject(x),"block_id","mod:machine",tick); fact(subject(x),"property.stage",stage,tick); }
    private void fact(String subject,String predicate,String value,long tick) { world.observe(subject,predicate,value,null,1,"server",null,tick,1000,false); }
    private static String subject(int x) { return "block:"+DIMENSION+":"+x+",64,0"; }
    private static String arguments(int x) { return "{\"x\":"+x+",\"y\":64,\"z\":0}"; }
}
