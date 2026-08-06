package com.mineagent.engine.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.engine.cognition.SituationSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Event-sourced semantic memory for the companion's partially observed world.
 *
 * <p>The event log records what produced a belief while the projection keeps
 * planning queries cheap. Facts carry source, confidence, observation time,
 * expiry, and correlation IDs so stale perception cannot silently become a
 * permanent world rule. Live inventory and actors are intentionally volatile;
 * durable facilities and learned mechanics survive a restart.
 */
public final class SemanticWorldModel {
    public enum EventType { OBSERVED, CHANGED, RETRACTED, ACTION, OUTCOME }

    public record SemanticEvent(long sequence, EventType type, String subject,
                                String predicate, String value,
                                WorldAssetIndex.Position position,
                                double confidence, String source,
                                String correlationId, long gameTick,
                                long expiresTick, boolean durable) {
        public SemanticEvent {
            sequence = Math.max(1L, sequence);
            type = type == null ? EventType.OBSERVED : type;
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "unknown");
            confidence = unit(confidence);
            source = normalize(source, "observation");
            correlationId = blankToNull(correlationId);
            gameTick = Math.max(0L, gameTick);
            expiresTick = expiresTick <= 0L ? Long.MAX_VALUE
                    : Math.max(gameTick, expiresTick);
        }

