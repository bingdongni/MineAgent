package com.mineagent.engine.survival.reflex;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.reflex.Reflex;
import com.mineagent.engine.entity.CompanionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Per-companion policy for fleeing from nearby creepers.
 *
 * <p>The global config determines the initial state when a body is spawned;
 * enable/disable then changes only that companion. The registry stores one
 * reflex instance globally, so state must be keyed by companion UUID.
 */
public final class AvoidCreepersReflex implements Reflex {

    private static final String ID = "avoid_creeper";
    private static final String DESC = "Flee from nearby creepers";
    private static final double DETECTION_RADIUS = 6.0;

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

    /** Find the nearest live creeper within the avoidance radius. */
    public Optional<Creeper> findNearestCreeper(AgentPlayer companion) {
        if (!isEnabled(companion)) return Optional.empty();
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            AABB box = sp.getBoundingBox().inflate(DETECTION_RADIUS);
            List<Creeper> creepers = sp.level().getEntitiesOfClass(
                    Creeper.class, box, Creeper::isAlive);
            return creepers.stream()
                    .min(Comparator.comparingDouble(entity -> entity.distanceTo(sp)));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public boolean isCreeperNearby(AgentPlayer companion) {
        return findNearestCreeper(companion).isPresent();
    }

    /** Apply direct flee input; MobDefenseChain uses the same policy state. */
    public void fleeFrom(AgentPlayer companion, Creeper creeper) {
        if (!isEnabled(companion) || creeper == null) return;
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            Vec3 away = sp.position().subtract(creeper.position());
            if (away.lengthSqr() < 1.0e-8) return;
            away = away.normalize();
            sp.setYRot((float) Math.toDegrees(Math.atan2(-away.x, away.z)));
            sp.setXRot(0);
            input.setForward(1.0f);
            input.setSprinting(true);
            input.setJumping(true);
        } catch (Exception ignored) {
            // Best-effort reflex; the survival chain remains authoritative.
        }
    }

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity entity) return entity.inputDriver();
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }
}
