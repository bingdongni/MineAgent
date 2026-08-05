package com.mineagent.engine.cognition;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.theory.TheoryOfMind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.PickaxeItem;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-rate cognition controller.
 *
 * <p>Vanilla and survival chains keep the 20 Hz body loop. This controller
 * refreshes the tactical frame at 5 Hz, publishes team state, and emits a
 * deliberation event only when executor evidence invalidates the current plan.
 * That separation improves reaction time while avoiding one paid LLM request
 * per body observation.
 */
public final class RealtimeCognition {
    private static final long NORMAL_INTERVAL_TICKS = 4L;
    private static final long DELIBERATION_COOLDOWN_TICKS = 80L;
    private static final int MAX_RECENT_EVENTS = 8;

    public record OwnerIntentSignal(TheoryOfMind.PlayerIntent intent,
                                    float confidence, String evidence,
                                    long gameTick) {}

    public record TickResult(TacticalDecision decision,
                             String deliberationEvent,
                             OwnerIntentSignal ownerIntent) {}

    private final AgentPlayer companion;
    private final TacticalPlanner planner = new TacticalPlanner();
    private final AtomicReference<SituationSnapshot> latestFrame = new AtomicReference<>();
    private final AtomicReference<TacticalDecision> latestDecision =
            new AtomicReference<>(TacticalDecision.initial());
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();

    private long lastObservationTick = Long.MIN_VALUE;
    private long lastDeliberationTick = Long.MIN_VALUE;
    private String lastDeliberationSignature = "";
    private float previousHealth = Float.NaN;
    private float previousOwnerHealth = Float.NaN;
    private String previousDimension;
    private Set<UUID> previousThreats = Set.of();

    public RealtimeCognition(AgentPlayer companion) {
        this.companion = companion;
    }

    public TickResult tick(ServerPlayer player, ServerPlayer owner,
                           SituationSnapshot.TaskObservation task,
                           long gameTick) {
        if (!requiresImmediateRefresh(player)
                && lastObservationTick != Long.MIN_VALUE
                && gameTick - lastObservationTick < NORMAL_INTERVAL_TICKS) {
            return new TickResult(latestDecision.get(), null, null);
        }
        lastObservationTick = gameTick;
        SituationSnapshot frame = SituationObserver.capture(
                companion, player, owner, task, gameTick);
        TacticalDecision decision = planner.decide(frame);
        latestFrame.set(frame);
        latestDecision.set(decision);
        recordChanges(frame);
        TeamBlackboard.publish(companion.ownerUuid(), companion.companionId(),
                companion.companionName(), frame, decision);

        String deliberation = deliberationEvent(frame, decision);
        OwnerIntentSignal ownerIntent = inferOwnerIntent(owner, frame);
        return new TickResult(decision, deliberation, ownerIntent);
    }

    public TacticalDecision currentDecision() {
        return latestDecision.get();
    }

    public SituationSnapshot currentFrame() {
        return latestFrame.get();
    }

    public String summarizeForPrompt() {
        SituationSnapshot frame = latestFrame.get();
        TacticalDecision decision = latestDecision.get();
        if (frame == null) return "Realtime cognition: awaiting first server frame\n";
        StringBuilder out = new StringBuilder("Realtime cognition (server evidence):\n");
        out.append("- tactical=").append(decision.compact())
                .append(" local_control=").append(decision.localControlRequired())
                .append(" deliberation=").append(decision.deliberationRequired()).append('\n');
        out.append("- self=").append(frame.self().compact())
                .append(" health=").append(Math.round(frame.vitals().healthRatio() * 100.0))
                .append("% food=").append(frame.vitals().food())
                .append(" air=").append(frame.vitals().air())
                .append(" hazards=").append(frame.environment().immediateHazards())
                .append(" drop_depth=").append(frame.environment().dropDepth()).append('\n');
        out.append("- nearby: immediate_threats=").append(frame.immediateThreats().size())
                .append(" owner_threats=").append(frame.threatsToOwner())
                .append(" unknown_players=").append(frame.unknownPlayers())
                .append(" projectiles=").append(frame.environment().nearbyProjectiles())
                .append(" drops=").append(frame.environment().nearbyDrops()).append('\n');
        synchronized (recentEvents) {
            if (!recentEvents.isEmpty()) {
                out.append("- recent_changes: ")
                        .append(String.join(" | ", recentEvents)).append('\n');
            }
        }
        out.append("Local tactical/survival control does not need an LLM tool call; deliberate only when the frame says deliberation=true.\n");
        return out.toString();
    }

