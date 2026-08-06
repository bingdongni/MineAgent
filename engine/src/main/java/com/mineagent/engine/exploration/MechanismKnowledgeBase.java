package com.mineagent.engine.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.engine.memory.TextSimilarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bounded evidence store for black-box mod-mechanism learning.
 *
 * <p>The store deliberately learns only observable contracts: registered
 * identities, bounded menu/recipe structure and tool-visible state changes.
 * It never serializes arbitrary NBT trees or assumes access to a mod's private
 * Java API.  Rules require independent supporting contexts and are invalidated
 * when their environment changes or verified execution contradicts them.
 */
public final class MechanismKnowledgeBase {
    public enum Kind { BLOCK, ITEM, ENTITY, MENU, RECIPE, UNKNOWN }
    public enum Novelty { UNKNOWN, BUILTIN_UNVERIFIED, OBSERVED_UNVERIFIED, VERIFIED, STALE }
    public enum RuleStatus { CANDIDATE, CONFIRMED, REFUTED, STALE }

    public record Profile(String subject, String namespace, Kind kind,
                          Map<String, String> attributes,
                          EnvironmentFingerprint fingerprint,
                          int observations, long lastObservedTick) {
        public Profile {
            subject = normalize(subject, "unknown");
            namespace = normalize(namespace, namespaceOf(subject));
            kind = kind == null ? Kind.UNKNOWN : kind;
            attributes = boundedAttributes(attributes);
            fingerprint = fingerprint == null
                    ? EnvironmentFingerprint.unknown() : fingerprint;
            observations = Math.max(1, observations);
            lastObservedTick = Math.max(0L, lastObservedTick);
        }
    }

    public record Rule(String id, String subject, String hypothesis,
                       String probeTool, String probeArguments,
                       String expectedSubject, String expectedPredicate,
                       String expectedValue, String compensationTool,
                       String compensationArguments,
                       Set<String> supportingContexts,
                       Set<String> refutingContexts,
                       int supports, int refutations, int inconclusive,
                       double confidence, RuleStatus status,
                       EnvironmentFingerprint fingerprint,
                       String adapterSkill, long updatedTick) {
        public Rule {
            id = normalize(id, "rule");
            subject = normalize(subject, "unknown");
            hypothesis = normalize(hypothesis, "unknown behavior");
            probeTool = normalize(probeTool, "look_around");
            probeArguments = normalize(probeArguments, "{}");
            expectedSubject = normalize(expectedSubject, "tool:" + probeTool);
            expectedPredicate = normalize(expectedPredicate, "outcome");
            expectedValue = normalize(expectedValue, "success");
            compensationTool = blankToNull(compensationTool);
            compensationArguments = normalize(compensationArguments, "{}");
            supportingContexts = boundedSet(supportingContexts);
            refutingContexts = boundedSet(refutingContexts);
            supports = Math.max(0, supports);
            refutations = Math.max(0, refutations);
            inconclusive = Math.max(0, inconclusive);
            confidence = unit(confidence);
            status = status == null ? RuleStatus.CANDIDATE : status;
            fingerprint = fingerprint == null
                    ? EnvironmentFingerprint.unknown() : fingerprint;
            adapterSkill = blankToNull(adapterSkill);
            updatedTick = Math.max(0L, updatedTick);
        }

        public boolean reusable(EnvironmentFingerprint current) {
            return status == RuleStatus.CONFIRMED && fingerprint.compatible(current)
                    && supports >= 2 && supportingContexts.size() >= 2
                    && confidence >= 0.7;
        }
    }

    public record State(List<Profile> profiles, List<Rule> rules, long revision) {}

    private static final int MAX_PROFILES = 256;
    private static final int MAX_RULES = 256;
    private static final int MAX_ATTRIBUTES = 48;
    private final LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
    private EnvironmentFingerprint currentFingerprint;
    private long revision;

    public MechanismKnowledgeBase(EnvironmentFingerprint currentFingerprint) {
        this.currentFingerprint = currentFingerprint == null
                ? EnvironmentFingerprint.unknown() : currentFingerprint;
    }

    public synchronized EnvironmentFingerprint currentFingerprint() {
        return currentFingerprint;
    }

