package com.mineagent.engine.survival;

/**
 * Layered survival priority system — borrowed from numen project.
 *
 * <p><b>Problem solved</b>: The old {@link PriorityAuction} used a single
 * threshold ({@code LLM_PREEMPT_THRESHOLD = 7.0}) to decide whether an
 * instinct chain can preempt a running LLM task. This was coarse:
 * <ul>
 *   <li>MobDefense (5.0-5.5) could never preempt, even when the companion
 *       was being attacked mid-task</li>
 *   <li>FoodChain (3.0-4.0) had no way to escalate when starving</li>
 *   <li>No mechanism to distinguish "urgent" from "routine" within a chain</li>
 * </ul>
 *
 * <p><b>Solution</b>: A layered priority formula, all pure functions of
 * raw state values (no Minecraft imports). Each chain computes its own
 * priority based on urgency, and the auction picks the highest.
 *
 * <p><b>Priority layers</b> (higher = more urgent):
 * <pre>
 *   MLG           10.0   (falling damage, life-or-death)
 *   breath         6.0   (drowning, hard timer)
 *   mob-defense    5.0   (under attack, urgent)
 *       ↳ +0.5 if HP < 30%    (escalation)
 *       ↳ +1.0 if HP < 20%    (critical)
 *   food-regen     4.0   (can eat to heal, HP not full)
 *   food-hunger    3.0   (getting hungry, no immediate danger)
 *       ↳ +0.5 if food < 6    (urgent)
 *   unstuck        2.0   (stuck, annoying but not dangerous)
 *   follow         1.0   (follow owner, lowest priority instinct)
 *   llm            0.0   (LLM-driven tasks, lowest)
 * </pre>
 *
 * <p><b>Preemption rule</b>: Any chain with priority > 5.0 (mob-defense
 * with escalation or higher) can preempt a running LLM task. This is
 * stricter than the old 7.0 — only genuine emergencies preempt.
 *
 * <p><b>Rationale</b> (from numen):
 * <ul>
 *   <li>Falling is most lethal → MLG highest</li>
 *   <li>Drowning is a hard timer → breath above combat</li>
 *   <li>Hunger is slow damage → below combat</li>
 *   <li>Stuck is annoying but not lethal → lowest survival priority</li>
 * </ul>
 *
 * <p><b>Pure function design</b>: All methods are static, take primitives,
 * return primitives. No Minecraft imports. Trivially unit-testable.
 */
public final class SurvivalDecisions {

    /** Priority: LLM-driven tasks (lowest). */
    public static final float LLM = 0.0f;
    /** Priority: follow owner in FOLLOW mode. */
    public static final float FOLLOW = 1.0f;
    /** Priority: stuck detection (annoying but not dangerous). */
    public static final float UNSTUCK = 2.0f;
    /** Priority: getting hungry (no immediate danger). */
    public static final float FOOD_HUNGER = 3.0f;
    /** Priority: can eat to heal (HP not full, food available). */
    public static final float FOOD_REGEN = 4.0f;
    /** Priority: under attack (urgent). */
    public static final float MOB_DEFENSE = 5.0f;
    /** Priority: drowning (hard timer). */
    public static final float BREATH = 6.0f;
    /** Priority: falling damage (life-or-death, highest). */
    public static final float MLG = 10.0f;

    /**
     * Preemption threshold: any chain with priority above this can preempt
     * a running LLM task. Set to 5.0 — only mob-defense (with escalation),
     * breath, and MLG can preempt.
     *
     * <p>This is stricter than the old 7.0: regular mob-defense (5.0) cannot
     * preempt, only escalated mob-defense (5.5+ for HP < 30%, 6.0+ for HP <
     * 20%) can. This prevents the chain from interrupting LLM tasks for
     * minor threats.
     */
    public static final float LLM_PREEMPT_THRESHOLD = 5.0f;

    private SurvivalDecisions() {}

    /**
     * Compute MLG chain priority.
     *
     * @param verticalSpeed current vertical speed (blocks/tick, negative = falling)
     * @param blocksAboveGround blocks between feet and ground (negative if underground)
     * @return MLG priority if water bucket needed, else -INF
     */
    public static float mlgPriority(double verticalSpeed, double blocksAboveGround) {
        // Falling fast (more than 0.5 block/tick downward) and high enough to take damage
        if (verticalSpeed < -0.5 && blocksAboveGround > 3.0) {
            return MLG;
        }
        return Float.NEGATIVE_INFINITY;
    }

