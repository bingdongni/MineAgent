package com.mineagent.api.task.reflex;

import com.mineagent.api.entity.AgentPlayer;

import java.util.Map;

/**
 * A reflex — a situational behavior policy that the companion can enable/disable.
 * Unlike TaskChains (which are always-on instinctive bids), reflexes are
 * policy-level toggles that the owner or the LLM can switch.
 *
 * <p>Example reflexes:
 * <ul>
 *   <li>Auto-eat when hungry</li>
 *   <li>Fight back when attacked</li>
 *   <li>Pick up items while walking</li>
 * </ul>
 */
public interface Reflex {

    /** A stable snake_case id for this reflex (e.g. {@code auto_eat}). */
    String id();

    /** A human-readable description (included in system prompt). */
    String description();

    /** Whether this reflex is currently enabled. */
    boolean isEnabled(AgentPlayer companion);

    /** Enable this reflex. */
    void enable(AgentPlayer companion);

    /** Disable this reflex. */
    void disable(AgentPlayer companion);

    /** Release any per-companion toggle state during despawn. */
    default void forget(AgentPlayer companion) {}
}