    public synchronized Novelty novelty(String subject) {
        String key = canonical(subject);
        boolean verified = rules.values().stream().anyMatch(rule ->
                canonical(rule.subject()).equals(key) && rule.reusable(currentFingerprint));
        if (verified) return Novelty.VERIFIED;
        boolean stale = rules.values().stream().anyMatch(rule ->
                canonical(rule.subject()).equals(key)
                        && !rule.fingerprint().compatible(currentFingerprint));
        if (stale) return Novelty.STALE;
        Profile profile = profiles.get(key);
        String namespace = profile == null ? namespaceOf(subject) : profile.namespace();
        if ("minecraft".equals(namespace)) return Novelty.BUILTIN_UNVERIFIED;
        return profiles.containsKey(key) ? Novelty.OBSERVED_UNVERIFIED : Novelty.UNKNOWN;
    }

    public synchronized Profile profile(String subject) {
        return profiles.get(canonical(subject));
    }

    /** Extract only bounded fields with stable planning meaning. */
    public synchronized List<Profile> observeToolResult(String toolName,
                                                        String rawJson,
                                                        long gameTick) {
        JsonObject root = parseObject(rawJson);
        if (root == null) return List.of();
        ArrayList<Profile> updated = new ArrayList<>();
        switch (canonical(toolName)) {
            case "look_around" -> observeSituationResult(root, gameTick, updated);
            case "inspect_block" -> {
                String id = primitive(root, "block_id");
                if (id != null) updated.add(upsert(id, Kind.BLOCK,
                        blockAttributes(root), gameTick, true));
            }
            case "inspect_gui" -> {
                String menu = primitive(root, "menu_type_id");
                if (menu == null) menu = primitive(root, "container_type");
                if (menu != null) updated.add(upsert("menu:" + menu, Kind.MENU,
                        menuAttributes(root), gameTick, true));
            }
            case "lookup_recipe" -> {
                JsonArray results = array(root, "results");
                if (results != null) {
                    for (JsonElement element : results) {
                        if (updated.size() >= 12 || !element.isJsonObject()) break;
                        JsonObject recipe = element.getAsJsonObject();
                        String id = primitive(recipe, "recipe_id");
                        if (id != null) {
                            updated.add(upsert(id, Kind.RECIPE,
                                    recipeAttributes(recipe), gameTick, true));
                            String output = primitive(recipe, "result");
                            if (output != null && updated.size() < 12) {
                                updated.add(upsert(output, Kind.ITEM,
                                        Map.of("produced_by", id), gameTick));
                            }
                        }
                    }
                }
            }
            default -> {
                for (String key : List.of("block_id", "item_id", "entity_id", "recipe_id")) {
                    String id = primitive(root, key);
                    if (id == null || !id.contains(":")) continue;
                    Kind kind = key.startsWith("block") ? Kind.BLOCK
                            : key.startsWith("item") ? Kind.ITEM
                            : key.startsWith("entity") ? Kind.ENTITY : Kind.RECIPE;
                    updated.add(upsert(id, kind, primitiveAttributes(root), gameTick));
                }
            }
        }
        trim();
        return List.copyOf(updated);
    }

    private void observeSituationResult(JsonObject root, long gameTick,
                                        List<Profile> updated) {
        JsonArray entities = array(root, "entities");
        if (entities != null) for (JsonElement element : entities) {
            if (updated.size() >= 24 || !element.isJsonObject()) break;
            JsonObject entity = element.getAsJsonObject();
            String type = primitive(entity, "type");
            if (type != null) updated.add(upsert(type, Kind.ENTITY,
                    selectedAttributes(entity, List.of("activity", "line_of_sight",
                            "immediate_threat", "health", "max_health")), gameTick));
        }
        JsonArray blocks = array(root, "notable_blocks");
        if (blocks != null) for (JsonElement element : blocks) {
            if (updated.size() >= 24 || !element.isJsonObject()) break;
            JsonObject block = element.getAsJsonObject();
            String id = primitive(block, "block");
            if (id != null) updated.add(upsert(id, Kind.BLOCK,
                    selectedAttributes(block, List.of("kind", "item_form",
                            "block_entity_type")), gameTick));
        }
        JsonArray drops = array(root, "dropped_items");
        if (drops != null) for (JsonElement element : drops) {
            if (updated.size() >= 24 || !element.isJsonObject()) break;
            JsonObject drop = element.getAsJsonObject();
            String item = primitive(drop, "item");
            if (item != null) updated.add(upsert(item, Kind.ITEM,
                    selectedAttributes(drop, List.of("count")), gameTick));
        }
    }

