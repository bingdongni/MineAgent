package com.mineagent.engine.exploration;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PredictiveLearningTest {
    @Test void learnsAcrossLocationsWithoutMergingDifferentControlsOrConditions() {
        PredictiveLearning model = new PredictiveLearning();
        observe(model, "a", 1, "off", "on", 1);
        observe(model, "b", 20, "off", "on", 2);
        var prediction = model.predict("mod:machine", "interact_at", "{\"x\":90,\"face\":\"up\"}",
                "state", "off", "env-a");
        assertEquals(2, prediction.independentContexts());
        assertTrue(prediction.outcomes().get("on") > 0.8);
        assertFalse(prediction.needsRevalidation());
        assertEquals(0, model.predict("mod:machine", "interact_at", "{\"x\":90,\"face\":\"down\"}",
                "state", "off", "env-a").independentContexts());
        assertEquals(0, model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}",
                "state", "jammed", "env-a").independentContexts());
        assertEquals(0, model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}",
                "state", "off", "env-b").independentContexts());
    }

    @Test void duplicateCallbacksAndRepeatedSetupCannotInventIndependentEvidence() {
        PredictiveLearning model = new PredictiveLearning();
        observe(model, "one", 1, "off", "on", 1);
        assertFalse(model.observe("one", "mod:machine", "interact_at", "{\"face\":\"up\"}",
                "state", "off", "on", "another-context", "env-a", 2));
        assertFalse(model.observe("two", "mod:machine", "interact_at", "{\"x\":1,\"face\":\"up\"}",
                "state", "off", "on", "position:1", "env-a", 2));
        assertEquals(1, model.size());
        assertTrue(model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}",
                "state", "off", "env-a").needsRevalidation());
        assertFalse(model.observe("old", "mod:machine", "interact_at", "{\"face\":\"up\"}",
                "state", "off", "on", "position:1", "env-a", 0));
    }

    @Test void contradictionsIncreaseUncertaintyAndTriggerRevalidation() {
        PredictiveLearning model = new PredictiveLearning();
        for (int index = 0; index < 8; index++) observe(model, "ok-" + index, index, "off", "on", index);
        var before = model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}", "state", "off", "env-a");
        observe(model, "changed", 10, "off", "jammed", 20);
        var after = model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}", "state", "off", "env-a");
        assertTrue(after.uncertaintyBits() > before.uncertaintyBits());
        assertTrue(after.needsRevalidation());
        assertTrue(after.outcomes().containsKey("jammed"));
    }

    @Test void stableExperienceReducesExplorationCostAndSurvivesCheckpoint() {
        PredictiveLearning model = new PredictiveLearning();
        double cold = model.explorationValue("mod:machine", "interact_at", "{\"face\":\"up\"}", "env-a", 0.9);
        for (int index = 0; index < 32; index++) observe(model, "ok-" + index, index, "off", "on", index);
        assertTrue(model.explorationValue("mod:machine", "interact_at", "{\"face\":\"up\"}", "env-a", 0.9) < cold);
        Gson gson = new Gson();
        PredictiveLearning restored = new PredictiveLearning();
        restored.importState(gson.fromJson(gson.toJson(model.exportState()), PredictiveLearning.State.class));
        assertEquals(model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}", "state", "off", "env-a"),
                restored.predict("mod:machine", "interact_at", "{\"face\":\"up\"}", "state", "off", "env-a"));
        restored.importState(new PredictiveLearning.State(java.util.Arrays.asList(null,
                new PredictiveLearning.Trial("bad", "m", "t", "[]", "p", "", "v", "x", "e", 1, Double.NaN))));
        assertEquals(0, restored.size());
    }

    @Test void canonicalizesObjectsButRetainsArrayOrderAndRejectsInvalidInputs() {
        PredictiveLearning model = new PredictiveLearning();
        assertTrue(model.observe("one", "m", "t", "{\"settings\":{\"a\":1,\"b\":2},\"slots\":[1,2]}",
                "p", "", "ok", "ctx", "env", 1));
        assertEquals(1, model.predict("m", "t", "{\"slots\":[1,2],\"settings\":{\"b\":2,\"a\":1}}", "p", "", "env").independentContexts());
        assertEquals(0, model.predict("m", "t", "{\"slots\":[2,1],\"settings\":{\"a\":1,\"b\":2}}", "p", "", "env").independentContexts());
        assertThrows(IllegalArgumentException.class, () -> model.predict("m", "t", "[]", "p", "", "env"));
        assertTrue(model.observe("two", "m", "t", "{}", "p", "", "<unobserved>", "ctx2", "env", 2));
        assertTrue(model.predict("m", "t", "{}", "p", "", "env").outcomes().containsKey("<unobserved>"));
    }

    @Test void recentVerifiedContextsCanReplaceAnObsoletePredictionAfterRuleDrift() {
        PredictiveLearning model = new PredictiveLearning();
        for (int index = 0; index < 16; index++) observe(model, "old-" + index, index, "off", "on", index);
        for (int index = 0; index < 16; index++) observe(model, "new-" + index, index + 20, "off", "jammed", index + 20);
        var prediction = model.predict("mod:machine", "interact_at", "{\"face\":\"up\"}", "state", "off", "env-a");
        assertEquals(16, prediction.independentContexts());
        assertFalse(prediction.outcomes().containsKey("on"));
        assertFalse(prediction.needsRevalidation());
        assertEquals(32, model.size()); // Historical evidence remains inspectable in the checkpoint.
    }

    @Test void randomizedOutcomesRemainNormalizedFiniteAndBounded() {
        PredictiveLearning model = new PredictiveLearning();
        var cold = model.predict("m", "t", "{}", "state", "off", "env");
        assertTrue(cold.outcomes().isEmpty());
        assertEquals(1.0, cold.unseenProbability());
        assertEquals(0.0, cold.informationGainBits());
        assertTrue(cold.needsRevalidation());
        java.util.Random random = new java.util.Random(87123);
        for (int index = 0; index < 700; index++) {
            assertTrue(model.observe("trial-" + index, "m", "t", "{}", "state", "off",
                    "outcome-" + random.nextInt(4), "setup-" + index, "env", index));
            var prediction = model.predict("m", "t", "{}", "state", "off", "env");
            double total = prediction.unseenProbability()
                    + prediction.outcomes().values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(1.0, total, 1e-10);
            assertTrue(Double.isFinite(prediction.informationGainBits()));
            assertTrue(prediction.informationGainBits() >= 0);
            assertTrue(prediction.uncertaintyBits() >= 0);
            assertTrue(prediction.independentContexts() <= 16);
        }
        assertEquals(256, model.size());
        var oversized = new java.util.ArrayList<PredictiveLearning.Trial>();
        for (int repeat = 0; repeat < 5; repeat++) oversized.addAll(model.exportState().trials());
        model.importState(new PredictiveLearning.State(oversized));
        assertTrue(model.size() <= 256);
    }

    @Test void composesANewRouteFromSeparatelyLearnedTransitionsAndStopsOnCounterexamples() {
        PredictiveLearning model = new PredictiveLearning();
        for (int context = 0; context < 2; context++) {
            assertTrue(model.observe("prime-" + context, "m", "interact_at", "{\"operation\":\"prime\"}",
                    "state", "off", "ready", "prime-setup-" + context, "env", context));
            assertTrue(model.observe("process-" + context, "m", "interact_at", "{\"operation\":\"process\"}",
                    "state", "ready", "done", "process-setup-" + context, "env", context + 2));
        }
        var plan = model.plan("m", "state", "off", "done", "env", 4);
        assertTrue(plan.found());
        assertEquals(2, plan.steps().size());
        assertEquals("ready", plan.steps().getFirst().after());
        assertEquals("done", plan.steps().getLast().after());
        assertFalse(model.plan("m", "state", "off", "done", "env", 1).found());
        assertFalse(model.plan("m", "state", "off", "done", "changed-env", 4).found());
        assertFalse(model.plan("m", "state", "<absent>", "done", "env", 4).found());
        assertTrue(model.observe("contradiction", "m", "interact_at", "{\"operation\":\"prime\"}",
                "state", "off", "jammed", "different-setup", "env", 20));
        assertFalse(model.plan("m", "state", "off", "done", "env", 4).found());
    }

    @Test void planningDoesNotTreatSingleObservationsAsReusableTransitions() {
        PredictiveLearning model = new PredictiveLearning();
        assertTrue(model.observe("only", "m", "t", "{}", "state", "off", "on", "context", "env", 1));
        assertFalse(model.plan("m", "state", "off", "on", "env", 6).found());
        assertEquals(0, model.plan("m", "state", "on", "on", "env", 6).steps().size());
        assertThrows(IllegalArgumentException.class, () -> model.plan("m", "state", "off", "*", "env", 6));
    }

    private static void observe(PredictiveLearning model, String id, int x,
                                String before, String after, long tick) {
        assertTrue(model.observe(id, "mod:machine", "interact_at", "{\"x\":" + x + ",\"face\":\"up\"}",
                "state", before, after, "position:" + x, "env-a", tick));
    }
}
