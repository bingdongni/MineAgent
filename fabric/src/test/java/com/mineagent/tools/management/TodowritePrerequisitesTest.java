package com.mineagent.tools.management;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class TodowritePrerequisitesTest {
    @Test void malformedPrerequisitesAreRejectedBeforeAccessingTheCompanion() {
        for (String invalid : new String[]{"true", "[1]", "[{\"subject\":\"inventory:x\",\"predicate\":\"count\",\"value\":\"NaN\",\"comparison\":\"at_least\"}]"}) {
            JsonObject args = request(invalid);
            AtomicReference<String> result = new AtomicReference<>();
            new TodowriteTool().onServerCall("call", args, null, result::set);
            assertTrue(result.get().contains("error"));
            assertTrue(result.get().contains("prerequisites"));
        }
    }

    @Test void nonfiniteConfidenceCannotSilentlyDisableVerification() {
        JsonObject args = request("[{\"subject\":\"inventory:x\",\"predicate\":\"count\",\"value\":\"1\",\"comparison\":\"at_least\"}]");
        args.getAsJsonArray("todos").get(0).getAsJsonObject().getAsJsonArray("prerequisites")
                .get(0).getAsJsonObject().addProperty("minimum_confidence", Double.NaN);
        AtomicReference<String> result = new AtomicReference<>();
        new TodowriteTool().onServerCall("call", args, null, result::set);
        assertTrue(result.get().contains("invalid fields"));
    }

    @Test void schemaExposesLiveConditionsOnEachStep() {
        Map<?, ?> properties = (Map<?, ?>) new TodowriteTool().parameterSchema().get("properties");
        Map<?, ?> todos = (Map<?, ?>) properties.get("todos");
        Map<?, ?> step = (Map<?, ?>) todos.get("items");
        Map<?, ?> fields = (Map<?, ?>) step.get("properties");
        assertTrue(fields.containsKey("prerequisites"));
    }

    private static JsonObject request(String prerequisites) {
        return JsonParser.parseString("{\"todos\":[{\"id\":\"craft\",\"content\":\"craft\",\"priority\":\"medium\",\"status\":\"pending\",\"prerequisites\":"
                + prerequisites + "}]}").getAsJsonObject();
    }
}