    public synchronized Rule record(MechanismExplorer.Experiment experiment,
                                    MechanismExplorer.Status outcome,
                                    String evidence, long gameTick) {
        if (experiment == null) return null;
        String id = ruleId(experiment.subject(), experiment.hypothesis(),
                experiment.probeTool(), experiment.expectedPredicate(),
                experiment.expectedValue(), currentFingerprint);
        Rule old = rules.get(id);
        int supports = old == null ? 0 : old.supports();
        int refutations = old == null ? 0 : old.refutations();
        int inconclusive = old == null ? 0 : old.inconclusive();
        LinkedHashSet<String> contexts = new LinkedHashSet<>(old == null
                ? Set.of() : old.supportingContexts());
        LinkedHashSet<String> counterContexts = new LinkedHashSet<>(old == null
                ? Set.of() : old.refutingContexts());
        if (outcome == MechanismExplorer.Status.SUPPORTED) {
            // Repeating the same setup is useful reliability evidence, but it
            // is not an independent context and cannot alone confirm a rule.
            if (contexts.add(experiment.contextKey())) supports++;
        } else if (outcome == MechanismExplorer.Status.REFUTED) {
            if (counterContexts.add(experiment.contextKey())) refutations++;
        } else {
            inconclusive++;
        }
        double confidence = (1.0 + supports) / (2.0 + supports + refutations);
        RuleStatus status;
        EnvironmentFingerprint learnedIn = old == null
                ? currentFingerprint : old.fingerprint();
        if (!learnedIn.compatible(currentFingerprint)) status = RuleStatus.STALE;
        else if (refutations > 0 && (refutations >= supports || confidence < 0.7)) {
            status = RuleStatus.REFUTED;
        } else if (supports >= 2 && contexts.size() >= 2 && confidence >= 0.7) {
            status = RuleStatus.CONFIRMED;
        } else status = RuleStatus.CANDIDATE;

        Rule rule = new Rule(id, experiment.subject(), experiment.hypothesis(),
                experiment.probeTool(), experiment.probeArguments(),
                experiment.expectedSubject(), experiment.expectedPredicate(),
                experiment.expectedValue(), experiment.compensationTool(),
                experiment.compensationArguments(), contexts, counterContexts, supports,
                refutations, inconclusive, confidence, status, learnedIn,
                old == null ? null : old.adapterSkill(), gameTick);
        rules.put(id, rule);
        revision++;
        trim();
        return rule;
    }

    public synchronized Rule attachAdapter(String ruleId, String skillName) {
        Rule old = rules.get(ruleId);
        if (old == null) return null;
        Rule updated = copy(old, old.status(), skillName, old.updatedTick());
        rules.put(ruleId, updated);
        revision++;
        return updated;
    }

    /** Contradictory verified execution invalidates replay; transient failures do not. */
    public synchronized Rule recordAdapterContradiction(String skillName, long gameTick) {
        Rule old = rules.values().stream()
                .filter(rule -> skillName != null && skillName.equals(rule.adapterSkill()))
                .findFirst().orElse(null);
        if (old == null) return null;
        int refutations = old.refutations() + 1;
        LinkedHashSet<String> counterContexts = new LinkedHashSet<>(old.refutingContexts());
        counterContexts.add("adapter:" + skillName + ":" + gameTick);
        double confidence = (1.0 + old.supports())
                / (2.0 + old.supports() + refutations);
        Rule updated = new Rule(old.id(), old.subject(), old.hypothesis(),
                old.probeTool(), old.probeArguments(), old.expectedSubject(),
                old.expectedPredicate(), old.expectedValue(), old.compensationTool(),
                old.compensationArguments(), old.supportingContexts(), counterContexts,
                old.supports(),
                refutations, old.inconclusive(), confidence, RuleStatus.STALE,
                old.fingerprint(), old.adapterSkill(), gameTick);
        rules.put(updated.id(), updated);
        revision++;
        return updated;
    }

    public synchronized List<Rule> relevantRules(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return rules.values().stream()
                .filter(rule -> rule.status() == RuleStatus.CONFIRMED
                        || rule.status() == RuleStatus.CANDIDATE
                        || rule.status() == RuleStatus.STALE)
                .map(rule -> Map.entry(rule, TextSimilarity.score(query,
                        rule.subject() + " " + rule.hypothesis() + " "
                                + rule.probeTool())))
                // One shared stop-word (for example English "to") is not
                // enough to spend prompt tokens on an unrelated mechanism.
                .filter(entry -> entry.getValue() >= 0.20)
                .sorted(Comparator.<Map.Entry<Rule, Double>>comparingDouble(
                                Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().confidence(),
                                Comparator.reverseOrder()))
                .limit(Math.min(4, limit)).map(Map.Entry::getKey).toList();
    }

