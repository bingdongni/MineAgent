package com.mineagent.engine.act;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.phys.Vec3;

/**
 * Ranged combat calculations — provides projectile prediction, launch
 * angle calculation, and shooting/throwing logic for ranged attacks.
 *
 * <p>Used by RangedAttackTool and MobDefenseChain when the companion
 * has a ranged weapon.
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class Ballistics {

    /** Gravity constant for thrown projectiles (snowballs, ender pearls). */
    private static final double THROW_GRAVITY = 0.03;

    /** Gravity constant for arrows (lower than thrown items). */
    private static final double ARROW_GRAVITY = 0.006;

    /** Default throw power multiplier. */
    private static final float DEFAULT_THROW_POWER = 1.5f;

    /** Bow charge ticks for full charge (vanilla: 20 ticks = 1 second). */
    private static final int BOW_FULL_CHARGE_TICKS = 20;

    /** Crossbow charge ticks. */
    private static final int CROSSBOW_CHARGE_TICKS = 25;

    /** Maximum prediction ticks ahead (prevent excessive computation). */
    private static final int MAX_PREDICTION_TICKS = 100;

    private Ballistics() {}

    // ── Position Prediction ───────────────────────────────────────

    /**
     * Predict where a living entity will be after the given number of ticks.
     *
     * <p>Uses the entity's current velocity (delta movement) to extrapolate
     * position. Accounts for drag and gravity for a simple linear
     * prediction.
     *
     * @param target     the entity to predict position for
     * @param ticksAhead number of ticks to predict ahead
     * @return predicted position as a Vec3
     */
    public static Vec3 predictPosition(LivingEntity target, int ticksAhead) {
        try {
            if (target == null) return Vec3.ZERO;
            if (ticksAhead <= 0) return target.position();
            if (ticksAhead > MAX_PREDICTION_TICKS) ticksAhead = MAX_PREDICTION_TICKS;

            Vec3 currentPos = target.position();
            Vec3 velocity = target.getDeltaMovement();

            // Simple kinematic prediction with drag
            // Minecraft applies 0.91 drag per tick for horizontal, 0.98 for vertical
            double dragH = 0.91;
            double dragV = 0.98;
            double gravity = 0.08; // Standard entity gravity

            double x = currentPos.x;
            double y = currentPos.y;
            double z = currentPos.z;
            double vx = velocity.x;
            double vy = velocity.y;
            double vz = velocity.z;

            for (int t = 0; t < ticksAhead; t++) {
                x += vx;
                y += vy;
                z += vz;
                // Apply drag
                vx *= dragH;
                vz *= dragH;
                vy = (vy - gravity) * dragV;
            }

            return new Vec3(x, y, z);
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.predictPosition error: " + e.getMessage());
            return target != null ? target.position() : Vec3.ZERO;
        }
    }

    // ── Launch Angle ──────────────────────────────────────────────

    /**
     * Calculate the launch angle (elevation) to hit a target from
     * a given position with a given velocity.
     *
     * <p>Uses the ballistic trajectory equation to find the angle
     * that will hit the target. If the target is out of range,
     * returns 45 degrees (maximum range angle).
     *
     * @param from     the launch position
     * @param to       the target position
     * @param velocity the initial velocity magnitude (blocks/tick)
     * @return launch angle in radians
     */
    public static double calculateLaunchAngle(Vec3 from, Vec3 to, double velocity) {
        try {
            if (from == null || to == null || velocity <= 0) return Math.PI / 4;

            double dx = to.x - from.x;
            double dz = to.z - from.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            double dy = to.y - from.y;

            // g = gravity per tick squared (adjusted for tick-based simulation)
            double g = 2 * ARROW_GRAVITY;

            double vSq = velocity * velocity;
            double vSqSq = vSq * vSq;

            // Discriminant of the ballistic equation
            double discriminant = vSqSq - g * (g * horizontalDist * horizontalDist + 2 * dy * vSq);

            if (discriminant < 0) {
                // Out of range — use 45 degrees (maximum range)
                return Math.PI / 4;
            }

            // Two possible angles — use the lower one (more direct shot)
            double sqrtDiscriminant = Math.sqrt(discriminant);
            double angle1 = Math.atan2(vSq - sqrtDiscriminant, g * horizontalDist);
            double angle2 = Math.atan2(vSq + sqrtDiscriminant, g * horizontalDist);

            // Use the lower angle for a flatter trajectory (more reliable)
            return Math.min(angle1, angle2);
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.calculateLaunchAngle error: " + e.getMessage());
            return Math.PI / 4;
        }
    }

    // ── Projectile Throwing ───────────────────────────────────────

    /**
     * Throw a projectile (snowball, ender pearl, etc.) at a target entity.
     *
     * <p>The method predicts the target's future position based on
     * distance, calculates the throw vector, and uses the item.
     *
     * @param player the server player throwing
     * @param target the target entity
     * @return true if the projectile was thrown
     */
    public static boolean throwProjectile(ServerPlayer player, Entity target) {
        try {
            if (player == null || target == null) return false;

            ItemStack mainHand = player.getMainHandItem();
            if (mainHand.isEmpty()) return false;

            Item item = mainHand.getItem();
            if (!(item instanceof SnowballItem) && !(item instanceof EnderpearlItem)
                    && !isThrowableItem(item)) {
                return false;
            }

            // Calculate lead time based on distance
            double dist = player.distanceTo(target);
            int leadTicks = (int) Math.ceil(dist / 1.5); // Approximate flight time

            // Predict target position
            Vec3 targetPos = target instanceof LivingEntity living
                    ? predictPosition(living, leadTicks)
                    : target.position();

            // Calculate throw vector
            Vec3 from = player.getEyePosition();
            Vec3 throwVel = getThrowVelocity(from, targetPos, DEFAULT_THROW_POWER);

            // Face the throw direction
            faceDirection(player, throwVel);

            // Use the item (throw it)
            return Interaction.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.throwProjectile error: " + e.getMessage());
            return false;
        }
    }

    // ── Arrow Shooting ────────────────────────────────────────────

    /**
     * Shoot an arrow from a bow or crossbow at a target entity.
     *
     * <p>The method calculates charge time, predicts target position
     * with lead, and fires the shot.
     *
     * @param player the server player shooting
     * @param target the target entity
     * @return true if the arrow was shot
     */
    public static boolean shootArrow(ServerPlayer player, Entity target) {
        try {
            if (player == null || target == null) return false;

            ItemStack mainHand = player.getMainHandItem();
            if (mainHand.isEmpty()) return false;

            Item weapon = mainHand.getItem();
            if (!(weapon instanceof BowItem) && !(weapon instanceof CrossbowItem)) {
                return false;
            }

            // Calculate distance and charge time
            double dist = player.distanceTo(target);
            float chargeTime = calculateChargeTime(weapon, dist);

            // Calculate lead time based on arrow velocity and distance
            float arrowVelocity = getProjectileVelocity(mainHand);
            int leadTicks = (int) Math.ceil(dist / (arrowVelocity * 0.8));
            leadTicks = Math.min(leadTicks, MAX_PREDICTION_TICKS);

            // Predict target position with lead
            Vec3 targetPos = target instanceof LivingEntity living
                    ? predictPosition(living, leadTicks)
                    : target.position();

            // Calculate launch angle
            Vec3 from = player.getEyePosition();
            double launchAngle = calculateLaunchAngle(from, targetPos, arrowVelocity);

            // Calculate the direction vector
            Vec3 toTarget = targetPos.subtract(from);
            double horizontalDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

            // Build the aim direction
            Vec3 aimDir;
            if (horizontalDist > 0.001) {
                aimDir = new Vec3(
                        toTarget.x / horizontalDist * Math.cos(launchAngle),
                        Math.sin(launchAngle),
                        toTarget.z / horizontalDist * Math.cos(launchAngle)
                ).normalize();
            } else {
                aimDir = new Vec3(0, Math.sin(launchAngle), Math.cos(launchAngle));
            }

            // Face the aim direction
            faceDirection(player, aimDir);

            // Use the bow/crossbow
            return Interaction.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.shootArrow error: " + e.getMessage());
            return false;
        }
    }

    // ── Projectile Velocity ───────────────────────────────────────

    /**
     * Get the projectile velocity for the given weapon.
     *
     * <p>Bow velocity depends on charge level; crossbow is fixed.
     *
     * @param weapon the weapon item stack
     * @return projectile velocity in blocks/tick
     */
    public static float getProjectileVelocity(ItemStack weapon) {
        try {
            if (weapon == null || weapon.isEmpty()) return 0;

            Item item = weapon.getItem();
            if (item instanceof BowItem) {
                // Full charge = 3.0 blocks/tick (vanilla max)
                // Assuming full charge for simplicity
                return 3.0f;
            }
            if (item instanceof CrossbowItem) {
                // Crossbow: 3.15 blocks/tick (vanilla, slightly faster)
                return 3.15f;
            }

            // Default throw velocity
            return 1.5f;
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.getProjectileVelocity error: " + e.getMessage());
            return 1.5f;
        }
    }

    // ── Throw Velocity ────────────────────────────────────────────

    /**
     * Calculate the throw velocity vector from one position to another.
     *
     * <p>Accounts for gravity to create an arc trajectory.
     *
     * @param from  the throw origin
     * @param to    the target position
     * @param power the throw power multiplier
     * @return the velocity vector (blocks/tick)
     */
    public static Vec3 getThrowVelocity(Vec3 from, Vec3 to, float power) {
        try {
            if (from == null || to == null) return new Vec3(0, power, 0);

            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double dz = to.z - from.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            if (horizontalDist < 0.001) {
                // Target is directly above or below
                return new Vec3(0, dy > 0 ? power : -power, 0);
            }

            // Calculate time of flight based on horizontal distance and power
            double time = horizontalDist / power;
            if (time < 1) time = 1;

            // Calculate the required vertical velocity to hit the target
            // considering gravity: y = vy*t - 0.5*g*t^2
            // => vy = (dy + 0.5*g*t^2) / t
            double gravityAcc = THROW_GRAVITY;
            double vy = (dy + 0.5 * gravityAcc * time * time) / time;

            // Horizontal velocity components
            double vx = dx / time;
            double vz = dz / time;

            // Scale to match the desired power
            double currentSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (currentSpeed > 0.001) {
                double scale = power / currentSpeed;
                vx *= scale;
                vy *= scale;
                vz *= scale;
            }

            return new Vec3(vx, vy, vz);
        } catch (Exception e) {
            System.err.println("[MineAgent] Ballistics.getThrowVelocity error: " + e.getMessage());
            return new Vec3(0, power, 0);
        }
    }

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Calculate the charge time (in ticks) needed for a bow/crossbow
     * to reach adequate charge for the given distance.
     *
     * @param weapon the weapon item
     * @param dist   the distance to the target
     * @return charge time in ticks (0.0 to 1.0 normalized)
     */
    private static float calculateChargeTime(Item weapon, double dist) {
        if (weapon instanceof BowItem) {
            // Longer distance = more charge needed
            // Minimum 10 ticks for medium charge, 20 for full
            if (dist < 10) return 0.65f;    // Medium charge for close targets
            if (dist < 20) return 0.85f;    // High charge for mid-range
            return 1.0f;                     // Full charge for long range
        }
        if (weapon instanceof CrossbowItem) {
            return 1.0f; // Crossbow always full charge
        }
        return 0.5f;
    }

    /**
     * Face the player towards a direction vector.
     *
     * @param player the server player
     * @param dir    the direction to face
     */
    private static void faceDirection(ServerPlayer player, Vec3 dir) {
        if (dir.lengthSqr() < 0.0001) return;
        double horizontalDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontalDist));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    /**
     * Check if an item is a throwable projectile (beyond the known types).
     *
     * @param item the item to check
     * @return true if throwable
     */
    private static boolean isThrowableItem(Item item) {
        // Check for other throwable items by class name
        String className = item.getClass().getSimpleName();
        return className.contains("Egg") || className.contains("Potion")
                || className.contains("ExperienceBottle") || className.contains("FireCharge");
    }
}
