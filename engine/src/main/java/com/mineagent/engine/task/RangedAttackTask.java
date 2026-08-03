package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.pathing.cache.PathCaches;
import com.mineagent.engine.pathing.execute.PlayerNav;
import com.mineagent.tools.combat.RangedAttackTool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Executes a ranged attack task - tracks the target entity, navigates
 * to effective weapon range, calculates lead angle using ballistics,
 * and fires the projectile.
 */
public class RangedAttackTask extends CompanionTask<RangedAttackTool.RangedAttackTaskRecord> {

    private enum Phase { NAVIGATE, AIM, SHOOT, COOLDOWN, DONE }

    /** Ticks to wait after shooting before next shot. */
    private static final int SHOT_COOLDOWN = 40;
    /** Minimum companion health to keep fighting. */
    private static final float MIN_HEALTH = 5.0f;
    /** Max number of shots before giving up. */
    private static final int MAX_SHOTS = 10;

    private PlayerNav nav;
    private Phase phase;
    private Entity target;
    private int cooldownTicks;
    private int aimTicks;
    private int shotsFired;
    private String failReason;
    private double effectiveRange;
    private boolean useStarted;
    private int blockedSightTicks;
    private BlockPos firingPosition;

    /** Aim time in ticks before releasing the shot. */
    private static final int AIM_TICKS = 10;
    /** Bound time spent trying to obtain a shot instead of firing into terrain. */
    private static final int MAX_BLOCKED_SIGHT_TICKS = 200;

    public RangedAttackTask(AgentPlayer player, RangedAttackTool.RangedAttackTaskRecord record) {
        super(player, record);
    }

    @Override
    public void onStart() {
        phase = Phase.NAVIGATE;
        cooldownTicks = 0;
        aimTicks = 0;
        shotsFired = 0;
        failReason = null;
        useStarted = false;
        blockedSightTicks = 0;
        firingPosition = null;

        // Resolve target entity
        ServerLevel level = TaskContext.serverPlayer(player).serverLevel();
        target = level.getEntity(record.entityId);
        if (target == null || !target.isAlive()) {
            failReason = "Target entity " + record.entityId + " not found or dead";
            phase = Phase.DONE;
            return;
        }
        if (target == TaskContext.serverPlayer(player) || !target.isAttackable()) {
            failReason = "Target entity " + record.entityId + " cannot be attacked";
            phase = Phase.DONE;
            return;
        }

        // Determine weapon type and effective range. Ender pearls used to be
        // accepted as "ammo", which teleported the companion instead of
        // attacking; only weapons promised by the tool contract are legal.
        if (!isSupportedWeapon(TaskContext.serverPlayer(player).getMainHandItem())) {
            failReason = "Main hand must hold a bow, crossbow, or trident";
            phase = Phase.DONE;
            return;
        }
        String weaponId = getWeaponId();
        effectiveRange = Ballistics.effectiveRange(weaponId);

        // Check ammo
        if (!hasAmmo()) {
            failReason = "No ammo for ranged weapon";
            phase = Phase.DONE;
            return;
        }

        PathCaches caches = TaskContext.navCaches(player);
        nav = new PlayerNav(player, caches);
        nav.setListener(new PlayerNav.NavListener() {
            @Override
            public void onGoalReached() {
                if (phase == Phase.NAVIGATE) {
                    firingPosition = null;
                    phase = Phase.AIM;
                }
            }

            @Override
            public void onNavigationFailed(String reason) {
                failReason = "Navigation to range failed: " + reason;
                phase = Phase.DONE;
            }
        });

        navigateToRange();
    }

