package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Registry for survival instinct chains. Chains are registered with a
 * priority weight and a factory that creates a new instance per companion.
 */
public final class BrainChains {

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private BrainChains() {}

    /**
     * Register a chain factory with a base priority weight.
     *
     * @param weight   the base weight (affects ordering; actual priority
     *                 is computed per-tick by the chain itself)
     * @param factory  creates a new chain instance for each companion
     */
    public static void register(int weight, Function<AgentPlayer, TaskChain> factory) {
        ENTRIES.add(new Entry(weight, factory));
    }

    /** All registered chain entries, in registration order. */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public record Entry(int weight, Function<AgentPlayer, TaskChain> factory) {}

    /** Remove all registered chains (for testing). */
    public static void clear() {
        ENTRIES.clear();
    }
}
