package com.mineagent.engine.planning;

import com.mineagent.engine.act.Placement;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.task.BlockDigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks only blocks that navigation verifiably placed and makes bounded,
 * context-sensitive cleanup decisions while the body is otherwise idle.
 *
 * <p>The ledger does not encode a universal "recover blocks" score. It first
 * rejects unsafe actions, then describes the live consequences of recover,
 * defer and leave to {@link ContextualDecisionEngine}. Context-derived
 * weights therefore differ when the player is threatened, resources are
 * scarce, a support preserves an escape route, or the owner requested a
 * permanent support.
 */
public final class TemporaryBlockLedger {
    public enum Purpose { BRIDGE, PILLAR }

    private record Entry(ResourceKey<Level> dimension, BlockPos position,
                         Block block, Purpose purpose,
                         IntentContract.CleanupMode cleanupMode,
                         long placedTick, int attempts) {
        Entry attempted() {
            return new Entry(dimension, position, block, purpose, cleanupMode,
                    placedTick, attempts + 1);
        }
    }

    private static final int DECISION_INTERVAL_TICKS = 20;
    private static final int MAX_RECOVERY_ATTEMPTS = 2;
    private static final double RECOVERY_REACH_SQR = 18.0;

    private final ServerPlayer player;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ContextualDecisionEngine decisions = new ContextualDecisionEngine();
    private BlockPos activeBreakTarget;
    private ResourceKey<Level> activeBreakDimension;
    private int activeBreakTicks;
    private int activeBreakTimeout;
    private long lastDecisionTick = Long.MIN_VALUE;

    public TemporaryBlockLedger(ServerPlayer player) {
        this.player = player;
    }

    public synchronized void recordPlaced(BlockPos position, BlockState state,
                                          Purpose purpose,
                                          IntentContract.CleanupMode mode) {
        if (position == null || state == null || state.isAir()) return;
        ResourceKey<Level> dimension = player.level().dimension();
        entries.put(key(dimension, position), new Entry(dimension,
                position.immutable(), state.getBlock(),
                purpose == null ? Purpose.BRIDGE : purpose,
                mode == null ? IntentContract.CleanupMode.CONTEXTUAL : mode,
                player.level().getGameTime(), 0));
    }

    /**
     * Perform at most one low-level cleanup action. Returning true means the
     * caller must not run idle wandering or pickup steering on the same tick.
     */
    public synchronized boolean tickIdle() {
        pruneStaleEntries();
        if (activeBreakTarget != null) return tickActiveBreak();
        if (entries.isEmpty()) return false;

        long now = player.level().getGameTime();
        if (now - lastDecisionTick < DECISION_INTERVAL_TICKS) return false;
        lastDecisionTick = now;

        Entry entry = nearestReachableEntry();
        if (entry == null) return false;
        ContextualDecisionEngine.Decision decision = decide(entry, now);
        var selected = decision.selected();
        if (selected == null || "defer".equals(selected.id())) return false;
        if ("leave".equals(selected.id())) {
            entries.remove(key(entry));
            return false;
        }

        activeBreakTimeout = BlockDigger.expectedBreakTicks(player, entry.position());
        if (!BlockDigger.startBreaking(player, entry.position())) {
            rememberFailedAttempt(entry);
            return false;
        }
        activeBreakTarget = entry.position();
        activeBreakDimension = entry.dimension();
        activeBreakTicks = 0;
        return true;
    }

    private boolean tickActiveBreak() {
        Entry entry = entries.get(key(activeBreakDimension, activeBreakTarget));
        if (!java.util.Objects.equals(activeBreakDimension, player.level().dimension())) {
            clearActiveBreak(true);
            return false;
        }
        BlockState state = player.level().getBlockState(activeBreakTarget);
        if (state.isAir() || entry == null || !state.is(entry.block())) {
            if (entry != null) entries.remove(key(entry));
            clearActiveBreak(false);
            return true;
        }
        if (isUnsafeToRecover(entry)) {
            clearActiveBreak(true);
            return false;
        }
        if (++activeBreakTicks > activeBreakTimeout) {
            clearActiveBreak(true);
            rememberFailedAttempt(entry);
            return false;
        }
        return true;
    }

    private ContextualDecisionEngine.Decision decide(Entry entry, long now) {
        double danger = dangerLevel();
        double scarcity = 1.0 - Math.min(1.0,
                Placement.supportBlockCount(player) / 32.0);
        double cleanupPreference = switch (entry.cleanupMode()) {
            case REQUIRED -> 1.0;
            case LEAVE -> 0.0;
            case CONTEXTUAL -> 0.55;
        };
        boolean escapeSupport = preservesCurrentEscapeOption(entry.position());
        double age = Math.min(1.0, Math.max(0L, now - entry.placedTick()) / 1200.0);

        // This vocabulary belongs to cleanup only. Other decisions can supply
        // entirely different factors; none are globally mandatory.
        Map<String, Double> salience = new LinkedHashMap<>();
        salience.put("physical_safety", 1.0 + 5.0 * danger);
        salience.put("block_scarcity", 0.4 + 3.0 * scarcity);
        salience.put("owner_cleanup_intent", 0.2 + 3.0 * cleanupPreference);
        salience.put("escape_route_option", escapeSupport ? 5.0 : 0.4);
        salience.put("future_support_reuse", 0.3 + 2.5 * (1.0 - age));
        salience.put("time_efficiency", 0.5);
        salience.put("reversibility", escapeSupport ? 3.0 : 0.7);
        var context = new ContextualDecisionEngine.DecisionContext(
                salience, 0.15 + danger * 0.85);

        List<String> violations = new ArrayList<>();
        if (isUnsafeToRecover(entry)) violations.add("unsafe live world state");
        if (entry.cleanupMode() == IntentContract.CleanupMode.LEAVE) {
            violations.add("task contract marks support as permanent");
        }
        Map<String, Double> recover = effects(
                0.8 - danger, 0.15, 0.9, -0.35,
                escapeSupport ? -1.0 : 0.2, 0.9, 0.5, 0.0);
        Map<String, Double> defer = effects(
                0.8, 0.0, -0.1, 0.8,
                escapeSupport ? 0.9 : 0.2, -0.3, 0.9, 0.1);
        Map<String, Double> leave = effects(
                0.7, 0.0, -0.7, 0.95,
                0.7, -0.9, 0.4, 0.0);

        return decisions.decide(context, List.of(
                new ContextualDecisionEngine.Candidate("recover", recover,
                        violations, danger, "recover an owned temporary support"),
                new ContextualDecisionEngine.Candidate("defer", defer, List.of(),
                        0.05, "retain the option and reconsider from a later state"),
                new ContextualDecisionEngine.Candidate("leave", leave,
                        entry.cleanupMode() == IntentContract.CleanupMode.REQUIRED
                                ? List.of("cleanup is required by the task contract") : List.of(),
                        0.1, "stop treating this support as temporary")));
    }

