package com.mineagent.api.entity;

/**
 * Lifecycle callbacks for a companion — spawn, despawn, death, respawn.
 */
public interface CompanionLifecycle {

    /** Called when the companion entity is spawned into the world. */
    void onSpawn(AgentPlayer companion);

    /** Called when the companion dies. */
    void onDeath(AgentPlayer companion);

    /** Called when the companion is respawned after death. */
    void onRespawn(AgentPlayer companion);

    /** Called when the companion is removed from the world. */
    void onDespawn(AgentPlayer companion);
}
