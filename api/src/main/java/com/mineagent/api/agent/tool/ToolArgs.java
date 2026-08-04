package com.mineagent.api.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.Locale;

/**
 * Utility for parsing tool arguments.
 * <p>
 * Provides safe, null-tolerant accessors for both the legacy
 * {@code Map<String, Object>} representation and the JSON representation used
 * by {@link Tool#onServerCall}. All getters return {@code null} (or a default)
 * when the key is absent, the value is JSON null, or the value cannot be coer
 * to the requested type — they never throw.
 *
 * <p>Robustness contract:
 * <ul>
 *   <li>Strings are accepted as-is; numbers/booleans are coerced via toString.</li>
 *   <li>Numbers accept numeric primitives and numeric strings.</li>
 *   <li>Booleans accept boolean primitives, "true"/"false" strings, and numeric 0/1.</li>
 *   <li>Arrays accept native JsonArray or a JSON-encoded array string.</li>
 * </ul>
 * This is critical because LLMs frequently pass parameters with slightly
 * wrong types (e.g. an integer as a string, or a JSON array as a string).
 */
public final class ToolArgs {

    private ToolArgs() {}

    // ----------------------------------------------------------------------
    // Map<String, Object> accessors (legacy)
    // ----------------------------------------------------------------------

    /** Parse a boolean from args, with default. */
    public static boolean parseBool(Map<String, Object> args, String key, boolean defaultVal) {
        if (args == null) return defaultVal;
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }

    /** Parse an int from args, with default. */
    public static int parseInt(Map<String, Object> args, String key, int defaultVal) {
        if (args == null) return defaultVal;
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    /** Parse a double from args, with default. */
    public static double parseDouble(Map<String, Object> args, String key, double defaultVal) {
        if (args == null) return defaultVal;
        Object val = args.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    /** Parse a string from args, with default. */
    public static String parseString(Map<String, Object> args, String key, String defaultVal) {
        if (args == null) return defaultVal;
        Object val = args.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    // ----------------------------------------------------------------------
    // JsonObject accessors
    // ----------------------------------------------------------------------

    /**
     * Return the raw JsonElement for {@code key}, or {@code null} if the key
     * is absent or the value is JSON null.
     */
    public static JsonElement getElement(JsonObject args, String key) {
        if (args == null) return null;
        if (!args.has(key)) return null;
        JsonElement e = args.get(key);
        return e.isJsonNull() ? null : e;
    }

    /**
     * Return true if {@code key} is present and not JSON null.
     */
    public static boolean has(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull();
    }

    /**
     * Get a string value. Accepts strings, numbers, and booleans (coerced
     * to string). Returns {@code defaultVal} if absent or null.
     */
    public static String getString(JsonObject args, String key, String defaultVal) {
        JsonElement e = getElement(args, key);
        if (e == null) return defaultVal;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return Boolean.toString(p.getAsBoolean());
            if (p.isNumber()) return p.getAsNumber().toString();
        }
        return defaultVal;
    }

    /**
     * Get a string value, or {@code null} if absent.
     * @see #getString(JsonObject, String, String)
     */
    public static String getString(JsonObject args, String key) {
        return getString(args, key, null);
    }

    /**
     * Get an int value. Accepts numbers and numeric strings. Returns
     * {@code defaultVal} if absent or unparseable.
     */
    public static int getInt(JsonObject args, String key, int defaultVal) {
        JsonElement e = getElement(args, key);
        if (e == null) return defaultVal;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsNumber().intValue();
            if (p.isString()) {
                try { return Integer.parseInt(p.getAsString().trim()); }
                catch (NumberFormatException ex) { return defaultVal; }
            }
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
        }
        return defaultVal;
    }

    /**
     * Get an int value, or {@code null} if absent.
     */
    public static Integer getIntOrNull(JsonObject args, String key) {
        JsonElement e = getElement(args, key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber() || p.isString()) {
                return new java.math.BigDecimal(p.getAsString().trim()).intValueExact();
            }
        } catch (NumberFormatException | ArithmeticException ignored) {}
        return null;
    }

    /**
     * Get a double value. Accepts numbers and numeric strings. Returns
     * {@code defaultVal} if absent or unparseable.
     */
    public static double getDouble(JsonObject args, String key, double defaultVal) {
        JsonElement e = getElement(args, key);
        if (e == null) return defaultVal;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsNumber().doubleValue();
            if (p.isString()) {
                try { return Double.parseDouble(p.getAsString().trim()); }
                catch (NumberFormatException ex) { return defaultVal; }
            }
        }
        return defaultVal;
    }

    /**
     * Get a double value, or {@code null} if absent.
     */
    public static Double getDoubleOrNull(JsonObject args, String key) {
        JsonElement e = getElement(args, key);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber() || p.isString()) {
                double value = Double.parseDouble(p.getAsString().trim());
                return Double.isFinite(value) ? value : null;
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /**
     * Get a boolean value. Accepts booleans and "true"/"false" strings.
     * Returns {@code defaultVal} if absent or unparseable.
     */
    public static boolean getBool(JsonObject args, String key, boolean defaultVal) {
        JsonElement e = getElement(args, key);
        if (e == null) return defaultVal;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) {
                String s = p.getAsString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("1")) return true;
                if (s.equals("false") || s.equals("0")) return false;
            }
            if (p.isNumber()) return p.getAsNumber().intValue() != 0;
        }
        return defaultVal;
    }

    /**
     * Get a boolean value, or {@code null} if absent.
     */
    public static Boolean getBoolOrNull(JsonObject args, String key) {
        JsonElement e = getElement(args, key);
        if (e == null || !e.isJsonPrimitive()) return null;
        var p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber().intValue() != 0;
        if (p.isString()) {
            String value = p.getAsString().trim().toLowerCase(Locale.ROOT);
            if (value.equals("true") || value.equals("1")) return true;
            if (value.equals("false") || value.equals("0")) return false;
        }
        return null;
    }

    /**
     * Get a JsonArray value. Accepts a native JsonArray or a JSON-encoded
     * array string (the LLM frequently passes arrays as strings). Returns
     * {@code null} if the value is absent or cannot be parsed as an array.
     */
    public static JsonArray getArray(JsonObject args, String key) {
        JsonElement e = getElement(args, key);
        if (e == null) return null;
        if (e.isJsonArray()) return e.getAsJsonArray();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = JsonParser.parseString(e.getAsString());
                if (parsed.isJsonArray()) return parsed.getAsJsonArray();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Get a nested JsonObject value. Accepts a native object or a JSON-
     * encoded object string. Returns {@code null} if absent or unparseable.
     */
    public static JsonObject getObject(JsonObject args, String key) {
        JsonElement e = getElement(args, key);
        if (e == null) return null;
        if (e.isJsonObject()) return e.getAsJsonObject();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = JsonParser.parseString(e.getAsString());
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Escape a string for embedding in a JSON string literal.
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Build structured error JSON so arbitrary exception text stays escaped. */
    public static String errorJson(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message == null ? "Unknown error" : message);
        return result.toString();
    }
}
