package com.mineagent.engine.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.SemanticWorldModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/** Observable experiment assumptions. Neither names nor model assertions prove a condition. */
public final class LearningConditions {
    private LearningConditions() {}

    public static List<PlanGraph.GoalCondition> checked(List<PlanGraph.GoalCondition> conditions) {
        if (conditions == null) return List.of();
        if (conditions.size() > 16) throw new IllegalArgumentException("At most 16 total experiment prerequisites are supported");
        for (var condition : conditions) {
            if (condition == null || condition.subject().length() > 256
                    || condition.predicate().length() > 128 || condition.value().length() > 256
                    || condition.subject().equals("unknown")) {
                throw new IllegalArgumentException("Invalid experiment prerequisite");
            }
        }
        return List.copyOf(conditions);
    }

    public static String blocker(List<PlanGraph.GoalCondition> conditions, SemanticWorldModel world, long tick) {
        for (var condition : checked(conditions)) {
            var fact = world.find(condition.subject(), condition.predicate(), tick)
                    .filter(value -> value.confidenceAt(tick) >= condition.minimumConfidence());
            if (fact.isEmpty()) return "Observe prerequisite: " + condition.describe();
            if (!condition.matches(world, tick)) return "Restore prerequisite: " + condition.describe();
        }
        return null;
    }

    static String roleArguments(String arguments, String target, List<PlanGraph.GoalCondition> conditions) {
        boolean actor = target != null && target.startsWith("entity:") && checked(conditions).stream().anyMatch(
                condition -> condition.subject().equals(target) && condition.predicate().equals("entity_type")
                        && condition.comparison() == PlanGraph.Comparison.EQUALS);
        if (!actor) return arguments;
        JsonObject normalized = com.google.gson.JsonParser.parseString(arguments).getAsJsonObject();
        normalized.remove("entity_id"); normalized.remove("expected_uuid");
        return normalized.toString();
    }

    /** Normalize only the explicitly bound target; unrelated subjects retain their identities. */
    public static String signature(List<PlanGraph.GoalCondition> conditions, String target) {
        if (conditions == null || conditions.isEmpty()) return "";
        JsonArray values = new JsonArray();
        checked(conditions).stream().map(condition -> {
            JsonObject value = new JsonObject();
            value.addProperty("subject", condition.subject().equals(target) ? "$target" : condition.subject());
            value.addProperty("predicate", condition.predicate());
            value.addProperty("value", condition.value());
            value.addProperty("comparison", condition.comparison().name());
            value.addProperty("confidence", condition.minimumConfidence());
            return value;
        }).sorted(Comparator.comparing(JsonObject::toString)).forEach(values::add);
        return digest(values.toString());
    }

    static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String scope(EnvironmentFingerprint fingerprint,
                               List<PlanGraph.GoalCondition> conditions, String target) {
        String signature = signature(conditions, target);
        String effect = effectIdentity(target);
        return fingerprint + (effect.isEmpty() ? "" : "|effect=" + digest(effect))
                + (signature.isEmpty() ? "" : "|conditions=" + signature);
    }

    /** Inventory outputs and type profiles are registered identities, not interchangeable instances. */
    static String effectIdentity(String target) {
        return target != null && (target.startsWith("inventory:") || target.startsWith("profile:")) ? target : "";
    }

    public static String predictiveScope(EnvironmentFingerprint fingerprint,
            List<PlanGraph.GoalCondition> conditions, String target, String predicate) {
        return scope(fingerprint, checked(conditions).stream().filter(condition ->
                !(condition.subject().equals(target) && condition.predicate().equals(predicate))).toList(), target);
    }
}
