package com.mineagent.api.agent.tool;

import java.util.*;

/**
 * Fluent builder for JSON Schema objects used in {@link Tool#parameterSchema()}.
 *
 * <pre>{@code
 * Map<String, Object> schema = Schema.object()
 *     .string("item_id", "The item to craft.")
 *     .optionalInteger("count", "How many (default 1).", 1, 256)
 *     .build();
 * }</pre>
 */
public final class Schema {

    private Schema() {}

    /** Start building an object schema. */
    public static Builder object() {
        return new Builder();
    }

    /** An empty schema (no parameters). */
    public static Map<String, Object> none() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    public static final class Builder {
        private final LinkedHashMap<String, Map<String, Object>> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        /** Add a required string property. */
        public Builder string(String name, String description) {
            properties.put(name, Map.of("type", "string", "description", description));
            required.add(name);
            return this;
        }

        /** Add an optional string property. */
        public Builder optionalString(String name, String description) {
            properties.put(name, Map.of("type", List.of("string", "null"), "description", description));
            return this;
        }

        /** Add a required integer property with optional bounds. */
        public Builder integer(String name, String description, int min, int max) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "integer");
            prop.put("description", description);
            if (min != Integer.MIN_VALUE) prop.put("minimum", min);
            if (max != Integer.MAX_VALUE) prop.put("maximum", max);
            properties.put(name, Collections.unmodifiableMap(prop));
            required.add(name);
            return this;
        }

        /** Add an optional integer property. */
        public Builder optionalInteger(String name, String description, int min, int max) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", List.of("integer", "null"));
            prop.put("description", description);
            if (min != Integer.MIN_VALUE) prop.put("minimum", min);
            if (max != Integer.MAX_VALUE) prop.put("maximum", max);
            properties.put(name, Collections.unmodifiableMap(prop));
            return this;
        }

        /** Add a required number property. */
        public Builder number(String name, String description) {
            properties.put(name, Map.of("type", "number", "description", description));
            required.add(name);
            return this;
        }

        /** Add an optional (nullable) number property. */
        public Builder nullableNumber(String name, String description) {
            properties.put(name, Map.of("type", List.of("number", "null"), "description", description));
            return this;
        }

        /** Add a required string-array property. */
        public Builder stringArray(String name, String description, int minItems) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("description", description);
            prop.put("items", Map.of("type", "string"));
            if (minItems > 0) prop.put("minItems", minItems);
            properties.put(name, Collections.unmodifiableMap(prop));
            required.add(name);
            return this;
        }

        /** Add a required array with an explicit item schema. */
        public Builder array(String name, String description,
                             Map<String, Object> itemSchema, int minItems) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("description", description);
            prop.put("items", Map.copyOf(itemSchema));
            if (minItems > 0) prop.put("minItems", minItems);
            properties.put(name, Collections.unmodifiableMap(prop));
            required.add(name);
            return this;
        }

        /** Build the final schema map. */
        public Map<String, Object> build() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "object");
            root.put("properties", Collections.unmodifiableMap(properties));
            root.put("required", List.copyOf(required));
            root.put("additionalProperties", false);
            return Collections.unmodifiableMap(root);
        }
    }
}
