package com.mineagent.engine.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/** Typed, literal bindings shared by action parameters and their verification conditions. */
public final class SkillBindings {
    private static final int MAX_DEPTH = 16;
    private SkillBindings() {}

    public static JsonObject reference(String name) {
        JsonObject reference = new JsonObject();
        reference.addProperty("$binding", name);
        return reference;
    }

    public static JsonObject defaults(JsonArray steps) {
        JsonObject result = new JsonObject();
        if (!steps.isEmpty() && steps.get(0).isJsonObject()) {
            JsonElement declared = steps.get(0).getAsJsonObject().get("bindings");
            if (declared != null) {
                if (!declared.isJsonObject()) throw new IllegalArgumentException("bindings must be an object");
                if (declared.getAsJsonObject().size() > 64) throw new IllegalArgumentException("Too many skill bindings");
                declared.getAsJsonObject().asMap().forEach((name, value) -> {
                    if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) {
                        throw new IllegalArgumentException("Invalid skill binding name: " + name);
                    }
                    result.add(name, value.deepCopy());
                });
            }
        }
        return result;
    }

    public static JsonElement resolve(JsonElement source, JsonObject bindings) {
        return resolve(source, bindings, new HashSet<>(), 0);
    }

    public static void applyOverrides(JsonObject bindings, JsonObject overrides) {
        for (var entry : overrides.entrySet()) {
            String name = entry.getKey();
            JsonElement incoming = entry.getValue();
            if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || incoming.isJsonNull()
                    || !bindings.has(name)) {
                throw new IllegalArgumentException("Invalid skill binding override: " + name);
            }
            JsonElement previous = bindings.get(name);
            if (previous != null && previous.isJsonPrimitive()) {
                if (!incoming.isJsonPrimitive()
                        || previous.getAsJsonPrimitive().isNumber() != incoming.getAsJsonPrimitive().isNumber()
                        || previous.getAsJsonPrimitive().isString() != incoming.getAsJsonPrimitive().isString()
                        || previous.getAsJsonPrimitive().isBoolean() != incoming.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("Skill binding type changed: " + name);
                }
                if (incoming.getAsJsonPrimitive().isNumber()
                        && !Double.isFinite(incoming.getAsDouble())) {
                    throw new IllegalArgumentException("Skill binding is not finite: " + name);
                }
            }
            bindings.add(name, incoming.deepCopy());
        }
    }

    private static JsonElement resolve(JsonElement source, JsonObject bindings,
                                        Set<String> resolving, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Skill bindings are too deeply nested");
        if (source.isJsonObject()) {
            JsonObject object = source.getAsJsonObject();
            if (object.has("$binding")) {
                if (object.size() != 1 || !object.get("$binding").isJsonPrimitive()
                        || !object.getAsJsonPrimitive("$binding").isString()) {
                    throw new IllegalArgumentException("A binding reference must contain only a string $binding");
                }
                String name = object.get("$binding").getAsString();
                if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || !bindings.has(name)
                        || bindings.get(name).isJsonNull()) {
                    throw new IllegalArgumentException("Missing or invalid skill binding: " + name);
                }
                if (!resolving.add(name)) throw new IllegalArgumentException("Cyclic skill binding: " + name);
                JsonElement resolved = resolve(bindings.get(name), bindings, resolving, depth + 1);
                resolving.remove(name);
                return resolved;
            }
            JsonObject result = new JsonObject();
            object.asMap().forEach((name, value) -> result.add(name,
                    resolve(value, bindings, resolving, depth + 1)));
            return result;
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            source.getAsJsonArray().forEach(value -> result.add(resolve(value, bindings, resolving, depth + 1)));
            return result;
        }
        return source.deepCopy();
    }
}
