package com.mineagent.api.task.reflex;

import java.util.*;

/**
 * Global registry of reflex policies.
 */
public final class ReflexRegistry {

    private static final LinkedHashMap<String, Reflex> REFLEXES = new LinkedHashMap<>();

    private ReflexRegistry() {}

    /** Register a reflex. */
    public static void register(Reflex reflex) {
        REFLEXES.put(reflex.id(), reflex);
    }

    /** Look up by id. */
    public static Optional<Reflex> get(String id) {
        return Optional.ofNullable(REFLEXES.get(id));
    }

    /** All registered reflexes. */
    public static Collection<Reflex> all() {
        return Collections.unmodifiableCollection(REFLEXES.values());
    }

    /** Remove all (for testing). */
    public static void clear() {
        REFLEXES.clear();
    }
}
