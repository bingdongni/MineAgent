package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.engine.task.TaskContext;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
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
    private final PlayerNav nav;
    private BlockPos lastNavTarget;
    private int retargetTicks;

    public MobDefenseChain(AgentPlayer companion, SurvivalConfig config) {
        this.companion = companion;
        this.config = config;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
        this.nav = new PlayerNav(companion, TaskContext.navCaches(companion));
    }

    @Override
    public String name() {
        return "mob_defense";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            // If actively fighting or fleeing, keep going
            if (mode == Mode.FIGHTING || mode == Mode.FLEEING) {
                ServerPlayer activePlayer = ((CompanionEntity) companion).serverPlayer();
                List<LivingEntity> activeThreats = findThreats(activePlayer, companion);
                if (activeThreats.isEmpty()) return Float.NEGATIVE_INFINITY;
                float dynamic = SurvivalDecisions.mobDefensePriority(
                        companion.health(), activePlayer.getMaxHealth(), activeThreats.size());
                return mode == Mode.FLEEING || !canFight(companion, activePlayer)
                        ? Math.max(PRIORITY_FLEE, dynamic) : dynamic;
            }

            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();

            // Check for nearby hostile mobs
            List<LivingEntity> threats = findThreats(sp, companion);
            if (threats.isEmpty()) {
                return Float.NEGATIVE_INFINITY;
            }

            // Check if any creeper is too close — always triggers flee
            // Honor the per-companion avoid_creeper policy. Previously this
            // branch always fled, making both config and UI toggles inert.
            if (avoidsCreepers(companion)) {
                for (LivingEntity threat : threats) {
                    if (isCreeper(threat) && threat.distanceTo(sp) < CREEPER_FLEE_DISTANCE) {
                        return PRIORITY_FLEE;
                    }
                }
            }

            // Being attacked or threatened — decide fight or flee
            LivingEntity nearest = threats.get(0);
            boolean canFight = canFight(companion, sp);

            float dynamic = SurvivalDecisions.mobDefensePriority(
                    companion.health(), sp.getMaxHealth(), threats.size());
            return canFight ? dynamic : Math.max(PRIORITY_FLEE, dynamic);
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
            List<LivingEntity> threats = findThreats(sp, companion);

            // Check if we need to switch mode (e.g., creeper appeared while fighting)
            if (mode == Mode.IDLE || mode == Mode.FIGHTING) {
                if (avoidsCreepers(companion)) {
                    for (LivingEntity threat : threats) {
                        if (isCreeper(threat) && threat.distanceTo(sp) < CREEPER_FLEE_DISTANCE) {
                            mode = Mode.FLEEING;
                            target = threat;
                            nav.cancel();
                            lastNavTarget = null;
                            retargetTicks = 0;
                            bodyLog.report("spotted a creeper, running away!");
                            break;
                        }
                    }
                }
                // Low health while fighting? Switch to flee
                if (mode == Mode.FIGHTING && !canFight(companion, sp)) {
                    mode = Mode.FLEEING;
                    nav.cancel();
                    lastNavTarget = null;
                    retargetTicks = 0;
                    bodyLog.report("retreating from combat");
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
            nav.cancel();
            inputDriver(companion).clear();
        } catch (Exception ignored) {
        }
        mode = Mode.IDLE;
        target = null;
        fightTicks = 0;
        fleeTicks = 0;
        lastNavTarget = null;
        retargetTicks = 0;
    }

    private boolean canFight(AgentPlayer companion, ServerPlayer sp) {
        boolean reflexEnabled = com.mineagent.api.task.reflex.ReflexRegistry
                .get("fight_back").map(r -> r.isEnabled(companion)).orElse(true);
        // Config seeds the reflex at spawn; consulting it here as a second
        // ceiling would make a later owner-issued enable ineffective.
        return reflexEnabled && hasWeapon(sp)
                && companion.health() > config.healthFlee();
    }

    private static boolean avoidsCreepers(AgentPlayer companion) {
        return com.mineagent.api.task.reflex.ReflexRegistry.get("avoid_creeper")
                .map(r -> r.isEnabled(companion)).orElse(true);
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

        if (dist > 3.0 || !sp.hasLineOfSight(target)) {
            // Refresh a collision-aware pursuit as the target moves. This
            // invokes real clearing/bridging actions instead of walking into
            // the first wall between the companion and the hostile.
            BlockPos targetPos = target.blockPosition();
            retargetTicks++;
            boolean moved = lastNavTarget == null
                    || lastNavTarget.distManhattan(targetPos) >= 2;
            if (!nav.isNavigating() || (retargetTicks >= 20 && moved)) {
                // Radius one prevents a wall two blocks away from satisfying
                // the goal while the target remains unreachable through it.
                nav.navigateNear(targetPos.getX(), targetPos.getY(),
                        targetPos.getZ(), 1);
                lastNavTarget = targetPos.immutable();
                retargetTicks = 0;
            }
            nav.tick();
        } else {
            // In attack range — stop moving and attack
            nav.cancel();
            input.clear();

            // Attack every 12 ticks (0.6 seconds — Minecraft attack cooldown)
            if (fightTicks % 12 == 0) {
                // Hold weapon
                int weaponSlot = findWeaponSlot(sp);
                if (weaponSlot >= 0) {
                    if (weaponSlot < 9) {
                        companion.holdInHand(weaponSlot);
                    } else {
                        var inventory = sp.getInventory();
                        int selected = inventory.selected;
                        var selectedStack = inventory.getItem(selected);
                        inventory.setItem(selected, inventory.getItem(weaponSlot));
                        inventory.setItem(weaponSlot, selectedStack);
                        com.mineagent.engine.task.TaskContext.syncInventory(sp);
                        companion.holdInHand(selected);
                    }
                }
                // Direct ServerPlayer#attack bypasses the normal reach and
                // line-of-sight validation. Use the shared vanilla interaction
                // gate so the companion cannot hit through a wall.
                if (Interaction.attackEntity(sp, target)) {
                    sp.swing(InteractionHand.MAIN_HAND);
                }
            }
        }

        // Safety: max 400 ticks (20 seconds) of fighting
        if (fightTicks > 400) {
            // Resetting to IDLE while the same mob still targets the player
            // re-entered FIGHTING on the next tick and made the timeout inert.
            // A prolonged fight should transition to the bounded flee state.
            bodyLog.report("fight has gone on too long, retreating");
            nav.cancel();
            mode = Mode.FLEEING;
            fleeTicks = 0;
            lastNavTarget = null;
            retargetTicks = 0;
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

        // Check if we're safe now
        if (fleeFrom.distanceTo(sp) > SCAN_RADIUS || !fleeFrom.isAlive()) {
            bodyLog.report("escaped from " + entityName(fleeFrom));
            reset();
            return;
        }

        // Re-plan around moving threats. Raw backward/sprint input can run
        // off cliffs and cannot route around the obstacle causing the danger.
        BlockPos threatPos = fleeFrom.blockPosition();
        retargetTicks++;
        boolean moved = lastNavTarget == null
                || lastNavTarget.distManhattan(threatPos) >= 2;
        if (!nav.isNavigating() || (retargetTicks >= 20 && moved)) {
            nav.runAway(threatPos.getX(), threatPos.getY(), threatPos.getZ(), SCAN_RADIUS);
            lastNavTarget = threatPos.immutable();
            retargetTicks = 0;
        }
        nav.tick();

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
                                                   AgentPlayer companion) {
        AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
        List<LivingEntity> threats = new ArrayList<>();
        for (Entity entity : player.level().getEntities(player, box)) {
            if (entity instanceof Monster monster && monster.isAlive()
                    && (monster.getTarget() == player
                    || (monster instanceof Creeper
                    && avoidsCreepers(companion)
                    && monster.distanceTo(player) < CREEPER_FLEE_DISTANCE))) {
                // A primed/nearby creeper is dangerous before its target field
                // necessarily points at the fake player. Include it so the
                // configured avoidance reflex has an actual execution path.
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
        // Only carried inventory is legal. Inventory slots 36-39 are armor;
        // swapping an unusual component-modified tiered item out of one would
        // place an arbitrary hotbar item into an equipment-only slot.
        int carried = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < carried; i++) {
            ItemStack stack = inv.getItem(i);
            if (!hasUsableDurability(stack)) continue;
            if (stack.getItem() instanceof SwordItem) {
                return i;
            }
            if (stack.getItem() instanceof TieredItem) {
                return i;
            }
        }
        if (inv.getContainerSize() > 40) {
            ItemStack offhand = inv.getItem(40);
            if (hasUsableDurability(offhand)
                    && (offhand.getItem() instanceof SwordItem
                    || offhand.getItem() instanceof TieredItem)) {
                return 40;
            }
        }
        return -1;
    }

    private static boolean hasUsableDurability(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (!stack.isDamageableItem()
                || stack.getMaxDamage() - stack.getDamageValue() > 1);
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
