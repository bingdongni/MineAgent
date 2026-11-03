package com.mineagent.engine.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, goal-conditioned long-term memory with explicit cognitive tiers.
 *
 * <p>The tiers are intentionally data-oriented: working observations expire
 * quickly, episodic outcomes retain context, semantic rules retain validated
 * generalisations, procedural entries point to verified skills, and team
 * entries expire with the coordination window.  Retrieval scores relevance,
 * confidence, recency and contradiction instead of dumping every old event
 * into the LLM prompt.</p>
 */
public final class LayeredMemory {
    public enum Tier { WORKING, EPISODIC, SEMANTIC, PROCEDURAL, TEAM }

    public record Entry(String id, Tier tier, String key, String value, String source,
                        double confidence, long createdTick, long lastVerifiedTick,
                        long expiresTick, int accesses, int supports, int refutes,
                        String environment, String dimension) {
        public Entry {
            id = normalize(id, "memory");
            tier = tier == null ? Tier.WORKING : tier;
            key = normalize(key, "fact");
            value = compact(value, 900);
            source = normalize(source, "unknown");
            confidence = unit(confidence);
            createdTick = Math.max(0L, createdTick);
            lastVerifiedTick = Math.max(0L, lastVerifiedTick);
            expiresTick = expiresTick <= 0L ? Long.MAX_VALUE : Math.max(createdTick, expiresTick);
            accesses = Math.max(0, accesses);
            supports = Math.max(0, supports);
            refutes = Math.max(0, refutes);
            environment = normalize(environment, "unknown");
            dimension = normalize(dimension, "unknown");
        }

        public boolean active(long tick) { return tick <= expiresTick; }

        public double reliability() {
            return (supports + 1.0) / (supports + refutes + 2.0);
        }

        Entry accessed(long tick) {
            return new Entry(id, tier, key, value, source, confidence,
                    // Reading a memory is not verification.  Advancing
                    // lastVerifiedTick here made hot-but-stale entries
                    // immortal under frequent prompt recall and defeated
                    // recency decay.  Verification is performed only by
                    // remember()/contradict()/explicit world evidence.
                    createdTick, lastVerifiedTick, expiresTick,
                    accesses + 1, supports, refutes, environment, dimension);
        }
    }

    public record State(List<Entry> entries, long revision) {
        public State {
            // A crash during JSON serialization can leave a null list member;
            // filter it before import so valid memories remain recoverable.
            entries = entries == null ? List.of() : entries.stream()
                    .filter(Objects::nonNull).toList();
            revision = Math.max(0L, revision);
        }
    }
    private static final int MAX_ENTRIES = 768;
    private static final int MAX_PER_TIER = 192;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private long revision;

    public synchronized Entry remember(Tier tier, String key, String value, String source,
                                        double confidence, long tick, long ttl,
                                        String environment, String dimension) {
        String id = id(tier, key, environment, dimension);
        Entry old = entries.get(id);
        // Server snapshots are intentionally repeated (inventory heartbeat,
        // open-menu refresh, body telemetry). Counting an identical sample as
        // a new independent support made confidence/reliability grow without
        // bound and crowded retrieval with duplicated facts. Only a changed
        // value is independent evidence; repeated verification still refreshes
        // the entry's timestamp and TTL.
        boolean identical = old != null && old.value().equals(compact(value, 900));
        int supports = old == null ? 1 : identical ? old.supports() : old.supports() + 1;
        Entry next = new Entry(id, tier, key, value, source, confidence, tick,
                tick, ttl <= 0 ? Long.MAX_VALUE : saturatedAdd(tick, ttl),
                old == null ? 0 : old.accesses(), supports,
                old == null ? 0 : old.refutes(), environment, dimension);
        entries.put(id, next);
        trim();
        revision++;
        return next;
    }

    public synchronized Entry contradict(Tier tier, String key, String value,
                                           String source, long tick,
                                           String environment, String dimension) {
        String id = id(tier, key, environment, dimension);
        Entry old = entries.get(id);
        if (old == null) return remember(tier, key, value, source, 0.35, tick,
                tier == Tier.WORKING ? 80 : 1_200, environment, dimension);
        Entry next = new Entry(old.id(), old.tier(), old.key(), value, source,
                Math.max(0.0, old.confidence() * 0.7), old.createdTick(), tick,
                old.expiresTick(), old.accesses(), old.supports(), old.refutes() + 1,
                old.environment(), old.dimension());
        entries.put(id, next);
        revision++;
        return next;
    }

