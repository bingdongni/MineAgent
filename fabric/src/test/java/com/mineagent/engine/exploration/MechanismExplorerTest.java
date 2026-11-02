package com.mineagent.engine.exploration;

import com.mineagent.engine.world.SemanticWorldModel;
import com.mineagent.engine.skill.SkillLibrary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MechanismExplorerTest {
    @Test void supportsHypothesisOnlyAfterDeclaredPostcondition() {
        SemanticWorldModel world = new SemanticWorldModel();
        List<String> evidence = new ArrayList<>();
        MechanismExplorer explorer = new MechanismExplorer(world,
                (experiment, supported, detail, tick) ->
                        evidence.add(supported + ":" + detail));
        var proposal = explorer.propose("mod:machine", "use opens a menu",
                "interact_at", "{\"x\":1}", "tool:interact_at",
                "outcome", "success", MechanismExplorer.Risk.MEDIUM, 10L);
        assertTrue(proposal.accepted());
        explorer.onToolDispatched("call", "interact_at", "{\"x\":1}", 11L);
        world.recordOutcome("interact_at", true, "opened", "call", 12L);
        explorer.onToolResult("call", true, false, "opened", 12L);

        assertEquals(MechanismExplorer.Status.SUPPORTED,
                explorer.get(proposal.experiment().id()).status());
        assertEquals(1, evidence.size());
    }

    @Test void rejectsHighRiskOrDestructiveProbe() {
        MechanismExplorer explorer = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        assertFalse(explorer.propose("x", "break it", "auto_mine", "{}",
                null, null, null, MechanismExplorer.Risk.LOW, 1L).accepted());
        assertFalse(explorer.propose("x", "touch it", "interact_at", "{}",
                null, null, null, MechanismExplorer.Risk.HIGH, 1L).accepted());
    }

    @Test void newContradictoryObservationRefutesHypothesis() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = new MechanismExplorer(world, (a, b, c, d) -> {});
        var proposal = explorer.propose("mod:machine", "use makes it active",
                "interact_at", "{}", "machine:mod:machine", "state", "active",
                MechanismExplorer.Risk.MEDIUM, 1L);
        explorer.onToolDispatched("call", "interact_at", "{}", 2L);
        world.observe("machine:mod:machine", "state", "jammed", null,
                0.9, "inspect_gui", "call", 3L, 40L, false);
        explorer.onToolResult("call", true, false, "interaction accepted", 3L);

        assertEquals(MechanismExplorer.Status.REFUTED,
                explorer.get(proposal.experiment().id()).status());
    }

    @Test void restartTurnsAnInFlightExperimentIntoInconclusiveEvidence() {
        MechanismExplorer source = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        var proposal = source.propose("mod:block", "use opens it", "interact_at",
                "{}", null, null, null, MechanismExplorer.Risk.MEDIUM, 1L);
        MechanismExplorer restored = new MechanismExplorer(
                new SemanticWorldModel(), (a, b, c, d) -> {});
        restored.importState(source.exportState());
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE,
                restored.get(proposal.experiment().id()).status());
    }

    @Test void failedProbeIsInconclusiveRatherThanNegativeRuleEvidence() {
        List<String> evidence = new ArrayList<>();
        MechanismExplorer explorer = new MechanismExplorer(new SemanticWorldModel(),
                (experiment, supported, detail, tick) -> evidence.add(detail));
        explorer.propose("mod:block", "use opens it", "interact_at", "{}",
                null, null, null, MechanismExplorer.Risk.MEDIUM, 1L);
        explorer.onToolDispatched("call", "interact_at", "{}", 2L);
        explorer.onToolResult("call", false, false, "out of reach", 3L);
        assertTrue(evidence.isEmpty());
    }

    @Test void oneSupportRemainsCandidateAndTwoIndependentContextsCompileAdapter() {
        SemanticWorldModel world = new SemanticWorldModel();
        SkillLibrary skills = new SkillLibrary();
        MechanismExplorer explorer = explorer(world, skills, fingerprint("a"));

        supportMachine(explorer, world, "machine:one", "ctx-one", 10L);
        var candidate = explorer.knowledgeBase().rules().iterator().next();
        assertEquals(MechanismKnowledgeBase.RuleStatus.CANDIDATE, candidate.status());
        assertNull(candidate.adapterSkill());

        supportMachine(explorer, world, "machine:two", "ctx-two", 100L);
        var confirmed = explorer.knowledgeBase().rules().iterator().next();
        assertEquals(MechanismKnowledgeBase.RuleStatus.CONFIRMED, confirmed.status());
        assertEquals(2, confirmed.supports());
        assertEquals(2, confirmed.supportingContexts().size());
        assertNotNull(confirmed.adapterSkill());
        assertTrue(skills.get(confirmed.adapterSkill()).isPresent());
        assertTrue(skills.get(confirmed.adapterSkill()).orElseThrow()
                .actionSequence().contains("expected_effects"));
    }

    @Test void contradictionLowersConfidenceAndInvalidatesCompiledAdapter() {
        SemanticWorldModel world = new SemanticWorldModel();
        SkillLibrary skills = new SkillLibrary();
        MechanismExplorer explorer = explorer(world, skills, fingerprint("a"));
        supportMachine(explorer, world, "machine:one", "ctx-one", 10L);
        supportMachine(explorer, world, "machine:two", "ctx-two", 100L);
        String adapter = explorer.knowledgeBase().rules().iterator().next().adapterSkill();

        var proposal = proposeMachine(explorer, "machine:three", "ctx-three", 200L);
        explorer.onToolDispatched("call-three", "interact_at", "{\"x\":1}", 201L);
        world.observe("machine:three", "state", "jammed", null, 0.9,
                "inspect_gui", "call-three", 202L, 40L, false);
        explorer.onToolResult("call-three", "interact_at", true, false,
                "{\"success\":true}", 202L);

        var invalid = explorer.knowledgeBase().rules().iterator().next();
        assertEquals(MechanismKnowledgeBase.RuleStatus.REFUTED, invalid.status());
        assertTrue(invalid.confidence() < 0.7);
        assertTrue(skills.get(adapter).isEmpty());
        assertEquals(MechanismExplorer.Status.REFUTED,
                explorer.get(proposal.experiment().id()).status());
    }

    @Test void unchangedPostStateCannotSupportACausalTransition() {
        SemanticWorldModel world = new SemanticWorldModel();
        world.observe("machine:same", "state", "active", null, 0.9,
                "baseline", null, 1L, 1_000L, false);
        MechanismExplorer explorer = explorer(world, new SkillLibrary(), fingerprint("a"));
        var proposal = proposeMachine(explorer, "machine:same", "ctx-same", 10L);
        explorer.onToolDispatched("same-call", "interact_at", "{\"x\":1}", 11L);
        world.observe("machine:same", "state", "active", null, 0.9,
                "inspect_gui", "same-call", 12L, 1_000L, false);
        explorer.onToolResult("same-call", "interact_at", true, false,
                "{\"success\":true}", 12L);
        explorer.tick(300L);

        assertEquals(MechanismExplorer.Status.INCONCLUSIVE,
                explorer.get(proposal.experiment().id()).status());
        assertEquals(0, explorer.knowledgeBase().rules().iterator().next().supports());
    }

    @Test void stateChangingProbeWithoutCompensationIsRejected() {
        MechanismExplorer explorer = explorer(new SemanticWorldModel(),
                new SkillLibrary(), fingerprint("a"));
        assertFalse(explorer.propose("mod:machine", "opens", "interact_at", "{}",
                "tool:interact_at", "outcome", "success",
                MechanismExplorer.Risk.MEDIUM, "ctx", true,
                null, "{}", 4, 0.8, 1L).accepted());
    }

    @Test void environmentChangeMakesRulesStaleAndPreventsAdapterRestore() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer source = explorer(world, new SkillLibrary(), fingerprint("a"));
        supportMachine(source, world, "machine:one", "ctx-one", 10L);
        supportMachine(source, world, "machine:two", "ctx-two", 100L);

        SkillLibrary restoredSkills = new SkillLibrary();
        MechanismExplorer restored = explorer(new SemanticWorldModel(),
                restoredSkills, fingerprint("b"));
        restored.importState(source.exportState());
        var stale = restored.knowledgeBase().rules().iterator().next();
        assertEquals(MechanismKnowledgeBase.RuleStatus.STALE, stale.status());
        assertFalse(stale.reusable(fingerprint("b")));
        assertTrue(restoredSkills.exportAll().isEmpty());
    }

    @Test void changedEnvironmentCanRevalidateWithoutRevivingOldRule() {
        SemanticWorldModel oldWorld = new SemanticWorldModel();
        MechanismExplorer old = explorer(oldWorld, new SkillLibrary(), fingerprint("a"));
        supportMachine(old, oldWorld, "machine:old-one", "old-one", 10L);
        supportMachine(old, oldWorld, "machine:old-two", "old-two", 100L);

        SemanticWorldModel newWorld = new SemanticWorldModel();
        SkillLibrary newSkills = new SkillLibrary();
        MechanismExplorer current = explorer(newWorld, newSkills, fingerprint("b"));
        current.importState(old.exportState());
        supportMachine(current, newWorld, "machine:new-one", "new-one", 200L);
        supportMachine(current, newWorld, "machine:new-two", "new-two", 300L);

        long stale = current.knowledgeBase().rules().stream()
                .filter(rule -> rule.status() == MechanismKnowledgeBase.RuleStatus.STALE).count();
        long confirmed = current.knowledgeBase().rules().stream()
                .filter(rule -> rule.status() == MechanismKnowledgeBase.RuleStatus.CONFIRMED).count();
        assertEquals(1L, stale);
        assertEquals(1L, confirmed);
        assertEquals(MechanismKnowledgeBase.Novelty.VERIFIED,
                current.knowledgeBase().novelty("mod:machine"));
    }

    @Test void restartRestoresKnowledgeButNotRunningExperimentOwnership() {
        SemanticWorldModel sourceWorld = new SemanticWorldModel();
        MechanismExplorer source = explorer(sourceWorld,
                new SkillLibrary(), fingerprint("a"));
        supportMachine(source, sourceWorld, "machine:one", "ctx-one", 10L);
        supportMachine(source, sourceWorld, "machine:two", "ctx-two", 100L);
        var inFlight = source.propose("other:block", "inspection reveals hardness",
                "inspect_block", "{\"x\":1,\"y\":2,\"z\":3}",
                "tool:inspect_block", "result.hardness", "2.0",
                MechanismExplorer.Risk.LOW, "other-context", true,
                null, "{}", 1, 0.7, 200L);
        assertTrue(inFlight.accepted());

        SkillLibrary restoredSkills = new SkillLibrary();
        MechanismExplorer restored = explorer(new SemanticWorldModel(),
                restoredSkills, fingerprint("a"));
        restored.importState(source.exportState());

        var rule = restored.knowledgeBase().rules().iterator().next();
        assertEquals(MechanismKnowledgeBase.RuleStatus.CONFIRMED, rule.status());
        assertTrue(restoredSkills.get(rule.adapterSkill()).isPresent());
        assertEquals(MechanismExplorer.Status.INCONCLUSIVE,
                restored.get(inFlight.experiment().id()).status());
        assertNull(restored.get(inFlight.experiment().id()).actionId());
    }

    @Test void promptRecallReturnsOnlyGoalRelevantMechanisms() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = explorer(world, new SkillLibrary(), fingerprint("a"));
        supportMachine(explorer, world, "machine:one", "ctx-one", 10L);
        supportMachine(explorer, world, "machine:two", "ctx-two", 100L);

        String relevant = explorer.summarizeForPrompt("activate mod machine");
        String unrelated = explorer.summarizeForPrompt("walk to the oak forest");
        assertTrue(relevant.contains("mod:machine"));
        assertFalse(unrelated.contains("mod:machine"));
    }

    @Test void guiSlotsAreLearnedAsBoundedStructuredAttributes() {
        MechanismKnowledgeBase knowledge = new MechanismKnowledgeBase(fingerprint("a"));
        String gui = "{\"container_type\":\"ModMachineMenu\",\"total_slots\":5,"
                + "\"slots\":[{\"slot\":0,\"endpoint\":\"container\","
                + "\"item\":\"mod:ore\",\"count\":2},{\"slot\":4,"
                + "\"endpoint\":\"player\",\"inventory_slot\":1,"
                + "\"item\":\"minecraft:coal\",\"count\":3}]}";
        knowledge.observeToolResult("inspect_gui", gui, 10L);

        var profile = knowledge.profile("menu:ModMachineMenu");
        assertNotNull(profile);
        assertEquals(MechanismKnowledgeBase.Kind.MENU, profile.kind());
        assertEquals("container", profile.attributes().get("slot.0.endpoint"));
        assertEquals("mod:ore", profile.attributes().get("slot.0.item"));
        assertEquals("1", profile.attributes().get("slot.4.inventory_slot"));
    }

    @Test void structuredSituationProfilesModEntitiesBlocksAndItems() {
        MechanismKnowledgeBase knowledge = new MechanismKnowledgeBase(fingerprint("a"));
        String result = "{\"entities\":[{\"type\":\"mod:sentinel\","
                + "\"activity\":\"guarding\",\"immediate_threat\":false}],"
                + "\"notable_blocks\":[{\"block\":\"mod:altar\","
                + "\"kind\":\"block_entity\"}],\"dropped_items\":[{"
                + "\"item\":\"mod:shard\",\"count\":2}]}";
        knowledge.observeToolResult("look_around", result, 10L);

        assertEquals(MechanismKnowledgeBase.Kind.ENTITY,
                knowledge.profile("mod:sentinel").kind());
        assertEquals("guarding",
                knowledge.profile("mod:sentinel").attributes().get("activity"));
        assertEquals(MechanismKnowledgeBase.Kind.BLOCK,
                knowledge.profile("mod:altar").kind());
        assertEquals(MechanismKnowledgeBase.Kind.ITEM,
                knowledge.profile("mod:shard").kind());
        assertEquals(MechanismKnowledgeBase.Novelty.OBSERVED_UNVERIFIED,
                knowledge.novelty("mod:sentinel"));
        assertEquals(MechanismKnowledgeBase.Novelty.BUILTIN_UNVERIFIED,
                knowledge.novelty("minecraft:zombie"));
    }

    @Test void postInteractionGuiInspectionCanVerifyStructuredStateDelta() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = explorer(world, new SkillLibrary(), fingerprint("a"));
        String empty = "{\"menu_type_id\":\"mod:machine\",\"slots\":[{"
                + "\"slot\":0,\"endpoint\":\"container\","
                + "\"slot_type\":\"InputSlot\",\"occupied\":false}]}";
        explorer.onToolResult("inspect-before", "inspect_gui", true, false, empty, 5L);

        var proposal = explorer.propose("mod:machine", "interaction fills input slot",
                "interact_at", "{\"x\":1}", "profile:menu:mod:machine",
                "attribute.slot.0.occupied", "true", MechanismExplorer.Risk.MEDIUM,
                "ctx-profile", true, "close_gui", "{}", 4, 0.9, 10L);
        assertTrue(proposal.accepted());
        explorer.onToolDispatched("interaction", "interact_at", "{\"x\":1}", 11L);
        explorer.onToolResult("interaction", "interact_at", true, false,
                "{\"success\":true}", 12L);
        String filled = "{\"menu_type_id\":\"mod:machine\",\"slots\":[{"
                + "\"slot\":0,\"endpoint\":\"container\","
                + "\"slot_type\":\"InputSlot\",\"occupied\":true,"
                + "\"item\":\"mod:ore\",\"count\":1}]}";
        explorer.onToolResult("inspect-after", "inspect_gui", true, false, filled, 13L);
        explorer.tick(14L);

        assertEquals(MechanismExplorer.Status.SUPPORTED,
                explorer.get(proposal.experiment().id()).status());
        assertEquals("true", explorer.knowledgeBase()
                .profile("menu:mod:machine").attributes().get("slot.0.occupied"));
    }

    @Test void abortedExperimentDoesNotCreateRuleEvidence() {
        MechanismExplorer explorer = explorer(new SemanticWorldModel(),
                new SkillLibrary(), fingerprint("a"));
        var proposal = explorer.propose("mod:block", "has a property", "inspect_block",
                "{}", null, null, null, MechanismExplorer.Risk.LOW,
                "ctx", true, null, "{}", 1, 0.5, 1L);
        assertTrue(proposal.accepted());
        assertTrue(explorer.abort(proposal.experiment().id(), "owner stopped", 2L));
        assertTrue(explorer.knowledgeBase().rules().isEmpty());
    }

    @Test void probeRecommendationBalancesInformationCostAndSafety() {
        MechanismExplorer explorer = explorer(new SemanticWorldModel(),
                new SkillLibrary(), fingerprint("a"));
        var recommendation = explorer.recommend(List.of(
                new MechanismExplorer.ProbeCandidate("inspect_block", "{}",
                        MechanismExplorer.Risk.LOW, true, null, "{}", 1, 0.7),
                new MechanismExplorer.ProbeCandidate("interact_entity", "{}",
                        MechanismExplorer.Risk.MEDIUM, false, null, "{}", 4, 1.0),
                new MechanismExplorer.ProbeCandidate("auto_mine", "{}",
                        MechanismExplorer.Risk.HIGH, false, null, "{}", 1, 1.0)),
                "mod:block", 1L);
        assertNotNull(recommendation.candidate());
        assertEquals("inspect_block", recommendation.candidate().tool());
    }

    @Test void verifiedEvidencePredictsAcrossTargetsAndPersistsWithoutRestoringAnExperiment() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer source = explorer(world, new SkillLibrary(), fingerprint("a"));
        supportMachine(source, world, "machine:one", "ctx-one", 10);
        supportMachine(source, world, "machine:two", "ctx-two", 100);
        var prediction = source.predict("mod:machine", "interact_at", "{\"x\":99}", "state", "<absent>");
        assertEquals(2, prediction.independentContexts());
        assertTrue(prediction.outcomes().get("active") > 0.8);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        MechanismExplorer restored = explorer(new SemanticWorldModel(), new SkillLibrary(), fingerprint("a"));
        restored.importState(gson.fromJson(gson.toJson(source.exportState()), MechanismExplorer.State.class));
        assertEquals(prediction, restored.predict("mod:machine", "interact_at", "{\"x\":200}", "state", "<absent>"));
        MechanismExplorer changed = explorer(new SemanticWorldModel(), new SkillLibrary(), fingerprint("b"));
        changed.importState(source.exportState());
        assertEquals(0, changed.predict("mod:machine", "interact_at", "{}", "state", "<absent>").independentContexts());
        restored.importState(null);
        assertEquals(0, restored.predict("mod:machine", "interact_at", "{}", "state", "<absent>").independentContexts());
    }

    @Test void transferredAdapterVerifiesTheNewTargetInsteadOfTheOldTarget() {
        SemanticWorldModel world = new SemanticWorldModel();
        SkillLibrary skills = new SkillLibrary();
        MechanismExplorer explorer = explorer(world, skills, fingerprint("a"));
        supportMachine(explorer, world, "machine:one", "ctx-one", 10);
        supportMachine(explorer, world, "machine:two", "ctx-two", 100);
        String adapter = explorer.knowledgeBase().rules().iterator().next().adapterSkill();
        List<Integer> positions = new ArrayList<>();
        var runtime = new com.mineagent.engine.skill.SkillRuntime(skills, world,
                (id, tool, args) -> {
                    positions.add(args.get("x").getAsInt());
                    world.observe("machine:two", "state", "active", null, 1.0,
                            "server", id, 201, 40, false);
                    return new com.mineagent.engine.skill.SkillRuntime.DispatchResult(true, false, true, "accepted");
                }, ignored -> {});
        var overrides = com.google.gson.JsonParser.parseString(
                "{\"bindings\":{\"target_x\":99,\"effect_subject\":\"machine:three\"}}").getAsJsonObject();
        assertTrue(runtime.start(adapter, overrides, 200).accepted());
        runtime.tick(201);
        assertEquals(List.of(99), positions);
        assertEquals(com.mineagent.engine.skill.SkillRuntime.Status.VERIFYING, runtime.snapshot().status());
        world.observe("machine:three", "state", "active", null, 1.0, "server", null, 202, 40, false);
        runtime.tick(202);
        assertEquals(com.mineagent.engine.skill.SkillRuntime.Status.SUCCEEDED, runtime.snapshot().status());
    }

    @Test void curriculumIsRelevantBudgetedAndDoesNotClaimBodyOwnership() {
        MechanismExplorer explorer = explorer(new SemanticWorldModel(), new SkillLibrary(), fingerprint("a"));
        var profile = new MechanismKnowledgeBase.Profile("mod:machine", "mod", MechanismKnowledgeBase.Kind.BLOCK,
                java.util.Map.of("function", "processing"), fingerprint("a"), 1, 1);
        explorer.importState(new MechanismExplorer.State(List.of(),
                new MechanismKnowledgeBase.State(List.of(profile), List.of(), 1), List.of(), 1));
        assertEquals(1, explorer.curriculum("mod:machine", 2, 5).size());
        assertTrue(explorer.curriculum("ocean fishing", 2, 5).isEmpty());
        assertTrue(explorer.all().isEmpty());
    }

    @Test void renamingTheSameSetupCannotConfirmAMechanism() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = explorer(world, new SkillLibrary(), fingerprint("a"));
        world.observe("machine:one", "state", "off", null, 0.9, "server", null, 1, 40, false);
        supportMachine(explorer, world, "machine:one", "invented-context-one", 10);
        world.observe("machine:one", "state", "off", null, 0.9, "server", null, 50, 40, false);
        supportMachine(explorer, world, "machine:one", "invented-context-two", 60);
        var rule = explorer.knowledgeBase().rules().iterator().next();
        assertEquals(1, rule.supports());
        assertEquals(MechanismKnowledgeBase.RuleStatus.CANDIDATE, rule.status());
        assertNull(rule.adapterSkill());
    }

    @Test void legacyCallerNamedContextsRequireRevalidationAndCannotRestoreAnAdapter() {
        SemanticWorldModel world = new SemanticWorldModel();
        SkillLibrary skills = new SkillLibrary();
        MechanismExplorer source = explorer(world, skills, fingerprint("a"));
        supportMachine(source, world, "machine:one", "legacy-one", 10);
        supportMachine(source, world, "machine:two", "legacy-two", 100);
        String adapter = source.knowledgeBase().rules().iterator().next().adapterSkill();
        assertTrue(skills.get(adapter).isPresent());
        var gson = new com.google.gson.Gson();
        var legacy = gson.toJsonTree(source.exportState()).getAsJsonObject();
        legacy.getAsJsonObject("knowledge").remove("contextVersion");
        source.importState(gson.fromJson(legacy, MechanismExplorer.State.class));
        var restored = source.knowledgeBase().rules().iterator().next();
        assertEquals(0, restored.supports());
        assertEquals(MechanismKnowledgeBase.RuleStatus.CANDIDATE, restored.status());
        assertTrue(skills.get(adapter).isEmpty());
    }

    @Test void learnerAuditFactsCannotReplaceTheFrozenProbeObservation() {
        SemanticWorldModel world = new SemanticWorldModel();
        MechanismExplorer explorer = explorer(world, new SkillLibrary(), fingerprint("a"));
        var proposal = explorer.propose("mod:machine", "observation changes the declared value",
                "look_around", "{}", "mechanism:mod:machine", "hypothesis_status", "external_value",
                MechanismExplorer.Risk.LOW, 1);
        assertTrue(proposal.accepted());
        explorer.onToolDispatched("probe", "look_around", "{}", 2);
        world.observe("mechanism:mod:machine", "hypothesis_status", "external_value", null,
                1.0, "server", "probe", 3, 40, false);
        explorer.onToolResult("probe", "look_around", true, false, "{\"success\":true}", 3);
        assertEquals(MechanismExplorer.Status.SUPPORTED, explorer.get(proposal.experiment().id()).status());
        var prediction = explorer.predict("mod:machine", "look_around", "{}", "hypothesis_status", "<absent>");
        assertTrue(prediction.outcomes().containsKey("external_value"));
        assertFalse(prediction.outcomes().containsKey("supported"));
    }

    @Test void unsupportedProbeShapesAreRejectedBeforeTheyCanEnterTheEvidenceLifecycle() {
        MechanismExplorer explorer = explorer(new SemanticWorldModel(), new SkillLibrary(), fingerprint("a"));
        String deep = "{\"nested\":".repeat(20) + "1" + "}".repeat(20);
        assertFalse(explorer.propose("mod:machine", "observation", "look_around", deep,
                "tool:look_around", "outcome", "success", MechanismExplorer.Risk.LOW, 1).accepted());
        var candidate = new MechanismExplorer.ProbeCandidate("look_around", deep,
                MechanismExplorer.Risk.LOW, true, null, "{}", 1, 0.9);
        assertNull(explorer.recommend(List.of(candidate), "mod:machine", 1).candidate());
        var oversized = new MechanismExplorer.ProbeCandidate("look_around",
                "{\"text\":\"" + "x".repeat(2_048) + "\"}",
                MechanismExplorer.Risk.LOW, true, null, "{}", 1, 0.9);
        assertNull(explorer.recommend(List.of(oversized), "mod:machine", 1).candidate());
        assertTrue(explorer.all().isEmpty());
    }

    private static MechanismExplorer explorer(SemanticWorldModel world,
                                              SkillLibrary skills,
                                              EnvironmentFingerprint fingerprint) {
        return new MechanismExplorer(world, (a, b, c, d) -> {}, skills, fingerprint,
                (id, tool, args) -> new MechanismExplorer.CompensationResult(
                        true, false, true, "restored"));
    }

    private static MechanismExplorer.Proposal proposeMachine(MechanismExplorer explorer,
                                                             String expectedSubject,
                                                             String context,
                                                             long tick) {
        return explorer.propose("mod:machine", "use changes state to active",
                "interact_at", "{\"x\":1}", expectedSubject, "state", "active",
                MechanismExplorer.Risk.MEDIUM, context, true,
                "close_gui", "{}", 4, 0.8, tick);
    }

    private static void supportMachine(MechanismExplorer explorer,
                                       SemanticWorldModel world,
                                       String expectedSubject,
                                       String context, long tick) {
        var proposal = proposeMachine(explorer, expectedSubject, context, tick);
        assertTrue(proposal.accepted());
        String call = "call-" + context;
        explorer.onToolDispatched(call, "interact_at", "{\"x\":1}", tick + 1L);
        world.observe(expectedSubject, "state", "active", null, 0.9,
                "inspect_gui", call, tick + 2L, 40L, false);
        explorer.onToolResult(call, "interact_at", true, false,
                "{\"success\":true}", tick + 2L);
    }

    private static EnvironmentFingerprint fingerprint(String revision) {
        return new EnvironmentFingerprint("1.21.1", "fabric",
                "mods-" + revision, "registries-" + revision);
    }
}