        public String key() {
            return canonical(subject) + "\u0000" + canonical(predicate);
        }
    }

    public record SemanticFact(String subject, String predicate, String value,
                               WorldAssetIndex.Position position,
                               double confidence, String source,
                               String correlationId, long observedTick,
                               long expiresTick, long eventSequence,
                               boolean durable, boolean retracted) {
        public SemanticFact {
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "unknown");
            confidence = unit(confidence);
            source = normalize(source, "observation");
            correlationId = blankToNull(correlationId);
            observedTick = Math.max(0L, observedTick);
            expiresTick = expiresTick <= 0L ? Long.MAX_VALUE
                    : Math.max(observedTick, expiresTick);
            eventSequence = Math.max(1L, eventSequence);
        }

        public String key() {
            return canonical(subject) + "\u0000" + canonical(predicate);
        }

        public boolean activeAt(long gameTick) {
            return !retracted && gameTick <= expiresTick;
        }

        public double confidenceAt(long gameTick) {
            if (!activeAt(gameTick)) return 0.0;
            if (expiresTick == Long.MAX_VALUE || gameTick <= observedTick) return confidence;
            long lifetime = Math.max(1L, expiresTick - observedTick);
            double ageFraction = Math.min(1.0,
                    (double) (gameTick - observedTick) / lifetime);
            return confidence * (1.0 - 0.5 * ageFraction);
        }
    }

    public record State(List<SemanticEvent> events, List<SemanticFact> facts,
                        long nextSequence, long revision) {}

    private static final int MAX_EVENTS = 2_048;
    private static final int MAX_FACTS = 2_048;
    private static final long LIVE_FACT_TTL = 40L;
    private static final long ACTOR_TTL = 20L;
    private static final long HEARTBEAT_INTERVAL = 100L;

    private final ArrayList<SemanticEvent> events = new ArrayList<>();
    private final LinkedHashMap<String, SemanticFact> facts = new LinkedHashMap<>();
    private final Map<String, Integer> lastInventoryCounts = new HashMap<>();
    private final Set<String> lastAssetKeys = new LinkedHashSet<>();
    private long nextSequence = 1L;
    private long revision;

    public synchronized SemanticEvent observe(String subject, String predicate,
                                               String value,
                                               WorldAssetIndex.Position position,
                                               double confidence, String source,
                                               String correlationId, long gameTick,
                                               long ttlTicks, boolean durable) {
        long expires = ttlTicks <= 0L ? Long.MAX_VALUE
                : saturatedAdd(gameTick, ttlTicks);
        String key = key(subject, predicate);
        SemanticFact previous = facts.get(key);
        if (previous != null && gameTick < previous.observedTick()) {
            // Late observations remain auditable but cannot roll the current
            // projection back to an older world state.
            SemanticEvent historical = new SemanticEvent(nextSequence++,
                    EventType.OBSERVED, subject, predicate, value, position,
                    confidence, source, correlationId, gameTick, expires, durable);
            events.add(historical);
            revision++;
            trim();
            return historical;
        }
        boolean sameValue = previous != null && !previous.retracted()
                && previous.value().equals(normalize(value, "unknown"))
                && java.util.Objects.equals(previous.position(), position)
                && previous.durable() == durable;

        // Frequent server snapshots refresh freshness without filling the
        // bounded event journal with one identical event every second.
        if (sameValue && gameTick >= previous.observedTick()
                && gameTick - previous.observedTick() < HEARTBEAT_INTERVAL) {
            facts.put(key, new SemanticFact(previous.subject(), previous.predicate(),
                    previous.value(), position, Math.max(previous.confidence(), unit(confidence)),
                    source, correlationId, gameTick, expires,
                    previous.eventSequence(), durable, false));
            return null;
        }

        EventType type = previous == null || previous.retracted()
                ? EventType.OBSERVED : EventType.CHANGED;
        return append(type, subject, predicate, value, position, confidence,
                source, correlationId, gameTick, expires, durable, false);
    }

    public synchronized SemanticEvent retract(String subject, String predicate,
                                               String source, String correlationId,
                                               long gameTick, boolean durable) {
        SemanticFact previous = facts.get(key(subject, predicate));
        if (previous == null || previous.retracted()
                || gameTick < previous.observedTick()) return null;
        return append(EventType.RETRACTED, previous.subject(), previous.predicate(),
                previous.value(), previous.position(), previous.confidence(), source,
                correlationId, gameTick, gameTick, durable, true);
    }

    public synchronized void recordAction(String toolName, String arguments,
                                          String correlationId, long gameTick) {
        append(EventType.ACTION, "tool:" + normalize(toolName, "unknown"),
                "arguments", compact(arguments, 320), null, 1.0,
                "agent_loop", correlationId, gameTick,
                saturatedAdd(gameTick, 1_200L), false, false);
    }

    public synchronized void recordOutcome(String toolName, boolean success,
                                           String evidence, String correlationId,
                                           long gameTick) {
        String subject = "tool:" + normalize(toolName, "unknown");
        append(EventType.OUTCOME, subject,
                "outcome", success ? "success" : "failure", null, 1.0,
                compact(evidence, 320), correlationId, gameTick,
                saturatedAdd(gameTick, 2_400L), false, false);
        // Primitive top-level result fields provide generic semantic
        // postconditions for unfamiliar tools without hard-coding mod IDs or
        // parsing arbitrary nested payloads into unbounded prompt state.
        JsonObject object = parseObject(evidence);
        if (object == null) return;
        int emitted = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (emitted >= 12 || !entry.getValue().isJsonPrimitive()) continue;
            String value;
            try {
                value = entry.getValue().getAsString();
            } catch (RuntimeException ignored) {
                continue;
            }
            observe(subject, "result." + entry.getKey(), compact(value, 160),
                    null, 0.95, "tool_result", correlationId, gameTick,
                    2_400L, false);
            emitted++;
        }
    }

    /** Replace live carried-item facts, including explicit zero-count removals. */
    public synchronized void observeInventory(WorldAssetIndex.Position position,
                                              Collection<WorldAssetIndex.ItemObservation> items,
                                              long gameTick) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (items != null) {
            for (WorldAssetIndex.ItemObservation item : items) {
                if (item == null || item.count() <= 0) continue;
                counts.merge(item.resourceId(), item.count(), Integer::sum);
            }
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(lastInventoryCounts.keySet());
        ids.addAll(counts.keySet());
        for (String id : ids) {
            observe("inventory:" + id, "count",
                    Integer.toString(counts.getOrDefault(id, 0)), position,
                    1.0, "inventory_snapshot", null, gameTick,
                    LIVE_FACT_TTL, false);
        }
        lastInventoryCounts.clear();
        lastInventoryCounts.putAll(counts);
    }

    /** Project the generic asset index into semantic object facts. */
    public synchronized void observeAssets(Collection<WorldAssetIndex.Asset> assets,
                                           long gameTick) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (assets != null) {
            for (WorldAssetIndex.Asset asset : assets) {
                if (asset == null) continue;
                // Carried stacks already have one aggregated inventory fact;
                // duplicating every slot here wastes prompt space and events.
                if (asset.carried()) continue;
                String subject = "asset:" + asset.key();
                seen.add(subject);
                boolean durable = asset.scope() == WorldAssetIndex.Scope.KNOWN_CONTAINER
                        || asset.scope() == WorldAssetIndex.Scope.WORLD_OBJECT;
                long ttl = durable ? 0L : LIVE_FACT_TTL;
                observe(subject, "resource", asset.resourceId(), asset.position(),
                        asset.confidence(), "world_asset_index", null,
                        gameTick, ttl, durable);
                observe(subject, "scope", asset.scope().name().toLowerCase(Locale.ROOT),
                        asset.position(), asset.confidence(), "world_asset_index", null,
                        gameTick, ttl, durable);
                observe(subject, "count", Integer.toString(asset.count()),
                        asset.position(), asset.confidence(), "world_asset_index", null,
                        gameTick, ttl, durable);
                if (!asset.capabilities().isEmpty()) {
                    observe(subject, "capabilities", String.join(",", asset.capabilities()),
                            asset.position(), asset.confidence(), "world_asset_index", null,
                            gameTick, ttl, durable);
                }
            }
        }
        for (String old : new ArrayList<>(lastAssetKeys)) {
            if (!seen.contains(old)) {
                SemanticFact fact = facts.get(key(old, "scope"));
                if (fact != null && !fact.durable()) {
                    retract(old, "scope", "world_asset_index", null, gameTick, false);
                }
            }
        }
        lastAssetKeys.clear();
        lastAssetKeys.addAll(seen);
    }

    /** Track actors by UUID so movement and disappearance have temporal identity. */
    public synchronized void observeSituation(SituationSnapshot frame) {
        if (frame == null) return;
        long tick = frame.gameTick();
        WorldAssetIndex.Position selfPos = position(frame.self());
        observe("self", "position", frame.self().compact(), selfPos,
                1.0, "situation_frame", null, tick, LIVE_FACT_TTL, false);
        observe("self", "health_ratio",
                format(frame.vitals().healthRatio()), selfPos,
                1.0, "situation_frame", null, tick, LIVE_FACT_TTL, false);
        observe("environment", "immediate_hazards",
                Integer.toString(frame.environment().immediateHazards()), selfPos,
                1.0, "situation_frame", null, tick, LIVE_FACT_TTL, false);
        if (frame.owner().present()) {
            observe("owner", "position", frame.owner().position().compact(),
                    position(frame.owner().position()), 1.0, "situation_frame",
                    null, tick, LIVE_FACT_TTL, false);
            observe("owner", "activity", frame.owner().activity(),
                    position(frame.owner().position()), 0.9, "situation_frame",
                    null, tick, LIVE_FACT_TTL, false);
        }
        for (SituationSnapshot.ActorObservation actor : frame.actors()) {
            String subject = "actor:" + actor.id();
            WorldAssetIndex.Position actorPos = position(actor.position());
            observe(subject, "type", actor.type(), actorPos, 1.0,
                    "situation_frame", null, tick, ACTOR_TTL, false);
            observe(subject, "kind", actor.kind().name().toLowerCase(Locale.ROOT),
                    actorPos, 1.0, "situation_frame", null, tick, ACTOR_TTL, false);
            observe(subject, "activity", actor.activity(), actorPos, 0.85,
                    "situation_frame", null, tick, ACTOR_TTL, false);
            if (actor.immediateThreat()) {
                observe(subject, "threat", "immediate", actorPos, 1.0,
                        "situation_frame", null, tick, ACTOR_TTL, false);
            }
        }
    }

    public synchronized Optional<SemanticFact> find(String subject, String predicate,
                                                    long gameTick) {
        SemanticFact fact = facts.get(key(subject, predicate));
        return fact != null && fact.activeAt(gameTick) ? Optional.of(fact) : Optional.empty();
    }

    public synchronized boolean matches(String subject, String predicate,
                                        String expectedValue, long notBeforeTick,
                                        double minimumConfidence, long gameTick) {
        return find(subject, predicate, gameTick)
                .filter(fact -> fact.observedTick() >= notBeforeTick)
                .filter(fact -> fact.confidenceAt(gameTick) >= unit(minimumConfidence))
                .map(fact -> valueMatches(fact.value(), expectedValue))
                .orElse(false);
    }

    public synchronized List<SemanticEvent> eventsSince(long sequence) {
        return events.stream().filter(event -> event.sequence() > sequence).toList();
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized State exportState() {
        List<SemanticEvent> durableEvents = events.stream()
                .filter(SemanticEvent::durable).toList();
        List<SemanticFact> durableFacts = facts.values().stream()
                .filter(SemanticFact::durable).toList();
        return new State(durableEvents, durableFacts, nextSequence, revision);
    }

    public synchronized void importState(State state) {
        events.clear();
        facts.clear();
        lastInventoryCounts.clear();
        lastAssetKeys.clear();
        if (state != null) {
            if (state.events() != null) {
                state.events().stream().filter(java.util.Objects::nonNull)
                        .filter(SemanticEvent::durable)
                        .sorted(Comparator.comparingLong(SemanticEvent::sequence))
                        .forEach(events::add);
            }
            if (state.facts() != null) {
                for (SemanticFact fact : state.facts()) {
                    if (fact != null && fact.durable()) facts.put(fact.key(), fact);
                }
            }
            nextSequence = Math.max(maxSequence() + 1L,
                    Math.max(1L, state.nextSequence()));
            revision = Math.max(0L, state.revision()) + 1L;
        } else {
            nextSequence = 1L;
            revision = 0L;
        }
        trim();
    }

    public synchronized String summarizeForPrompt(long gameTick) {
        List<SemanticFact> active = facts.values().stream()
                .filter(fact -> fact.activeAt(gameTick))
                // Realtime cognition and WorldAssetIndex already publish
                // actors, vitals, inventory, and assets in compact domain
                // summaries. Avoid repeating those tokens here; expose only
                // cross-cutting task/action/mechanism evidence.
                .filter(fact -> !fact.subject().equals("self")
                        && !fact.subject().equals("owner")
                        && !fact.subject().equals("environment")
                        && !fact.subject().startsWith("actor:")
                        && !fact.subject().startsWith("inventory:")
                        && !fact.subject().startsWith("asset:"))
                .sorted(Comparator.comparingLong(SemanticFact::observedTick).reversed())
                .limit(8).toList();
        if (active.isEmpty()) return "Semantic world model: no active facts\n";
        StringBuilder out = new StringBuilder("Semantic world model (temporal evidence):\n");
        for (SemanticFact fact : active) {
            out.append("- ").append(fact.subject()).append(' ')
                    .append(fact.predicate()).append('=').append(fact.value())
                    .append(" confidence=").append(format(fact.confidenceAt(gameTick)))
                    .append(" age=").append(Math.max(0L, gameTick - fact.observedTick()))
                    .append(fact.durable() ? " durable" : " volatile").append('\n');
        }
        out.append("Treat expired or low-confidence facts as unknown and gather reversible evidence.\n");
        return out.toString();
    }

    private SemanticEvent append(EventType type, String subject, String predicate,
                                 String value, WorldAssetIndex.Position position,
                                 double confidence, String source, String correlationId,
                                 long gameTick, long expiresTick, boolean durable,
                                 boolean retracted) {
        SemanticEvent event = new SemanticEvent(nextSequence++, type, subject,
                predicate, value, position, confidence, source, correlationId,
                gameTick, expiresTick, durable);
        events.add(event);
        facts.put(event.key(), new SemanticFact(event.subject(), event.predicate(),
                event.value(), event.position(), event.confidence(), event.source(),
                event.correlationId(), event.gameTick(), event.expiresTick(),
                event.sequence(), event.durable(), retracted));
        revision++;
        trim();
        return event;
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) events.remove(0);
        while (facts.size() > MAX_FACTS) {
            String removable = facts.values().stream()
                    .filter(fact -> !fact.durable())
                    .min(Comparator.comparingLong(SemanticFact::observedTick))
                    .map(SemanticFact::key)
                    .orElse(facts.keySet().iterator().next());
            facts.remove(removable);
        }
    }

    private long maxSequence() {
        return events.stream().mapToLong(SemanticEvent::sequence).max().orElse(0L);
    }

    private static WorldAssetIndex.Position position(SituationSnapshot.Position position) {
        if (position == null) return null;
        return new WorldAssetIndex.Position(position.dimension(),
                (int) Math.floor(position.x()), (int) Math.floor(position.y()),
                (int) Math.floor(position.z()));
    }

    private static boolean valueMatches(String actual, String expected) {
        if (expected == null || expected.isBlank() || "*".equals(expected)) return true;
        return canonical(actual).equals(canonical(expected));
    }

    private static String key(String subject, String predicate) {
        return canonical(subject) + "\u0000" + canonical(predicate);
    }

    private static String canonical(String value) {
        return normalize(value, "unknown").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String compact(String value, int limit) {
        String normalized = normalize(value, "none").replace('\n', ' ')
                .replace('\r', ' ');
        return normalized.length() <= limit ? normalized
                : normalized.substring(0, limit) + "...";
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return Math.max(0L, first + second);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static JsonObject parseObject(String value) {
        if (value == null || value.isBlank() || value.length() > 32_768) return null;
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