    public synchronized List<Entry> recall(String query, long tick, int tokenBudget) {
        if (query == null || query.isBlank() || tokenBudget <= 0) return List.of();
        int charBudget = Math.max(240, tokenBudget * 4);
        List<Map.Entry<Entry, Double>> ranked = entries.values().stream()
                .filter(entry -> entry.active(tick) && entry.confidence() >= 0.20)
                .map(entry -> Map.entry(entry, score(entry, query, tick)))
                // Confidence and tier affect ordering only after relevance is
                // established. Without this gate, an unrelated high-confidence
                // semantic fact entered every prompt and displaced useful memory.
                .filter(entry -> lexical(entry.getKey(), query) > 0.0)
                .sorted(Map.Entry.<Entry, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().lastVerifiedTick(), Comparator.reverseOrder()))
                .toList();
        ArrayList<Entry> result = new ArrayList<>();
        int used = 0;
        for (Map.Entry<Entry, Double> candidate : ranked) {
            Entry value = candidate.getKey();
            int cost = value.key().length() + value.value().length() + 48;
            if (used + cost > charBudget) continue;
            result.add(value.accessed(tick));
            entries.put(value.id(), result.get(result.size() - 1));
            used += cost;
        }
        if (!result.isEmpty()) revision++;
        return List.copyOf(result);
    }

    /** Promote repeatedly useful working facts and decay stale low-value facts. */
    public synchronized void consolidate(long tick) {
        for (Entry entry : List.copyOf(entries.values())) {
            if (!entry.active(tick)) { entries.remove(entry.id()); continue; }
            if (entry.tier() == Tier.WORKING && entry.accesses() >= 2
                    && entry.supports() > entry.refutes()) {
                entries.put(entry.id(), new Entry(entry.id(), Tier.EPISODIC, entry.key(),
                        entry.value(), entry.source(), entry.confidence(), entry.createdTick(),
                        tick, saturatedAdd(tick, 12_000), entry.accesses(), entry.supports(),
                        entry.refutes(), entry.environment(), entry.dimension()));
            }
        }
        trim();
        revision++;
    }

    public synchronized String summarizeForPrompt(String query, long tick, int tokenBudget) {
        List<Entry> values = recall(query, tick, tokenBudget);
        if (values.isEmpty()) return "Layered memory: none\n";
        StringBuilder out = new StringBuilder("Layered memory (goal-conditioned evidence):\n");
        for (Entry value : values) {
            out.append("- [").append(value.tier().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(value.key()).append('=').append(value.value())
                    .append(" confidence=").append(String.format(Locale.ROOT, "%.2f", value.confidence()))
                    .append(" reliability=").append(String.format(Locale.ROOT, "%.2f", value.reliability()))
                    .append('\n');
        }
        out.append("Use memory as evidence, verify volatile facts before irreversible actions.\n");
        return out.toString();
    }

    public synchronized State exportState() { return new State(List.copyOf(entries.values()), revision); }

    public synchronized void importState(State state) {
        entries.clear();
        if (state != null && state.entries() != null) {
            state.entries().stream().filter(Objects::nonNull)
                    .forEach(entry -> entries.put(entry.id(), entry));
            revision = Math.max(0L, state.revision()) + 1L;
        } else revision = 0L;
        trim();
    }

    public synchronized int size() { return entries.size(); }

    private double score(Entry entry, String query, long tick) {
        double lexical = lexical(entry, query);
        double recency = entry.lastVerifiedTick() >= tick ? 1.0
                : 1.0 / (1.0 + Math.min(10_000L, tick - entry.lastVerifiedTick()) / 1_200.0);
        double tier = switch (entry.tier()) {
            case SEMANTIC, PROCEDURAL -> 0.18;
            case EPISODIC -> 0.10;
            case TEAM -> 0.08;
            case WORKING -> 0.02;
        };
        return lexical * 0.62 + entry.confidence() * 0.18
                + entry.reliability() * 0.12 + recency * 0.08 + tier;
    }

    private static double lexical(Entry entry, String query) {
        return TextSimilarity.score(query, entry.key() + " " + entry.value());
    }

    private void trim() {
        for (Tier tier : Tier.values()) {
            while (entries.values().stream().filter(entry -> entry.tier() == tier).count() > MAX_PER_TIER) {
                Entry remove = entries.values().stream().filter(entry -> entry.tier() == tier)
                        .min(Comparator.comparingDouble((Entry entry) -> entry.confidence()
                                        * (entry.reliability() + 0.1))
                                .thenComparingLong(Entry::lastVerifiedTick)).orElse(null);
                if (remove == null) break;
                entries.remove(remove.id());
            }
        }
        while (entries.size() > MAX_ENTRIES) {
            Entry remove = entries.values().stream().min(Comparator
                    .comparingDouble((Entry entry) -> entry.confidence() * entry.reliability())
                    .thenComparingLong(Entry::lastVerifiedTick)).orElse(null);
            if (remove == null) break;
            entries.remove(remove.id());
        }
    }

    private static String id(Tier tier, String key, String environment, String dimension) {
        return (tier == null ? Tier.WORKING : tier).name() + "|"
                + normalize(key, "fact").toLowerCase(Locale.ROOT) + "|"
                + normalize(environment, "unknown") + "|" + normalize(dimension, "unknown");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String compact(String value, int limit) {
        String normalized = normalize(value, "").replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
    private static double unit(double value) { return Double.isFinite(value)
            ? Math.max(0.0, Math.min(1.0, value)) : 0.0; }
    private static long saturatedAdd(long first, long second) {
        return second > 0 && first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
