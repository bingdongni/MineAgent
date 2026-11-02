package com.mineagent.engine.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

final class SkillBindingsTest {
    @Test void preservesTypesAndResolvesTheSameTargetInActionsAndConditions() {
        var template = JsonParser.parseString("""
                {"args":{"x":{"$binding":"target_x"}},
                 "expected_effects":[{"subject":{"$binding":"target_subject"}}]}
                """);
        var bindings = JsonParser.parseString("{\"target_x\":42,\"target_subject\":\"machine:new\"}").getAsJsonObject();
        var resolved = SkillBindings.resolve(template, bindings).getAsJsonObject();
        assertTrue(resolved.getAsJsonObject("args").getAsJsonPrimitive("x").isNumber());
        assertEquals(42, resolved.getAsJsonObject("args").get("x").getAsInt());
        assertEquals("machine:new", resolved.getAsJsonArray("expected_effects").get(0)
                .getAsJsonObject().get("subject").getAsString());
        assertTrue(template.getAsJsonObject().getAsJsonObject("args").get("x").isJsonObject());
    }

    @Test void rejectsMissingCyclicAndMistypedParametersBeforeDispatch() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillBindings.resolve(SkillBindings.reference("missing"), new JsonObject()));
        var cyclic = JsonParser.parseString("{\"one\":{\"$binding\":\"two\"},\"two\":{\"$binding\":\"one\"}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> SkillBindings.resolve(SkillBindings.reference("one"), cyclic));

        SkillLibrary library = new SkillLibrary();
        library.registerSequence("bound", "bound", "bound", """
                [{"tool":"goto","bindings":{"target_x":1},"args":{"x":{"$binding":"target_x"},"z":2}}]
                """, true);
        var dispatched = new ArrayList<String>();
        var runtime = new SkillRuntime(library, new com.mineagent.engine.world.SemanticWorldModel(),
                (id, tool, arguments) -> { dispatched.add(tool); return new SkillRuntime.DispatchResult(true, true, true, "ok"); },
                ignored -> {});
        assertFalse(runtime.start("bound", JsonParser.parseString("{\"bindings\":{\"target_x\":\"wrong\"}}").getAsJsonObject(), 1).accepted());
        assertFalse(runtime.start("bound", JsonParser.parseString("{\"bindings\":{\"target_X\":99}}").getAsJsonObject(), 1).accepted());
        assertTrue(dispatched.isEmpty());
    }

    @Test void legacyOverridesAndLiteralStringsRemainCompatible() {
        var value = JsonParser.parseString("{\"literal\":\"${do_not_evaluate}\"}");
        assertEquals(value, SkillBindings.resolve(value, new JsonObject()));
        SkillLibrary library = new SkillLibrary();
        library.registerSequence("legacy", "legacy", "legacy", "[{\"tool\":\"goto\",\"args\":{\"x\":1,\"z\":2}}]", true);
        var positions = new ArrayList<Integer>();
        var runtime = new SkillRuntime(library, new com.mineagent.engine.world.SemanticWorldModel(),
                (id, tool, arguments) -> { positions.add(arguments.get("x").getAsInt()); return new SkillRuntime.DispatchResult(true, false, true, "ok"); },
                ignored -> {});
        assertTrue(runtime.start("legacy", JsonParser.parseString("{\"0\":{\"x\":99}}").getAsJsonObject(), 1).accepted());
        runtime.tick(2);
        assertEquals(java.util.List.of(99), positions);
    }
}
