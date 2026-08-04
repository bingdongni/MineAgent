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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-companion policy for detecting and fleeing nearby creepers. */
public final class AvoidCreepersReflex implements Reflex {

    private static final String ID = "avoid_creeper";
    private static final String DESC = "Flee from nearby creepers";
    private static final double DETECTION_RADIUS = 6.0;

    /** ReflexRegistry owns one shared instance, so state is keyed by UUID. */
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();

    @Override
    public String id() { return ID; }

    @Override
    public String description() { return DESC; }

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

    public Optional<Creeper> findNearestCreeper(AgentPlayer companion) {
        if (!isEnabled(companion)) return Optional.empty();
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            AABB box = sp.getBoundingBox().inflate(DETECTION_RADIUS);
            return sp.level().getEntitiesOfClass(Creeper.class, box, Creeper::isAlive)
                    .stream().min(Comparator.comparingDouble(entity -> entity.distanceTo(sp)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public boolean isCreeperNearby(AgentPlayer companion) {
        return findNearestCreeper(companion).isPresent();
    }

    public void fleeFrom(AgentPlayer companion, Creeper creeper) {
        if (!isEnabled(companion) || creeper == null) return;
        try {
            InputDriver input = ((CompanionEntity) companion).inputDriver();
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            Vec3 away = sp.position().subtract(creeper.position());
            if (away.lengthSqr() < 1.0e-6) return;
            away = away.normalize();
            sp.setYRot((float) Math.toDegrees(Math.atan2(-away.x, away.z)));
            sp.setXRot(0);
            input.setForward(1.0f);
            input.setSprinting(true);
        } catch (Exception ignored) {
            // Emergency reflexes are best effort and must not stop the tick.
        }
    }
}
