package com.mineagent.engine.survival.reflex;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.reflex.Reflex;

/**
 * Toggleable fight-back policy. When enabled, the MobDefenseChain instinct
 * will fight back against hostile mobs. When disabled, it will only flee
 * (run away from all threats, including non-creeper mobs).
 *
 * <p>Note: Creeper avoidance is always active regardless of this reflex.
 *
 * <p>id: {@code fight_back}, default: enabled
 */
public final class FightBackReflex implements Reflex {

    private static final String ID = "fight_back";
    private static final String DESC = "Fight back against hostile mobs when attacked (creepers always trigger flee)";

    /** Registry reflexes are singletons, so state must be keyed per companion. */
    private final java.util.Set<java.util.UUID> disabled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return DESC;
    }

    @Override
    public boolean isEnabled(AgentPlayer companion) {
        return companion != null && !disabled.contains(companion.companionId());
    }

    @Override
    public void enable(AgentPlayer companion) {
        if (companion != null) disabled.remove(companion.companionId());
    }

    @Override
    public void disable(AgentPlayer companion) {
        if (companion != null) disabled.add(companion.companionId());
    }

    @Override
    public void forget(AgentPlayer companion) {
        if (companion != null) disabled.remove(companion.companionId());
    }
}
