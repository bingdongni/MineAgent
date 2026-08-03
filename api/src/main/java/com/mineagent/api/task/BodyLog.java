package com.mineagent.api.task;

/**
 * Diary of body events (instinct actions, preemptions, etc.).
 * Reports are routed to the companion's inbox via the dual-rail system.
 */
public interface BodyLog {

    /**
     * Record a body event. The text is written in first person from the body's
     * perspective (e.g. "was attacked by a zombie and killed it").
     */
    void report(String narrative);
}