    @Override
    public TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            cancelNav();
            return TaskState.FAILED;
        }

        // Check companion health
        if (player.health() < MIN_HEALTH && player.isAlive()) {
            failReason = "Companion health critical, retreating";
            phase = Phase.DONE;
        }
        if (!player.isAlive()) {
            failReason = "Companion died";
            phase = Phase.DONE;
        }

        // Check target
        if (target == null || !target.isAlive()) {
            cancelNav();
            return TaskState.SUCCESS; // target died
        }

        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case AIM -> tickAim();
            case SHOOT -> tickShoot();
            case COOLDOWN -> tickCooldown();
            case DONE -> {}
        }

        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return TaskState.RUNNING;
    }

    private void tickNavigate() {
        nav.tick();

        if (firingPosition != null) {
            // Sight-position navigation is a concrete exact goal. The old
            // generic range check below cancelled it immediately because the
            // player was already within weapon range, so no repositioning
            // ever occurred behind cover.
            if (++blockedSightTicks > MAX_BLOCKED_SIGHT_TICKS) {
                nav.cancel();
                firingPosition = null;
                failReason = "Could not reach a clear ranged firing position";
                phase = Phase.DONE;
            }
            return;
        }

        // Check if we're already in range
        double dist = horizontalDistanceToTarget();
        if (dist <= effectiveRange && dist >= 5.0) {
            nav.cancel();
            phase = Phase.AIM;
        }
    }

    private void tickAim() {
        // Check if still in range
        double dist = horizontalDistanceToTarget();
        if (dist > effectiveRange + 5.0) {
            navigateToRange();
            return;
        }
        if (dist < 5.0) {
            // Backing up under raw input can walk over a cliff. Use the same
            // collision-aware path executor to establish shooting distance.
            nav.runAway(target.blockPosition().getX(),
                    target.blockPosition().getY(),
                    target.blockPosition().getZ(), 6.0);
            phase = Phase.NAVIGATE;
            return;
        }

        var sp = TaskContext.serverPlayer(player);
        if (!sp.hasLineOfSight(target)) {
            // Direct game-mode item use has no packet-handler ray validation.
            // Never consume ammunition into a wall. Reposition for a bounded
            // period, then fail explicitly if the target remains occluded.
            sp.stopUsingItem();
            useStarted = false;
            aimTicks = 0;
            repositionForLineOfSight();
            return;
        }
        blockedSightTicks = 0;

        TaskContext.inputDriver(player).clear();

        // Calculate aim
        aimTicks++;
        aimAtTarget();

        ItemStack weapon = TaskContext.serverPlayer(player).getMainHandItem();
        if (!useStarted && requiresCharging(weapon)) {
            if (weapon.is(Items.CROSSBOW) && CrossbowItem.isCharged(weapon)) {
                useStarted = true;
            } else {
                sp.gameMode.useItem(sp, sp.serverLevel(), weapon, InteractionHand.MAIN_HAND);
                useStarted = sp.isUsingItem();
                if (!useStarted) {
                    failReason = "Ranged weapon could not begin charging";
                    phase = Phase.DONE;
                    return;
                }
            }
        }

        int requiredCharge = Math.max(AIM_TICKS, record.chargeTicks);
        if (weapon.is(Items.CROSSBOW)) {
            requiredCharge = Math.max(requiredCharge,
                    CrossbowItem.getChargeDuration(weapon, TaskContext.serverPlayer(player)));
        }
        if (aimTicks >= requiredCharge) {
            phase = Phase.SHOOT;
        }
    }

    private void tickShoot() {
        // Check ammo
        if (!hasAmmo()) {
            failReason = "Out of ammo";
            phase = Phase.DONE;
            return;
        }

        var sp = TaskContext.serverPlayer(player);
        if (!sp.hasLineOfSight(target)) {
            // The target can move behind cover during the final tick after
            // charging. Preserve ammunition and return to the same bounded
            // sight-acquisition path rather than releasing through the wall.
            sp.stopUsingItem();
            useStarted = false;
            aimTicks = 0;
            repositionForLineOfSight();
            return;
        }

        // Final aim adjustment and shoot
        aimAtTarget();

        ItemStack weapon = sp.getMainHandItem();
        boolean fired;
        if (weapon.is(Items.BOW) || weapon.is(Items.TRIDENT)) {
            fired = sp.isUsingItem();
            sp.releaseUsingItem();
        } else if (weapon.is(Items.CROSSBOW)) {
            if (sp.isUsingItem()) {
                sp.releaseUsingItem();
            }
            fired = CrossbowItem.isCharged(weapon)
                    && sp.gameMode.useItem(sp, sp.serverLevel(), weapon,
                            InteractionHand.MAIN_HAND).consumesAction();
        } else {
            fired = sp.gameMode.useItem(sp, sp.serverLevel(), weapon,
                    InteractionHand.MAIN_HAND).consumesAction();
        }

        if (!fired) {
            failReason = "Ranged weapon failed to fire";
            phase = Phase.DONE;
            return;
        }
        sp.swing(InteractionHand.MAIN_HAND);

        shotsFired++;
        useStarted = false;
        cooldownTicks = SHOT_COOLDOWN;
        phase = Phase.COOLDOWN;

        if (shotsFired >= MAX_SHOTS) {
            failReason = "Max shots (" + MAX_SHOTS + ") reached without killing target";
            phase = Phase.DONE;
        }
    }

    private void tickCooldown() {
        cooldownTicks--;
        if (cooldownTicks <= 0) {
            // Check if target is still alive
            if (target == null || !target.isAlive()) {
                cancelNav();
                return;
            }
            phase = Phase.AIM;
            aimTicks = 0;
            useStarted = false;
        }
    }

    private void navigateToRange() {
        if (target == null || !target.isAlive()) return;
        firingPosition = null;
        // Navigate to a position at ~70% of effective range from target
        int targetRange = (int) (effectiveRange * 0.7);
        nav.navigateNear(
                target.blockPosition().getX(),
                target.blockPosition().getY(),
                target.blockPosition().getZ(),
                targetRange
        );
        phase = Phase.NAVIGATE;
    }

    /** Navigate to the nearest loaded, collision-safe cell with target LOS. */
    private void repositionForLineOfSight() {
        if (++blockedSightTicks > MAX_BLOCKED_SIGHT_TICKS) {
            failReason = "No line of sight to ranged target";
            phase = Phase.DONE;
            return;
        }
        BlockPos candidate = findFiringPosition();
        if (candidate == null) {
            // The target may move out of cover on a later tick. Keep AIM as a
            // bounded acquisition state instead of repeatedly resetting an
            // already-satisfied GoalNear.
            phase = Phase.AIM;
            return;
        }
        firingPosition = candidate;
        nav.navigateTo(candidate.getX(), candidate.getY(), candidate.getZ());
        phase = Phase.NAVIGATE;
    }

    private BlockPos findFiringPosition() {
        var sp = TaskContext.serverPlayer(player);
        var level = sp.serverLevel();
        BlockPos center = target.blockPosition();
        Vec3 targetPoint = target.position().add(0.0,
                target.getBbHeight() * 0.65, 0.0);
        int maxRadius = Math.max(6, (int) Math.floor(effectiveRange - 1.0));
        int[] radii = {6, Math.max(7, maxRadius / 2), maxRadius};
        int[] yOffsets = {0, 1, -1, 2, -2, 3, -3, 4, -4};
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        // A bounded polar sample is sufficient for local combat movement and
        // avoids loading chunks or running another unbounded world search.
        for (int radius : radii) {
            for (int i = 0; i < 16; i++) {
                double angle = i * (Math.PI * 2.0 / 16.0);
                int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
                for (int yOffset : yOffsets) {
                    BlockPos feet = new BlockPos(x, center.getY() + yOffset, z);
                    if (!level.hasChunkAt(feet)
                            || !level.getWorldBorder().isWithinBounds(feet)
                            || feet.getY() < level.getMinBuildHeight()
                            || feet.getY() + 1 >= level.getMaxBuildHeight()) {
                        continue;
                    }
                    var feetState = level.getBlockState(feet);
                    var headState = level.getBlockState(feet.above());
                    var supportState = level.getBlockState(feet.below());
                    if (!com.mineagent.engine.pathing.util.BlockHelper.isPassable(feetState)
                            || !com.mineagent.engine.pathing.util.BlockHelper.isPassable(headState)
                            || !com.mineagent.engine.pathing.util.BlockHelper.canStandOn(supportState)) {
                        continue;
                    }
                    double dx = feet.getX() + 0.5 - targetPoint.x;
                    double dz = feet.getZ() + 0.5 - targetPoint.z;
                    double horizontal = Math.hypot(dx, dz);
                    if (horizontal < 5.0 || horizontal > effectiveRange) continue;

                    var movedBox = sp.getBoundingBox().move(
                            feet.getX() + 0.5 - sp.getX(),
                            feet.getY() - sp.getY(),
                            feet.getZ() + 0.5 - sp.getZ());
                    if (!level.noCollision(sp, movedBox)) continue;

                    Vec3 candidateEye = new Vec3(feet.getX() + 0.5,
                            feet.getY() + sp.getEyeHeight(), feet.getZ() + 0.5);
                    var sight = level.clip(new net.minecraft.world.level.ClipContext(
                            candidateEye, targetPoint,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, sp));
                    if (sight.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                        continue;
                    }
                    double travelDistance = sp.position().distanceToSqr(
                            feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    if (travelDistance < bestDistance) {
                        bestDistance = travelDistance;
                        best = feet.immutable();
                    }
                }
            }
        }
        return best;
    }

    private void aimAtTarget() {
        if (target == null) return;
        var sp = TaskContext.serverPlayer(player);
        Vec3 shooterPos = sp.getEyePosition();
        Vec3 targetPos = target.position().add(0, target.getBbHeight() * 0.85, 0);
        Vec3 targetVel = target.getDeltaMovement();

        String weaponId = getWeaponId();
        double gravity = Ballistics.gravityForWeapon(weaponId);
        double velocity = Ballistics.velocityForWeapon(weaponId, record.chargeTicks);

        // Calculate lead position
        Vec3 leadPos = Ballistics.calculateLead(shooterPos, targetPos, targetVel, velocity, gravity);

        // Calculate direction to lead position
        Vec3 dir = leadPos.subtract(shooterPos);
        double horizontalDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        double heightDiff = dir.y;

        // Calculate pitch using ballistics
        double pitch = Ballistics.calculatePitch(horizontalDist, heightDiff, velocity, gravity);
        if (Double.isNaN(pitch)) {
            // Out of range - use direct aim as fallback
            dir = dir.normalize();
            // Keep pitch in radians; it is converted to degrees exactly once
            // below. The previous double conversion produced extreme angles.
            pitch = Math.asin(-dir.y);
        }

        // Calculate yaw
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        sp.setYRot(yaw);
        sp.setXRot((float) Math.toDegrees(pitch));
    }

    private double horizontalDistanceToTarget() {
        if (target == null) return Double.MAX_VALUE;
        var companionPos = TaskContext.serverPlayer(player).position();
        var targetPos = target.position();
        double dx = companionPos.x - targetPos.x;
        double dz = companionPos.z - targetPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String getWeaponId() {
        return player.mainHandItemId();
    }

    private boolean hasAmmo() {
        var sp = TaskContext.serverPlayer(player);
        var item = sp.getMainHandItem();

        // Bows and crossbows need arrows
        if (item.is(Items.BOW) || item.is(Items.CROSSBOW)) {
            return (item.is(Items.CROSSBOW) && CrossbowItem.isCharged(item))
                    || !sp.getProjectile(item).isEmpty();
        }

        // Tridents are both weapon and ammunition.
        if (item.is(Items.TRIDENT)) {
            return item.getCount() > 0;
        }

        return false;
    }

    private static boolean requiresCharging(ItemStack weapon) {
        return weapon.is(Items.BOW)
                || weapon.is(Items.CROSSBOW)
                || weapon.is(Items.TRIDENT);
    }

    private static boolean isSupportedWeapon(ItemStack weapon) {
        return weapon.is(Items.BOW)
                || weapon.is(Items.CROSSBOW)
                || weapon.is(Items.TRIDENT);
    }

    private void cancelNav() {
        if (nav != null) nav.cancel();
        firingPosition = null;
        TaskContext.inputDriver(player).clear();
        TaskContext.serverPlayer(player).stopUsingItem();
    }

    @Override
    public void onInterrupt() {
        cancelNav();
    }

    @Override
    protected String successMessage() {
        return "Ranged attack completed on entity " + record.entityId + " (shots: " + shotsFired + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "Ranged attack timed out on entity " + record.entityId + " (shots: " + shotsFired + ")";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Ranged attack failed on entity " + record.entityId;
    }
}
