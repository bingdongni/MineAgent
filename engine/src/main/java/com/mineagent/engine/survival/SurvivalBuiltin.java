package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal registry that binds a CompanionBodyLog to each AgentPlayer.
 * Chains use this to obtain the body log for their companion.
 *
 * <p>This is an engine-internal utility - not part of the public API.
 */
public final class SurvivalBuiltin {

    private SurvivalBuiltin() {}

    private static final Map<AgentPlayer, CompanionBodyLog> BODY_LOGS = new ConcurrentHashMap<>();

    /** Register a body log for a companion. Called during companion setup. */
    public static void registerBodyLog(AgentPlayer companion, CompanionBodyLog log) {
        BODY_LOGS.put(companion, log);
    }

    /** Get the body log for a companion. Never returns null - creates a default if missing. */
    public static CompanionBodyLog bodyLog(AgentPlayer companion) {
        return BODY_LOGS.computeIfAbsent(companion, c -> new CompanionBodyLog());
    }

    /** Release one despawned companion without disturbing active siblings. */
    public static void remove(AgentPlayer companion) {
        if (companion != null) BODY_LOGS.remove(companion);
    }

    /** Remove all registrations (for testing). */
    public static void clear() {
        BODY_LOGS.clear();
    }
}
