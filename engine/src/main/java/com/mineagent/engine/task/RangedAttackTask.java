package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.combat.RangedAttackTool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks a target and executes the real vanilla use/charge/release lifecycle
 * for bows, crossbows, tridents and immediately thrown projectiles.
 */
public class RangedAttackTask
        extends CompanionTask<RangedAttackTool.RangedAttackTaskRecord> {

    private enum Phase { NAVIGATE, AIM, CHARGE, SHOOT, COOLDOWN, DONE }
    private enum WeaponKind { BOW, CROSSBOW, TRIDENT, THROWABLE, INVALID }

    private static final int SHOT_COOLDOWN = 20;
    private static final int AIM_TICKS = 6;
    private static final int MAX_SHOTS = 10;
    private static final float MIN_HEALTH = 5.0f;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private WeaponKind weaponKind;
    private int cooldownTicks;
    private int aimTicks;
    private int shotsFired;
    private int requiredChargeTicks;
    private int repathTicks;
    private String failReason;
    private double effectiveRange;

    public RangedAttackTask(AgentPlayer player,
                            RangedAttackTool.RangedAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.NAVIGATE;
        cooldownTicks = 0;
        aimTicks = 0;
        shotsFired = 0;
        repathTicks = 0;
        failReason = null;

        ServerPlayer sp = TaskContext.serverPlayer(player);
        ServerLevel level = sp.serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive() || target == sp) {
            fail("Target entity " + record.entityId + " not found or invalid");
            return;
        }

        weaponKind = weaponKind(sp.getMainHandItem());
        if (weaponKind == WeaponKind.INVALID) {
            fail("Main hand does not contain a supported ranged weapon");
            return;
        }
        effectiveRange = Ballistics.effectiveRange(
                player.mainHandItemId(), record.chargeTicks);
        if (!hasAmmo(sp, sp.getMainHandItem(), weaponKind)) {
            fail("No compatible ammunition for the held ranged weapon");
            return;
        }

        // A previous interrupted food/shield/bow action must not leak into a
        // new ranged task. stopUsingItem cancels without releasing a shot.
        if (sp.isUsingItem()) sp.stopUsingItem();

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) {
                    TaskContext.inputDriver(player).clear();
                    phase = Phase.AIM;
                    aimTicks = 0;
                }
            }

            @Override
            public void onNavigationFailed(String reason) {
                fail("Navigation to firing range failed: " + reason);
            }
        });
        navigateToRange();
    }

    @Override
    protected TaskState onTick() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        if (sp.level().getGameTime() >= record.deadline()) {
            cleanup();
            return TaskState.FAILED;
        }
        if (!player.isAlive()) fail("Companion died");
        else if (player.health() < MIN_HEALTH) fail("Companion health critical, retreating");

        if (target == null || !target.isAlive()) {
            cleanup();
            return TaskState.SUCCESS;
        }
        if (target.level() != sp.level()) {
            fail("Target moved to another dimension");
        }

        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case AIM -> tickAim();
            case CHARGE -> tickCharge();
            case SHOOT -> tickShoot();
            case COOLDOWN -> tickCooldown();
            case DONE -> { }
        }

        return phase == Phase.DONE ? TaskState.FAILED : TaskState.RUNNING;
    }

    private void tickNavigate() {
        nav.tick();
        repathTicks++;
        double distance = horizontalDistanceToTarget();
        if (isInFiringRange(distance) && hasClearShot()) {
            nav.cancel();
            TaskContext.inputDriver(player).clear();
            phase = Phase.AIM;
            aimTicks = 0;
            return;
        }

        // A moving target can invalidate an otherwise healthy path. Refresh
        // the range goal periodically instead of walking to stale coordinates.
        if (repathTicks >= 20) navigateToRange();
    }

    private void tickAim() {
        double distance = horizontalDistanceToTarget();
        if (!isInFiringRange(distance) || !hasClearShot()) {
            navigateToRange();
            return;
        }
        aimAtTarget();
        if (++aimTicks >= AIM_TICKS) phase = Phase.SHOOT;
    }

    private void tickShoot() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        ItemStack held = sp.getMainHandItem();
        WeaponKind currentKind = weaponKind(held);
        if (currentKind != weaponKind) {
            fail("Held ranged weapon changed during the task");
            return;
        }
        if (!hasAmmo(sp, held, weaponKind)) {
            fail("Out of compatible ammunition");
            return;
        }
        if (!hasClearShot()) {
            navigateToRange();
            return;
        }
        aimAtTarget();

        if (weaponKind == WeaponKind.CROSSBOW && CrossbowItem.isCharged(held)) {
            if (!Interaction.useItem(sp, InteractionHand.MAIN_HAND)
                    || CrossbowItem.isCharged(sp.getMainHandItem())) {
                fail("Charged crossbow did not fire");
                return;
            }
            finishShot();
            return;
        }

        if (weaponKind == WeaponKind.THROWABLE) {
            int before = held.getCount();
            var beforeItem = held.getItem();
            if (!Interaction.useItem(sp, InteractionHand.MAIN_HAND)) {
                fail("Held projectile refused use");
                return;
            }
            ItemStack after = sp.getMainHandItem();
            if (after.is(beforeItem) && after.getCount() >= before) {
                fail("Projectile use consumed no item");
                return;
            }
            finishShot();
            return;
        }

        if (!Interaction.useItem(sp, InteractionHand.MAIN_HAND)
                || !sp.isUsingItem()
                || sp.getUsedItemHand() != InteractionHand.MAIN_HAND) {
            fail("Ranged weapon did not enter its charging state");
            return;
        }

        requiredChargeTicks = switch (weaponKind) {
            case BOW -> Math.max(3, record.chargeTicks);
            case CROSSBOW -> CrossbowItem.getChargeDuration(held, sp);
            case TRIDENT -> Math.max(10, record.chargeTicks);
            default -> 0;
        };
        phase = Phase.CHARGE;
    }

    private void tickCharge() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        if (!sp.isUsingItem() || weaponKind(sp.getUseItem()) != weaponKind) {
            fail("Ranged weapon charge was interrupted");
            return;
        }
        if (!hasClearShot()) {
            // Losing sight while charging should cancel, not release a blind
            // shot into a wall or nearby friendly entity.
            sp.stopUsingItem();
            navigateToRange();
            return;
        }
        aimAtTarget();
        if (sp.getTicksUsingItem() < requiredChargeTicks) return;

        sp.releaseUsingItem();
        TaskContext.syncInventory(sp);
        if (weaponKind == WeaponKind.CROSSBOW) {
            if (!CrossbowItem.isCharged(sp.getMainHandItem())) {
                fail("Crossbow failed to load after charging");
                return;
            }
            phase = Phase.SHOOT;
        } else {
            finishShot();
        }
    }

    private void finishShot() {
        shotsFired++;
        TaskContext.serverPlayer(player).swing(InteractionHand.MAIN_HAND);
        TaskContext.syncInventory(TaskContext.serverPlayer(player));
        if (shotsFired >= MAX_SHOTS) {
            fail("Max shots (" + MAX_SHOTS + ") reached without killing target");
            return;
        }
        cooldownTicks = SHOT_COOLDOWN;
        phase = Phase.COOLDOWN;
    }

    private void tickCooldown() {
        if (--cooldownTicks <= 0) {
            aimTicks = 0;
            phase = Phase.AIM;
        }
    }

    private void navigateToRange() {
        if (nav == null || target == null || !target.isAlive()) return;
        int targetRange = Math.max(5, (int) Math.floor(effectiveRange * 0.7));
        nav.navigateNear(target.blockPosition().getX(), target.blockPosition().getY(),
                target.blockPosition().getZ(), targetRange);
        repathTicks = 0;
        phase = Phase.NAVIGATE;
    }

    private boolean isInFiringRange(double distance) {
        return distance >= 4.0 && distance <= effectiveRange;
    }

    private boolean hasClearShot() {
        ServerPlayer sp = TaskContext.serverPlayer(player);
        return target != null && target.isAlive() && sp.hasLineOfSight(target);
    }

    private void aimAtTarget() {
        if (target == null) return;
        ServerPlayer sp = TaskContext.serverPlayer(player);
        Vec3 shooterPos = sp.getEyePosition();
        Vec3 targetPos = target.position().add(0, target.getBbHeight() * 0.75, 0);
        Vec3 leadPos = Ballistics.calculateLead(shooterPos, targetPos,
                target.getDeltaMovement(),
                Ballistics.velocityForWeapon(player.mainHandItemId(), record.chargeTicks),
                Ballistics.gravityForWeapon(player.mainHandItemId()));
        Vec3 direction = leadPos.subtract(shooterPos);
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        double pitch = Ballistics.calculatePitch(horizontal, direction.y,
                Ballistics.velocityForWeapon(player.mainHandItemId(), record.chargeTicks),
                Ballistics.gravityForWeapon(player.mainHandItemId()));
        if (!Double.isFinite(pitch)) {
            pitch = -Math.atan2(direction.y, horizontal);
        }
        sp.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        sp.setXRot((float) Math.toDegrees(pitch));
    }

    private double horizontalDistanceToTarget() {
        Vec3 from = TaskContext.serverPlayer(player).position();
        Vec3 to = target.position();
        return Math.hypot(from.x - to.x, from.z - to.z);
    }

    private static WeaponKind weaponKind(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return WeaponKind.INVALID;
        if (stack.is(Items.BOW)) return WeaponKind.BOW;
        if (stack.is(Items.CROSSBOW)) return WeaponKind.CROSSBOW;
        if (stack.is(Items.TRIDENT)) return WeaponKind.TRIDENT;
        if (stack.is(Items.SNOWBALL) || stack.is(Items.EGG)
                || stack.is(Items.ENDER_PEARL)) return WeaponKind.THROWABLE;
        return WeaponKind.INVALID;
    }

    private static boolean hasAmmo(ServerPlayer sp, ItemStack weapon,
                                   WeaponKind kind) {
        return switch (kind) {
            case BOW -> !sp.getProjectile(weapon).isEmpty();
            case CROSSBOW -> CrossbowItem.isCharged(weapon)
                    || !sp.getProjectile(weapon).isEmpty();
            case TRIDENT, THROWABLE -> !weapon.isEmpty() && weapon.getCount() > 0;
            default -> false;
        };
    }

    private void fail(String reason) {
        failReason = reason;
        cleanup();
        phase = Phase.DONE;
    }

    private void cleanup() {
        if (nav != null) nav.cancel();
        ServerPlayer sp = TaskContext.serverPlayer(player);
        if (sp.isUsingItem()) sp.stopUsingItem();
        TaskContext.inputDriver(player).clear();
    }

    @Override
    protected void onInterrupt() {
        cleanup();
    }

    @Override
    protected String successMessage() {
        return "Ranged attack completed on entity " + record.entityId
                + " (shots: " + shotsFired + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "Ranged attack timed out on entity " + record.entityId
                + " (shots: " + shotsFired + ")";
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Ranged attack failed on entity " + record.entityId;
    }
}