    public void close() {
        TeamBlackboard.remove(companion.ownerUuid(), companion.companionId());
    }

    private void recordChanges(SituationSnapshot frame) {
        if (Float.isFinite(previousHealth) && frame.vitals().health() < previousHealth - 0.5f) {
            remember("self_health_drop=" + Math.round(previousHealth - frame.vitals().health()));
        }
        previousHealth = frame.vitals().health();
        if (frame.owner().present()) {
            if (Float.isFinite(previousOwnerHealth)
                    && frame.owner().health() < previousOwnerHealth - 0.5f) {
                remember("owner_health_drop="
                        + Math.round(previousOwnerHealth - frame.owner().health()));
            }
            previousOwnerHealth = frame.owner().health();
        }
        if (previousDimension != null && !previousDimension.equals(frame.self().dimension())) {
            remember("dimension_changed=" + previousDimension + "->" + frame.self().dimension());
        }
        previousDimension = frame.self().dimension();

        HashSet<UUID> threats = new HashSet<>();
        frame.immediateThreats().forEach(threat -> threats.add(threat.id()));
        if (!threats.equals(previousThreats)) {
            remember("threat_set=" + threats.size());
            previousThreats = Set.copyOf(threats);
        }
        if (frame.task().blocked()) remember("task_blocked=" + frame.task().blockedReason());
    }

    private void remember(String event) {
        if (event == null || event.isBlank()) return;
        synchronized (recentEvents) {
            if (!recentEvents.isEmpty() && event.equals(recentEvents.peekLast())) return;
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_RECENT_EVENTS) recentEvents.removeFirst();
        }
    }

    private String deliberationEvent(SituationSnapshot frame, TacticalDecision decision) {
        if (!decision.deliberationRequired()) return null;
        String signature = decision.posture() + "|" + frame.task().taskId()
                + "|" + frame.task().blockedReason();
        if (signature.equals(lastDeliberationSignature)
                && lastDeliberationTick != Long.MIN_VALUE
                && frame.gameTick() - lastDeliberationTick < DELIBERATION_COOLDOWN_TICKS) {
            return null;
        }
        lastDeliberationSignature = signature;
        lastDeliberationTick = frame.gameTick();
        return "[COGNITION_DECISION] " + decision.compact()
                + "; executor evidence invalidated the current action. Replan from live evidence instead of retrying unchanged.";
    }

    private static OwnerIntentSignal inferOwnerIntent(ServerPlayer owner,
                                                      SituationSnapshot frame) {
        if (owner == null) return null;
        if (owner.hurtTime > 0 && owner.isSprinting()) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.FLEEING,
                    0.85f, "owner is hurt and sprinting", frame.gameTick());
        }
        if (owner.getLastHurtMob() != null && owner.getLastHurtMob().isAlive()) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.FIGHTING,
                    0.9f, "owner recently attacked a living target", frame.gameTick());
        }
        var held = owner.getMainHandItem().getItem();
        if (held instanceof PickaxeItem) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.MINING,
                    0.55f, "owner holds a pickaxe", frame.gameTick());
        }
        if (held instanceof HoeItem) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.FARMING,
                    0.55f, "owner holds a hoe", frame.gameTick());
        }
        if (held instanceof BlockItem && owner.isCrouching()) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.BUILDING,
                    0.6f, "owner holds a block while positioning carefully", frame.gameTick());
        }
        if (owner.isSprinting()) {
            return new OwnerIntentSignal(TheoryOfMind.PlayerIntent.EXPLORING,
                    0.3f, "owner is travelling quickly", frame.gameTick());
        }
        return null;
    }

    private static boolean requiresImmediateRefresh(ServerPlayer player) {
        return player.isInLava() || player.isOnFire()
                || (player.isInWater() && player.getAirSupply() < 140)
                || player.fallDistance > 6.0f || player.hurtTime > 0;
    }
}
