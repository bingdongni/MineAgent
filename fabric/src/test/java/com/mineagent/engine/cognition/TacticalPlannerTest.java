package com.mineagent.engine.cognition;

import com.mineagent.api.task.TaskState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TacticalPlannerTest {
    private final TacticalPlanner planner = new TacticalPlanner();

    @Test void drowningSelectsImmediateSurvival() {
        var frame = frame(vitals(20, 40, true, false), owner(false),
                SituationSnapshot.TaskObservation.idle(), List.of());
        assertEquals(TacticalDecision.Posture.SURVIVE_NOW,
                planner.decide(frame).posture());
    }

    @Test void ownerTargetedSelectsDefense() {
        var hostile = actor(3.0, false, true);
        var frame = frame(vitals(20, 300, false, true), owner(true),
                SituationSnapshot.TaskObservation.idle(), List.of(hostile));
        assertEquals(TacticalDecision.Posture.DEFEND_OWNER,
                planner.decide(frame).posture());
    }

    @Test void blockedExecutorRequestsReplan() {
        var task = new SituationSnapshot.TaskObservation("t1", "mine", TaskState.RUNNING,
                "navigate", "edge is unreachable", 3L);
        var frame = frame(vitals(20, 300, false, true), owner(false), task, List.of());
        assertEquals(TacticalDecision.Posture.REPLAN_BLOCKED_GOAL,
                planner.decide(frame).posture());
    }

    @Test void armedHealthyCompanionEngagesGroundedThreat() {
        var frame = frame(vitals(20, 300, false, true), owner(false),
                SituationSnapshot.TaskObservation.idle(), List.of(actor(3.0, true, false)));
        assertEquals(TacticalDecision.Posture.ENGAGE_PRIORITY_THREAT,
                planner.decide(frame).posture());
    }

    @Test void safeRunningTaskContinuesWithoutLlmPolling() {
        var task = new SituationSnapshot.TaskObservation("t1", "build", TaskState.RUNNING,
                "placing", null, 2L);
        var frame = frame(vitals(20, 300, false, true), owner(false), task, List.of());
        assertEquals(TacticalDecision.Posture.CONTINUE_VERIFIED_PLAN,
                planner.decide(frame).posture());
    }

    private static SituationSnapshot frame(SituationSnapshot.Vitals vitals,
                                           SituationSnapshot.OwnerObservation owner,
                                           SituationSnapshot.TaskObservation task,
                                           List<SituationSnapshot.ActorObservation> actors) {
        return new SituationSnapshot(100L, pos(0, 64, 0), vitals,
                new SituationSnapshot.Environment(0, 0, 0, 0, true),
                owner, task, actors);
    }

    private static SituationSnapshot.Vitals vitals(float health, int air,
                                                    boolean inWater, boolean armed) {
        return new SituationSnapshot.Vitals(health, 20, 20, air, inWater,
                false, false, true, 0, armed, armed ? 2 : 0);
    }

    private static SituationSnapshot.OwnerObservation owner(boolean present) {
        return present
                ? new SituationSnapshot.OwnerObservation(true, pos(2, 64, 0),
                20, 20, false, false, "active", 2)
                : SituationSnapshot.OwnerObservation.absent();
    }

    private static SituationSnapshot.ActorObservation actor(double distance,
                                                             boolean self,
                                                             boolean owner) {
        return new SituationSnapshot.ActorObservation(UUID.randomUUID(),
                "minecraft:zombie", SituationSnapshot.ActorKind.HOSTILE_MOB,
                pos(distance, 64, 0), distance, 20, 20, true,
                self, owner, true, "attacking");
    }

    private static SituationSnapshot.Position pos(double x, double y, double z) {
        return new SituationSnapshot.Position("minecraft:overworld", x, y, z);
    }
}
