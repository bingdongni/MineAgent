package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

/**
 * A bid-for-body control chain. Every tick, each chain reports a priority;
 * the highest-priority chain gets to control the companion's body for that tick.
 * When preempted, {@link #onInterrupt} is called.
 *
 * <p>Priority hierarchy (higher = more urgent):
 * <pre>
 *   MLG(10) > Breath(6) > MobDefense(5) > Food(4/3) > Unstuck(2) > LLM(0)
 * </pre>
 */
public interface TaskChain {

    /**
     * Return the priority for this tick. Higher values win the body.
     * Return {@link Float#NEGATIVE_INFINITY} to be dormant.
     */
    float getPriority(AgentPlayer companion);

    /**
     * Execute one tick of body control. Only called when this chain
     * won the priority auction.
     */
    void tick(AgentPlayer companion);

    /**
     * Called when a higher-priority chain preempts this one.
     * Release all held resources (navigation, item use, etc.).
     */
    void onInterrupt(AgentPlayer companion);

    /** A stable name for this chain (used in logging and reflex roster). */
    String name();
}
