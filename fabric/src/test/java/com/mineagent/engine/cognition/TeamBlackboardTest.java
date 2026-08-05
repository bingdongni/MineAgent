package com.mineagent.engine.cognition;

import com.mineagent.api.task.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TeamBlackboardTest {
    @AfterEach void clear() { TeamBlackboard.clearAll(); }

    @Test void reportsAreIsolatedByOwner() {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        TeamBlackboard.publish(firstOwner, member, "Alpha", snapshot(10L),
                TacticalDecision.initial());
        assertTrue(TeamBlackboard.summarize(firstOwner, member, 10L).contains("Alpha"));
        assertFalse(TeamBlackboard.summarize(secondOwner, member, 10L).contains("Alpha"));
    }

    @Test void staleReportsExpire() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        TeamBlackboard.publish(owner, member, "OldReport", snapshot(10L),
                TacticalDecision.initial());
        assertFalse(TeamBlackboard.summarize(owner, member, 211L).contains("OldReport"));
    }

    @Test void duplicateObjectivesProduceWarning() {
        UUID owner = UUID.randomUUID();
        TeamBlackboard.commit(owner, UUID.randomUUID(), "collect iron", "gatherer", null, 20L);
        TeamBlackboard.commit(owner, UUID.randomUUID(), "collect iron", "gatherer", null, 20L);
        assertTrue(TeamBlackboard.summarize(owner, UUID.randomUUID(), 20L)
                .contains("coordination_warning"));
    }

    @Test void terminalTaskClearsExecutorCommitment() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        TeamBlackboard.updateTask(owner, member, "t1", "build wall",
                TaskState.RUNNING, "1,2,3", 30L);
        TeamBlackboard.updateTask(owner, member, "t1", "build wall",
                TaskState.SUCCESS, "1,2,3", 31L);
        assertFalse(TeamBlackboard.summarize(owner, member, 31L)
                .contains("objective=build wall"));
    }

    private static SituationSnapshot snapshot(long tick) {
        return new SituationSnapshot(tick,
                new SituationSnapshot.Position("minecraft:overworld", 0, 64, 0),
                null, null, null, null, List.of());
    }
}