    /**
     * Compute breath chain priority.
     *
     * @param airSupply current air supply (0-300 typically)
     * @return BREATH if suffocating, else -INF
     */
    public static float breathPriority(int airSupply) {
        // Trigger when air is below 60 (3 seconds of breath left)
        if (airSupply < 60) {
            return BREATH;
        }
        return Float.NEGATIVE_INFINITY;
    }

    /**
     * Compute mob-defense chain priority with HP-based escalation.
     *
     * @param hp current health
     * @param maxHp max health
     * @param hostileCount number of hostile mobs within 8 blocks
     * @return priority (MOB_DEFENSE or higher with escalation), or -INF
     */
    public static float mobDefensePriority(float hp, float maxHp, int hostileCount) {
        if (hostileCount <= 0) return Float.NEGATIVE_INFINITY;

        // Modded attributes or a body during partial teardown can expose a
        // non-finite/zero max health. Dividing by it produces NaN/Infinity,
        // which then defeats every comparison and silently disables urgency
        // escalation. Treat that invalid state as critical instead.
        float hpRatio = Float.isFinite(maxHp) && maxHp > 0.0f
                ? hp / maxHp : 0.0f;
        if (!Float.isFinite(hpRatio)) hpRatio = 0.0f;
        float priority = MOB_DEFENSE;

        // Escalation: HP-based
        if (hpRatio < 0.30f) priority += 0.5f;   // HP < 30% → 5.5
        if (hpRatio < 0.20f) priority += 1.0f;   // HP < 20% → 6.5 (can preempt LLM)
        if (hpRatio < 0.10f) priority += 2.0f;   // HP < 10% → 8.5 (urgent)

        // Multiple hostiles escalation
        if (hostileCount >= 3) priority += 0.5f;
        if (hostileCount >= 5) priority += 0.5f;

        return priority;
    }

    /**
     * Compute food-regen priority (eating to heal when HP is not full).
     *
     * @param hp current health
     * @param maxHp max health
     * @param food food level (0-20)
     * @return FOOD_REGEN if should eat to heal, else -INF
     */
    public static float foodRegenPriority(float hp, float maxHp, int food) {
        // Can eat to heal: HP not full, food >= 18 (enough to regen)
        if (hp < maxHp && food >= 18) {
            return FOOD_REGEN;
        }
        return Float.NEGATIVE_INFINITY;
    }

    /**
     * Compute food-hunger priority (eating to prevent starvation).
     *
     * @param food food level (0-20)
     * @return FOOD_HUNGER or higher with urgency escalation, or -INF
     */
    public static float foodHungerPriority(int food) {
        if (food >= 15) return Float.NEGATIVE_INFINITY;

        float priority = FOOD_HUNGER;
        if (food < 10) priority += 0.5f;   // 3.5
        if (food < 6) priority += 1.0f;    // 4.0+ (urgent, can preempt? No, below threshold)
        if (food < 3) priority += 2.0f;    // 5.0+ (starving, can preempt LLM)
        return priority;
    }

    /**
     * Compute unstuck chain priority.
     *
     * <p>Always returns UNSTUCK when stuck — no escalation. Being stuck is
     * never urgent enough to preempt an LLM task.
     *
     * @param isStuck whether the companion is currently stuck
     * @return UNSTUCK if stuck, else -INF
     */
    public static float unstuckPriority(boolean isStuck) {
        return isStuck ? UNSTUCK : Float.NEGATIVE_INFINITY;
    }

    /**
     * Compute follow chain priority.
     *
     * @param distance distance to owner (blocks)
     * @param followModeEnabled whether FOLLOW mode is enabled
     * @return FOLLOW if should follow, else -INF
     */
    public static float followPriority(double distance, boolean followModeEnabled) {
        if (!followModeEnabled) return Float.NEGATIVE_INFINITY;
        if (distance < 8.0) return Float.NEGATIVE_INFINITY;  // close enough
        // Escalate with distance
        if (distance > 48.0) return FOLLOW + 1.5f;   // 2.5 — very far, urgent
        if (distance > 32.0) return FOLLOW + 1.0f;   // 2.0
        if (distance > 16.0) return FOLLOW + 0.5f;  // 1.5
        return FOLLOW;  // 1.0
    }

    /**
     * Should this priority preempt a running LLM task?
     *
     * @param priority the chain's computed priority
     * @return true if priority > LLM_PREEMPT_THRESHOLD
     */
    public static boolean shouldPreemptLLM(float priority) {
        return priority > LLM_PREEMPT_THRESHOLD;
    }
}
