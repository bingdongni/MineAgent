package com.mineagent.engine.skill;

import com.google.gson.JsonObject;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkillRuntimeTest {
    @Test void asyncStepMustFinishBeforeNextStepDispatches() {
        SkillLibrary library = library("two_steps", """
                [
                  {"tool":"goto","args":{"x":1,"z":2}},
                  {"tool":"get_self_status","args":{}}
                ]
                """);
        SemanticWorldModel world = new SemanticWorldModel();
        List<String> dispatched = new ArrayList<>();
        List<SkillRuntime.Snapshot> completed = new ArrayList<>();
        SkillRuntime runtime = new SkillRuntime(library, world,
                (id, tool, args) -> {
                    dispatched.add(id + "=" + tool);
                    return new SkillRuntime.DispatchResult(true,
                            "goto".equals(tool), true, "accepted");
                }, completed::add);

        var start = runtime.start("two_steps", null, 10L);
        assertTrue(start.accepted());
        runtime.tick(11L);
        assertEquals(1, dispatched.size());
        runtime.tick(12L);
        assertEquals(1, dispatched.size());

        String taskId = dispatched.getFirst().substring(0,
                dispatched.getFirst().indexOf('='));
        assertTrue(runtime.onTaskFinished(taskId, TaskState.SUCCESS,
                "arrived", 13L));
        runtime.tick(14L);
        assertEquals(2, dispatched.size());
        assertEquals(SkillRuntime.Status.SUCCEEDED, runtime.snapshot().status());
        assertEquals(1, completed.size());
    }

    @Test void declaredEffectIsVerifiedFromNewWorldEvidence() {
        SkillLibrary library = library("craft_item", """
                [{"tool":"craft","args":{"item":"minecraft:stick"},
                  "expected_effects":[{"subject":"inventory:minecraft:stick",
                    "predicate":"count","value":"4","minimum_confidence":1.0}]}]
                """);
        SemanticWorldModel world = new SemanticWorldModel();
        SkillRuntime runtime = new SkillRuntime(library, world,
                (id, tool, args) -> new SkillRuntime.DispatchResult(
                        true, false, true, "crafted"), ignored -> {});

        runtime.start("craft_item", new JsonObject(), 20L);
        runtime.tick(21L);
        assertEquals(SkillRuntime.Status.VERIFYING, runtime.snapshot().status());
        world.observe("inventory:minecraft:stick", "count", "4", null,
                1.0, "inventory_snapshot", null, 22L, 40L, false);
        runtime.tick(22L);
        assertEquals(SkillRuntime.Status.SUCCEEDED, runtime.snapshot().status());
    }

    @Test void missingPreconditionStopsWithoutDispatch() {
        SkillLibrary library = library("guarded", """
                [{"tool":"craft","args":{},"preconditions":[
                  {"subject":"inventory:minecraft:diamond","predicate":"count","value":"1"}
                ]}]
                """);
        SemanticWorldModel world = new SemanticWorldModel();
        List<String> dispatched = new ArrayList<>();
        SkillRuntime runtime = new SkillRuntime(library, world,
                (id, tool, args) -> {
                    dispatched.add(tool);
                    return new SkillRuntime.DispatchResult(true, false, true, "ok");
                }, ignored -> {});

        runtime.start("guarded", null, 1L);
        runtime.tick(2L);
        assertFalse(runtime.active());
        assertEquals(SkillRuntime.Status.NEEDS_REPLAN, runtime.snapshot().status());
        assertTrue(dispatched.isEmpty());
    }

    private static SkillLibrary library(String name, String sequence) {
        SkillLibrary library = new SkillLibrary();
        library.registerSequence(name, name, name, sequence, true);
        return library;
    }
}
