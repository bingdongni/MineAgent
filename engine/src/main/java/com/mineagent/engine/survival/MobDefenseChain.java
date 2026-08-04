package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.task.TaskContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fight back or flee — when the companion is being attacked by hostile mobs,
 * either counter-attack (if armed and healthy) or flee (if low health or
 * facing a creeper/warden).
 *
 * <p>Priority: 5 (below Breath, above Food)
 * <p>Scan radius: 12 blocks for threats
 * <p>Creeper rule: always flee creepers regardless of fight_back reflex
 */
public final class MobDefenseChain implements TaskChain {

    private static final float PRIORITY_FIGHT = 5.0f;
    private static final float PRIORITY_FLEE = 5.5f; // Slightly higher — flee is more urgent
    private static final double SCAN_RADIUS = 12.0;
    private static final double CREEPER_FLEE_DISTANCE = 6.0;

    private final SurvivalConfig config;
    private final CompanionBodyLog bodyLog;
    private final AgentPlayer companion;

    private enum Mode { IDLE, FIGHTING, FLEEING }
    private Mode mode = Mode.IDLE;
    private LivingEntity target = null;
    private int fightTicks = 0;
    private int fleeTicks = 0;

    public MobDefenseChain(AgentPlayer companion, SurvivalConfig config) {
        this.companion = companion;
        this.config = config;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() {
        return "mob_defense";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();

            // Check for nearby hostile mobs
            List<LivingEntity> threats = findThreats(sp,
                    reflexEnabled("avoid_creeper", companion, true));
            if (threats.isEmpty()) {
                return Float.NEGATIVE_INFINITY;
            }

            // Check if any creeper is too close — always triggers flee
            for (LivingEntity threat : threats) {
                if (isCreeper(threat) && threat.distanceTo(sp) < CREEPER_FLEE_DISTANCE) {
                    return PRIORITY_FLEE;
                }
            }

            // Being attacked or threatened — decide fight or flee
            boolean canFight = canFight(companion, sp);
            float urgency = SurvivalDecisions.mobDefensePriority(
                    companion.health(), sp.getMaxHealth(), threats.size());
            return canFight ? urgency : Math.max(PRIORITY_FLEE, urgency);
        } catch (Exception e) {
            System.err.println("[MineAgent] MobDefense getPriority error: " + e.getMessage());
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(AgentPlayer companion) {
        try {
            InputDriver input = inputDriver(companion);
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();

            // Re-evaluate threats
            List<LivingEntity> threats = findThreats(sp,
                    reflexEnabled("avoid_creeper", companion, true));

            // Check if we need to switch mode (e.g., creeper appeared while fighting)
            if (mode == Mode.IDLE || mode == Mode.FIGHTING) {
                for (LivingEntity threat : threats) {
                    if (isCreeper(threat) && threat.distanceTo(sp) < CREEPER_FLEE_DISTANCE) {
                        mode = Mode.FLEEING;
                        target = threat;
                        bodyLog.report("spotted a creeper, running away!");
                        break;
                    }
                }
                // Low health while fighting? Switch to flee
                if (mode == Mode.FIGHTING && companion.health() <= config.healthFlee()) {
                    mode = Mode.FLEEING;
                    bodyLog.report("health is low, retreating from combat");
                }
            }

            switch (mode) {
                case IDLE -> {
                    if (!threats.isEmpty()) {
                        boolean canFight = canFight(companion, sp);
                        if (canFight) {
                            mode = Mode.FIGHTING;
                            target = threats.get(0);
                            fightTicks = 0;
                            bodyLog.report("fighting back against " + entityName(target));
                        } else {
                            mode = Mode.FLEEING;
                            target = threats.get(0);
                            fleeTicks = 0;
                            bodyLog.report("fleeing from " + entityName(target));
                        }
                    }
                }
                case FIGHTING -> {
                    tickFighting(companion, input, sp, threats);
                }
                case FLEEING -> {
                    tickFleeing(companion, input, sp, threats);
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] MobDefense tick error: " + e.getMessage());
            reset();
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        reset();
    }

    private void reset() {
        try {
            ((CompanionEntity) companion).inputDriver().clear();
        } catch (Exception ignored) {
            // The entity may already be tearing down during an interrupt.
        }
        mode = Mode.IDLE;
        target = null;
        fightTicks = 0;
        fleeTicks = 0;
    }

    // ── Fight logic ────────────────────────────────────────────────

    private void tickFighting(AgentPlayer companion, InputDriver input,
                               ServerPlayer sp, List<LivingEntity> threats) {
        fightTicks++;

        // Validate target
        if (target == null || !target.isAlive() || target.distanceTo(sp) > SCAN_RADIUS) {
            if (!threats.isEmpty()) {
                target = threats.get(0);
                fightTicks = 0;
            } else {
                bodyLog.report("no more threats nearby");
                reset();
                return;
            }
        }

        // Look at target
        lookAt(sp, target);

        double dist = target.distanceTo(sp);

        if (dist > 3.0) {
            // Move toward target
            input.setForward(1.0f);
            input.setSprinting(dist > 6.0);
        } else {
            // In attack range — stop moving and attack
            input.setForward(0.0f);
            input.setSprinting(false);

            // Attack every 12 ticks (0.6 seconds — Minecraft attack cooldown)
            if (sp.getAttackStrengthScale(0.5f) >= 0.9f && equipWeapon(sp)) {
                // Attack the tracked target directly after explicit reach and
                // line-of-sight checks. View-vector leftClick could hit a
                // different mob standing between two nearby threats.
                Interaction.attackEntity(sp, target);
            }
        }

        // Safety: max 400 ticks (20 seconds) of fighting
        if (fightTicks > 400) {
            bodyLog.report("fight has gone on too long, disengaging");
            reset();
        }
    }

    // ── Flee logic ─────────────────────────────────────────────────

    private void tickFleeing(AgentPlayer companion, InputDriver input,
                              ServerPlayer sp, List<LivingEntity> threats) {
        fleeTicks++;

        // Find the most dangerous threat to flee from
        LivingEntity fleeFrom = threats.isEmpty() ? target : threats.get(0);
        if (fleeFrom == null) {
            reset();
            return;
        }

        // Calculate flee direction: away from the threat
        Vec3 away = sp.position().subtract(fleeFrom.position()).normalize();
        input.setForward(1.0f);
        input.setSprinting(true);
        input.setJumping(true);

        // Face the flee direction
        float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
        sp.setYRot(yaw);
        sp.setXRot(0);

        // Check if we're safe now
        if (fleeFrom.distanceTo(sp) > SCAN_RADIUS || !fleeFrom.isAlive()) {
            bodyLog.report("escaped from " + entityName(fleeFrom));
            reset();
            return;
        }

        // Safety: max 200 ticks (10 seconds) of fleeing
        if (fleeTicks > 200) {
            bodyLog.report("couldn't escape, stopping flee");
            reset();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }

    /** Find hostile mobs within scan radius, sorted by distance (nearest first). */
    private static List<LivingEntity> findThreats(ServerPlayer player,
                                                   boolean includeNearbyCreepers) {
        AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
        List<LivingEntity> threats = new ArrayList<>();
        for (Entity entity : player.level().getEntities(player, box)) {
            if (entity instanceof Monster monster && monster.isAlive()
                    && (monster.getTarget() == player
                        || (includeNearbyCreepers && monster instanceof Creeper
                            && monster.distanceTo(player) < CREEPER_FLEE_DISTANCE))) {
                threats.add(monster);
            }
        }
        threats.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));
        return threats;
    }

    private static boolean isCreeper(LivingEntity entity) {
        return entity instanceof Creeper;
    }

    private static boolean hasWeapon(ServerPlayer player) {
        return findWeaponSlot(player) >= 0;
    }

    private static int findWeaponSlot(ServerPlayer player) {
        var inv = player.getInventory();
        // Search carried inventory; equipWeapon performs a real hotbar swap.
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof SwordItem) {
                return i;
            }
            if (stack.getItem() instanceof TieredItem) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equipWeapon(ServerPlayer player) {
        int slot = findWeaponSlot(player);
        if (slot < 0) return false;
        var inventory = player.getInventory();
        if (slot < 9) {
            inventory.selected = slot;
        } else {
            int selected = inventory.selected;
            ItemStack displaced = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(slot));
            inventory.setItem(slot, displaced);
        }
        TaskContext.syncInventory(player);
        return true;
    }

    private boolean canFight(AgentPlayer companion, ServerPlayer player) {
        return reflexEnabled("fight_back", companion, config.fightBack())
                && hasWeapon(player) && companion.health() > config.healthFlee();
    }

    private static boolean reflexEnabled(String id, AgentPlayer companion,
                                         boolean fallback) {
        return com.mineagent.api.task.reflex.ReflexRegistry.get(id)
                .map(reflex -> reflex.isEnabled(companion)).orElse(fallback);
    }

    private static void lookAt(ServerPlayer player, Entity target) {
        Vec3 dir = target.position().add(0, target.getEyeHeight() / 2, 0)
                .subtract(player.getEyePosition());
        double dist = dir.horizontalDistance();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, dist));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private static String entityName(LivingEntity entity) {
        if (entity == null) return "unknown";
        return entity.getType().getDescriptionId().replace("entity.", "");
    }
}