    private static Map<String, Double> effects(
            double safety, double progress, double resources, double time,
            double optionValue, double cleanliness, double reversibility,
            double information) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("physical_safety", safety);
        result.put("goal_progress", progress);
        result.put("block_scarcity", resources);
        result.put("time_efficiency", time);
        result.put("escape_route_option", optionValue);
        result.put("future_support_reuse", optionValue);
        result.put("owner_cleanup_intent", cleanliness);
        result.put("reversibility", reversibility);
        result.put("information_gain", information);
        return result;
    }

    private Entry nearestReachableEntry() {
        return entries.values().stream()
                .filter(entry -> entry.dimension().equals(player.level().dimension()))
                .filter(entry -> player.distanceToSqr(
                        entry.position().getX() + 0.5,
                        entry.position().getY() + 0.5,
                        entry.position().getZ() + 0.5) <= RECOVERY_REACH_SQR)
                .min(java.util.Comparator.comparingDouble(entry -> player.distanceToSqr(
                        entry.position().getX() + 0.5,
                        entry.position().getY() + 0.5,
                        entry.position().getZ() + 0.5)))
                .orElse(null);
    }

    private boolean isUnsafeToRecover(Entry entry) {
        if (!player.isAlive() || player.isFallFlying() || !player.onGround()) return true;
        if (dangerLevel() > 0.2) return true;
        BlockPos feet = player.blockPosition();
        if (entry.position().equals(feet.below())) return true;
        return preservesCurrentEscapeOption(entry.position());
    }

    private double dangerLevel() {
        double healthRisk = 1.0 - player.getHealth() / Math.max(1.0, player.getMaxHealth());
        AABB box = player.getBoundingBox().inflate(8.0, 4.0, 8.0);
        int hostiles = player.serverLevel().getEntitiesOfClass(Monster.class, box,
                monster -> monster.isAlive() && monster.hasLineOfSight(player)).size();
        return Math.min(1.0, Math.max(healthRisk, hostiles / 3.0));
    }

    /**
     * Conservatively retain a nearby support when removing it would leave no
     * other standable neighboring cell. This is a physical hard constraint,
     * not a preference score.
     */
    private boolean preservesCurrentEscapeOption(BlockPos candidate) {
        BlockPos feet = player.blockPosition();
        if (feet.distManhattan(candidate) > 2) return false;
        int alternatives = 0;
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos nextFeet = feet.relative(direction);
            BlockPos support = nextFeet.below();
            if (support.equals(candidate)) continue;
            if (BlockHelper.canStandOn(player.level().getBlockState(support))
                    && BlockHelper.isPassable(player.level().getBlockState(nextFeet))
                    && BlockHelper.isPassable(player.level().getBlockState(nextFeet.above()))) {
                alternatives++;
            }
        }
        return alternatives == 0;
    }

    private void pruneStaleEntries() {
        entries.entrySet().removeIf(mapEntry -> {
            Entry entry = mapEntry.getValue();
            if (!entry.dimension().equals(player.level().dimension())) return false;
            BlockState state = player.level().getBlockState(entry.position());
            return state.isAir() || !state.is(entry.block());
        });
    }

    private void rememberFailedAttempt(Entry entry) {
        if (entry == null) return;
        if (entry.attempts() + 1 >= MAX_RECOVERY_ATTEMPTS) {
            entries.remove(key(entry));
        } else {
            entries.put(key(entry), entry.attempted());
        }
    }

    private void clearActiveBreak(boolean abort) {
        if (abort && activeBreakTarget != null) {
            BlockDigger.abortBreaking(player, activeBreakTarget);
        }
        activeBreakTarget = null;
        activeBreakDimension = null;
        activeBreakTicks = 0;
        activeBreakTimeout = 0;
    }

    public synchronized void close() {
        clearActiveBreak(true);
        entries.clear();
    }

    /** Release progressive mining immediately when another controller wins. */
    public synchronized void interrupt() {
        clearActiveBreak(true);
    }

    private static String key(Entry entry) {
        return key(entry.dimension(), entry.position());
    }

    private static String key(ResourceKey<Level> dimension, BlockPos position) {
        if (dimension == null || position == null) return "invalid";
        return dimension.location() + "|" + position.asLong();
    }
}