    public synchronized List<Profile> relevantProfiles(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return profiles.values().stream()
                .filter(profile -> {
                    Novelty novelty = novelty(profile.subject());
                    return novelty == Novelty.UNKNOWN
                            || novelty == Novelty.OBSERVED_UNVERIFIED
                            || novelty == Novelty.STALE;
                })
                .map(profile -> Map.entry(profile, TextSimilarity.score(query,
                        profile.subject() + " " + profile.kind() + " "
                                + profile.attributes())))
                .filter(entry -> entry.getValue() >= 0.20)
                .sorted(Comparator.<Map.Entry<Profile, Double>>comparingDouble(
                                Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().lastObservedTick(),
                                Comparator.reverseOrder()))
                .limit(Math.min(3, limit)).map(Map.Entry::getKey).toList();
    }

    public synchronized boolean hasRelevantNovelty(String query) {
        if (query == null || query.isBlank()) return false;
        return profiles.values().stream()
                .filter(profile -> {
                    Novelty novelty = novelty(profile.subject());
                    return novelty == Novelty.UNKNOWN
                            || novelty == Novelty.OBSERVED_UNVERIFIED
                            || novelty == Novelty.STALE;
                })
                .anyMatch(profile -> TextSimilarity.score(query,
                        profile.subject() + " " + profile.attributes()) >= 0.20);
    }

    public synchronized Collection<Rule> rules() {
        return List.copyOf(rules.values());
    }

    public synchronized State exportState() {
        return new State(List.copyOf(profiles.values()),
                List.copyOf(rules.values()), revision);
    }

    public synchronized void importState(State state) {
        profiles.clear();
        rules.clear();
        if (state != null) {
            if (state.profiles() != null) for (Profile profile : state.profiles()) {
                if (profile != null) profiles.put(canonical(profile.subject()), profile);
            }
            if (state.rules() != null) for (Rule rule : state.rules()) {
                if (rule == null) continue;
                Rule restored = rule.fingerprint().compatible(currentFingerprint)
                        ? rule : copy(rule, RuleStatus.STALE, rule.adapterSkill(), rule.updatedTick());
                rules.put(restored.id(), restored);
            }
            revision = Math.max(0L, state.revision()) + 1L;
        }
        trim();
    }

    private Profile upsert(String subject, Kind kind, Map<String, String> attributes,
                           long gameTick) {
        return upsert(subject, kind, attributes, gameTick, false);
    }

    private Profile upsert(String subject, Kind kind, Map<String, String> attributes,
                           long gameTick, boolean completeSnapshot) {
        String key = canonical(subject);
        Profile old = profiles.get(key);
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (!completeSnapshot && old != null
                && old.fingerprint().compatible(currentFingerprint)) {
            merged.putAll(old.attributes());
        }
        if (attributes != null) merged.putAll(attributes);
        boolean sameEnvironment = old != null
                && old.fingerprint().compatible(currentFingerprint);
        Profile value = new Profile(subject, namespaceOf(subject), kind, merged,
                currentFingerprint, sameEnvironment ? old.observations() + 1 : 1,
                gameTick);
        profiles.put(key, value);
        revision++;
        return value;
    }

