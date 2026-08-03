package com.mineagent.engine.survival.reflex;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.reflex.Reflex;
import com.mineagent.engine.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Auto pick up nearby items — makes the companion walk toward dropped items
 * within 3 blocks. This is a reflex rather than a chain because it's a
 * background behaviour that modifies movement but doesn't need to preempt
 * higher-priority instincts.
 *
 * <p>id: {@code pickup_items}, default: enabled
 */
public final class PickupItemsReflex implements Reflex {

    private static final String ID = "pickup_items";
    private static final String DESC = "Automatically walk toward and pick up nearby dropped items";
    private static final double PICKUP_RADIUS = 3.0;

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

    /**
     * If enabled, find the nearest item entity within pickup range and
     * return a movement nudge toward it. Called by the engine tick when
     * no higher-priority chain is active.
     *
     * @return the nearest item entity, if any
     */
    public Optional<ItemEntity> findNearestItem(AgentPlayer companion) {
        if (!isEnabled(companion)) return Optional.empty();
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            AABB box = sp.getBoundingBox().inflate(PICKUP_RADIUS);
            List<ItemEntity> items = sp.level().getEntitiesOfClass(ItemEntity.class, box,
                    item -> item.isAlive() && !item.hasPickUpDelay());
            if (items.isEmpty()) return Optional.empty();
            items.sort(Comparator.comparingDouble(e -> e.distanceTo(sp)));
            return Optional.of(items.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Apply a gentle movement nudge toward the given item.
     * Does NOT override existing movement — only applies when idle.
     */
    public void nudgeToward(AgentPlayer companion, ItemEntity item) {
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();

            // Fake players do not receive client movement packets, and their
            // manually-driven tick path is not guaranteed to run vanilla's
            // entity-collision pickup callback. Invoke the same vanilla entry
            // point once the hitboxes overlap; Inventory.add still enforces
            // capacity and ItemEntity owns the authoritative stack shrink.
            if (sp.getBoundingBox().inflate(0.25).intersects(item.getBoundingBox())) {
                item.playerTouch(sp);
                if (!item.isAlive() || item.getItem().isEmpty()) {
                    input.clear();
                    return;
                }
            }

            Vec3 delta = item.position().subtract(sp.position());
            double horizontal = Math.hypot(delta.x, delta.z);
            if (horizontal < 0.05) {
                input.clear();
                return;
            }
            double stepX = delta.x / horizontal;
            double stepZ = delta.z / horizontal;
            BlockPos nextFeet = BlockPos.containing(
                    sp.getX() + stepX * 0.65, sp.getY(), sp.getZ() + stepZ * 0.65);
            var level = sp.level();
            var feetState = level.getBlockState(nextFeet);
            var headState = level.getBlockState(nextFeet.above());
            var supportState = level.getBlockState(nextFeet.below());
            boolean unsafe = com.mineagent.engine.pathing.util.BlockHelper
                    .isHarmfulFluid(feetState)
                    || com.mineagent.engine.pathing.util.BlockHelper
                            .isHarmfulFluid(supportState)
                    || !com.mineagent.engine.pathing.util.BlockHelper.isPassable(feetState)
                    || !com.mineagent.engine.pathing.util.BlockHelper.isPassable(headState)
                    || (sp.onGround() && !com.mineagent.engine.pathing.util.BlockHelper
                            .canStandOn(supportState));
            if (unsafe) {
                // This reflex has no navigator or recovery state. Refuse a raw
                // nudge into lava, a wall, or an unsupported ledge; explicit
                // collect_items can use full path planning for difficult loot.
                input.clear();
                return;
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-stepX, stepZ));
            sp.setYRot(yaw);
            // IdleBehavior runs immediately before this reflex and may have
            // left strafe, jump or sprint active. The pickup nudge owns the
            // body for this idle tick, so clear every axis before applying its
            // deliberately slow, ledge-checked forward movement.
            input.clear();
            input.setForward(0.5f);
        } catch (Exception e) {
            // Silent — this is a best-effort nudge
        }
    }

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }
}
