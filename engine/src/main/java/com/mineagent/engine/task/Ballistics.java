package com.mineagent.engine.task;

import net.minecraft.world.phys.Vec3;

/**
 * Ballistic calculations for ranged attacks. Computes the required
 * launch angle and lead position to hit a moving target with a
 * projectile, accounting for gravity.
 */
public final class Ballistics {

    private Ballistics() {}

    /** Gravity acceleration for arrows (blocks/tick^2). */
    public static final double ARROW_GRAVITY = 0.05;
    /** Gravity acceleration for thrown projectiles (snowballs, eggs). */
    public static final double THROWN_GRAVITY = 0.03;
    /** Gravity acceleration for tridents. */
    public static final double TRIDENT_GRAVITY = 0.05;

    /** Default arrow velocity (blocks/tick) at full charge. */
    public static final double ARROW_VELOCITY = 3.0;
    /** Default thrown projectile velocity. */
    public static final double THROWN_VELOCITY = 1.5;
    /** Default trident velocity. */
    public static final double TRIDENT_VELOCITY = 2.5;

    /**
     * Calculate the lead position — where the target will be when
     * the projectile arrives, assuming constant target velocity.
     *
     * @param shooterPos     the shooter's eye position
     * @param targetPos      the target's current position
     * @param targetVelocity the target's velocity (blocks/tick)
     * @param projectileSpeed the projectile's initial speed (blocks/tick)
     * @param gravity        the projectile's gravity (blocks/tick^2)
     * @return the estimated intercept position
     */
    public static Vec3 calculateLead(Vec3 shooterPos, Vec3 targetPos,
                                      Vec3 targetVelocity, double projectileSpeed,
                                      double gravity) {
        Vec3 delta = targetPos.subtract(shooterPos);
        double horizontalDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontalDist < 0.1) return targetPos;

        // Estimate flight time
        double flightTime = horizontalDist / projectileSpeed;

        // Iterate to refine (2 iterations is usually sufficient)
        for (int i = 0; i < 2; i++) {
            // Predict where target will be
            Vec3 predicted = targetPos.add(targetVelocity.scale(flightTime));
            delta = predicted.subtract(shooterPos);
            horizontalDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (horizontalDist < 0.1) return predicted;
            flightTime = horizontalDist / projectileSpeed;
        }

        return targetPos.add(targetVelocity.scale(flightTime));
    }

    /**
     * Calculate the required pitch angle to hit a target at the given
     * horizontal distance and height difference.
     *
     * @param horizontalDist horizontal distance to target
     * @param heightDiff     target height minus shooter height
     * @param velocity       projectile initial velocity
     * @param gravity        projectile gravity
     * @return the required pitch angle in radians, or NaN if impossible
     */
    public static double calculatePitch(double horizontalDist, double heightDiff,
                                         double velocity, double gravity) {
        double vSq = velocity * velocity;
        double vSqSq = vSq * vSq;
        double discriminant = vSqSq - gravity * (gravity * horizontalDist * horizontalDist
                + 2 * heightDiff * vSq);

        if (discriminant < 0) return Double.NaN; // out of range

        // Use the lower arc (more direct shot)
        double pitch = Math.atan2(vSq - Math.sqrt(discriminant),
                gravity * horizontalDist);
        return -pitch; // Minecraft pitch is inverted
    }

    /**
     * Get the gravity constant for a given weapon item ID.
     */
    public static double gravityForWeapon(String itemId) {
        if (itemId == null) return ARROW_GRAVITY;
        return switch (itemId) {
            case "minecraft:bow" -> ARROW_GRAVITY;
            case "minecraft:crossbow" -> ARROW_GRAVITY;
            case "minecraft:trident" -> TRIDENT_GRAVITY;
            default -> THROWN_GRAVITY; // snowballs, eggs, etc.
        };
    }

    /**
     * Get the projectile velocity for a given weapon item ID and charge ticks.
     */
    public static double velocityForWeapon(String itemId, int chargeTicks) {
        if (itemId == null) return ARROW_VELOCITY;
        return switch (itemId) {
            case "minecraft:bow" -> {
                // BowItem uses ((t/20)^2 + 2*(t/20))/3 as power, capped
                // at one, then multiplies it by the 3.0 projectile speed.
                double fraction = Math.min(1.0, Math.max(0.0, chargeTicks / 20.0));
                double power = Math.min(1.0,
                        (fraction * fraction + 2.0 * fraction) / 3.0);
                yield power * ARROW_VELOCITY;
            }
            case "minecraft:crossbow" -> ARROW_VELOCITY * 1.1; // slightly faster
            case "minecraft:trident" -> TRIDENT_VELOCITY;
            default -> THROWN_VELOCITY;
        };
    }

    /**
     * Determine the effective range of a weapon (maximum horizontal distance
     * for a reasonable shot).
     */
    public static double effectiveRange(String itemId) {
        if (itemId == null) return 15.0;
        return switch (itemId) {
            case "minecraft:bow" -> 30.0;
            case "minecraft:crossbow" -> 25.0;
            case "minecraft:trident" -> 20.0;
            default -> 15.0; // thrown projectiles
        };
    }

    /** Charge-aware range prevents weak bow shots being attempted from 30 blocks. */
    public static double effectiveRange(String itemId, int chargeTicks) {
        double base = effectiveRange(itemId);
        if (!"minecraft:bow".equals(itemId)) return base;
        double speed = velocityForWeapon(itemId, chargeTicks);
        return Math.max(5.0, base * speed / ARROW_VELOCITY);
    }
}