    private static Map<String, String> blockAttributes(JsonObject root) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(primitiveAttributes(root));
        JsonObject properties = object(root, "properties");
        if (properties != null) properties.entrySet().stream().limit(20)
                .forEach(entry -> putPrimitive(out, "property." + entry.getKey(), entry.getValue()));
        // block_entity_data is intentionally excluded: its string form can be
        // large, unstable and may contain private server/mod data.
        out.remove("block_entity_data");
        return out;
    }

    private static Map<String, String> menuAttributes(JsonObject root) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(primitiveAttributes(root));
        JsonArray slots = array(root, "slots");
        if (slots == null) return out;
        int captured = 0;
        for (JsonElement element : slots) {
            if (captured >= 24 || !element.isJsonObject()) break;
            JsonObject slot = element.getAsJsonObject();
            String index = primitive(slot, "slot");
            if (index == null) continue;
            String prefix = "slot." + index + ".";
            for (String key : List.of("endpoint", "inventory_slot", "slot_type",
                    "occupied", "item", "count")) {
                String value = primitive(slot, key);
                if (value != null) out.put(prefix + key, value);
            }
            captured++;
        }
        return out;
    }

    private static Map<String, String> recipeAttributes(JsonObject recipe) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(primitiveAttributes(recipe));
        JsonArray ingredients = array(recipe, "ingredients");
        if (ingredients != null) {
            int index = 0;
            for (JsonElement group : ingredients) {
                if (index >= 12) break;
                if (group.isJsonArray()) {
                    ArrayList<String> alternatives = new ArrayList<>();
                    for (JsonElement item : group.getAsJsonArray()) {
                        if (alternatives.size() >= 8 || !item.isJsonPrimitive()) break;
                        alternatives.add(item.getAsString());
                    }
                    out.put("ingredient." + index, String.join("|", alternatives));
                }
                index++;
            }
        }
        return out;
    }

    private static Map<String, String> primitiveAttributes(JsonObject root) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        root.entrySet().stream().limit(MAX_ATTRIBUTES).forEach(entry ->
                putPrimitive(out, entry.getKey(), entry.getValue()));
        return out;
    }

    private static Map<String, String> selectedAttributes(JsonObject root,
                                                          List<String> keys) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (root.has(key)) putPrimitive(out, key, root.get(key));
        }
        return out;
    }

    private static void putPrimitive(Map<String, String> out, String key, JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || out.size() >= MAX_ATTRIBUTES) return;
        try {
            out.put(compact(key, 80), compact(value.getAsString(), 180));
        } catch (RuntimeException ignored) {
            // Malformed or provider-specific values are evidence we do not know,
            // not a reason to corrupt the entire persistent profile.
        }
    }

    private void trim() {
        while (profiles.size() > MAX_PROFILES) {
            Profile oldest = profiles.values().stream()
                    // Preserve scarce mod profiles and anything with rule
                    // evidence before routine vanilla observations.
                    .min(Comparator.comparingInt((Profile profile) ->
                                    "minecraft".equals(profile.namespace()) ? 0
                                            : hasRule(profile.subject()) ? 2 : 1)
                            .thenComparingLong(Profile::lastObservedTick)).orElse(null);
            if (oldest == null) break;
            profiles.remove(canonical(oldest.subject()));
        }
        while (rules.size() > MAX_RULES) {
            Rule oldest = rules.values().stream()
                    .filter(rule -> rule.status() != RuleStatus.CONFIRMED)
                    .min(Comparator.comparingLong(Rule::updatedTick))
                    .orElse(rules.values().iterator().next());
            rules.remove(oldest.id());
        }
    }

    private boolean hasRule(String subject) {
        String key = canonical(subject);
        return rules.values().stream().anyMatch(rule ->
                canonical(rule.subject()).equals(key));
    }

    private static Rule copy(Rule old, RuleStatus status, String adapter,
                             long updatedTick) {
        return new Rule(old.id(), old.subject(), old.hypothesis(), old.probeTool(),
                old.probeArguments(), old.expectedSubject(), old.expectedPredicate(),
                old.expectedValue(), old.compensationTool(), old.compensationArguments(),
                old.supportingContexts(), old.refutingContexts(), old.supports(), old.refutations(),
                old.inconclusive(), old.confidence(), status, old.fingerprint(),
                adapter, updatedTick);
    }

    private static String ruleId(String subject, String hypothesis, String tool,
                                 String predicate, String value,
                                 EnvironmentFingerprint fingerprint) {
        // expectedSubject often contains a coordinate or container instance.
        // Excluding it allows evidence from two independent machine instances
        // to validate one behavioral hypothesis without conflating different
        // predicates or expected values.
        String raw = canonical(subject) + "|" + canonical(hypothesis) + "|"
                + canonical(tool) + "|" + canonical(predicate) + "|"
                + canonical(value) + "|" + String.valueOf(fingerprint);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return "rule-" + java.util.HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            return "rule-" + Integer.toUnsignedString(raw.hashCode(), 36);
        }
    }

    private static Map<String, String> boundedAttributes(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (out.size() >= MAX_ATTRIBUTES || entry.getKey() == null
                    || entry.getValue() == null) break;
            out.put(compact(entry.getKey(), 80), compact(entry.getValue(), 180));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static Set<String> boundedSet(Set<String> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : source) {
            if (out.size() >= 16) break;
            if (value != null && !value.isBlank()) out.add(compact(value, 256));
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static JsonObject parseObject(String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(raw == null ? "" : raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        return source != null && source.has(key) && source.get(key).isJsonObject()
                ? source.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject source, String key) {
        return source != null && source.has(key) && source.get(key).isJsonArray()
                ? source.getAsJsonArray(key) : null;
    }

    private static String primitive(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonPrimitive()) return null;
        try {
            String value = source.get(key).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String namespaceOf(String subject) {
        if (subject == null) return "unknown";
        String value = subject.startsWith("menu:") ? subject.substring(5) : subject;
        int colon = value.indexOf(':');
        return colon > 0 ? value.substring(0, colon).toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String canonical(String value) {
        return normalize(value, "unknown").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : compact(value.trim(), 512);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : compact(value.trim(), 128);
    }

    private static String compact(String value, int limit) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
