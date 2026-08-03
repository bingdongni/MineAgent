package com.mineagent.engine.survival.reflex;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.reflex.Reflex;

/**
 * Toggleable auto-eat policy. When enabled, the FoodChain instinct is
 * active and will trigger eating when the companion is hungry.
 * When disabled, the companion will not auto-eat.
 *
 * <p>id: {@code auto_eat}, default: enabled
 */
public final class AutoEatReflex implements Reflex {

    private static final String ID = "auto_eat";
    private static final String DESC = "Automatically eat food when hunger drops below threshold";

    /** Disabled IDs rather than one global flag: registry instances are shared. */
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
