package com.mineagent.engine.cognition;

import com.mineagent.api.task.TaskState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, live coordination memory for companions owned by one player.
 *
 * <p>Routine coordination uses this blackboard instead of making every spoken
 * sentence wake every sibling LLM. Reports expire quickly and are never saved
 * as long-term memory, preventing an old task or position from surviving a
 * disconnect and masquerading as current team state.
 */
public final class TeamBlackboard {
    public record MemberReport(UUID companionId, String name,
                               SituationSnapshot.Position position,
                               double healthRatio,
                               TacticalDecision.Posture posture,
                               int immediateThreats, String taskName,
                               long gameTick) {}

    public record Commitment(UUID companionId, String taskId, String objective,
                             String role, String target, String source,
                             TaskState state, long gameTick) {}

    public record SupportRequest(UUID companionId, String request,
                                 String priority, String target,
                                 long gameTick) {}

    private static final long REPORT_TTL_TICKS = 200L;
    private static final long REQUEST_TTL_TICKS = 1_200L;
    private static final ConcurrentHashMap<UUID, GroupState> GROUPS =
            new ConcurrentHashMap<>();

    private TeamBlackboard() {}

    public static void publish(UUID ownerId, UUID companionId, String name,
                               SituationSnapshot snapshot,
                               TacticalDecision decision) {
        if (ownerId == null || companionId == null || snapshot == null) return;
        GroupState group = GROUPS.computeIfAbsent(ownerId, ignored -> new GroupState());
        synchronized (group) {
            group.reports.put(companionId, new MemberReport(companionId,
                    normalize(name, "companion"), snapshot.self(),
                    snapshot.vitals().healthRatio(),
                    decision == null ? TacticalDecision.Posture.OBSERVE_BEFORE_COMMIT
                            : decision.posture(),
                    snapshot.immediateThreats().size(), snapshot.task().taskName(),
                    snapshot.gameTick()));
            expire(group, snapshot.gameTick());
        }
    }

    public static void updateTask(UUID ownerId, UUID companionId,
                                  String taskId, String objective,
                                  TaskState state, String target,
                                  long gameTick) {
        if (ownerId == null || companionId == null) return;
        GroupState group = GROUPS.computeIfAbsent(ownerId, ignored -> new GroupState());
        synchronized (group) {
            if (state == null || (state != TaskState.RUNNING && state != TaskState.PAUSED)) {
                Commitment existing = group.commitments.get(companionId);
                if (existing != null && (taskId == null || taskId.equals(existing.taskId()))) {
                    group.commitments.remove(companionId);
                }
                return;
            }
            group.commitments.put(companionId, new Commitment(companionId,
                    normalize(taskId, "body-task"), normalize(objective, "body task"),
                    "executor", blankToNull(target), "verified_task",
                    state, Math.max(0L, gameTick)));
        }
    }

    public static void commit(UUID ownerId, UUID companionId,
                              String objective, String role, String target,
                              long gameTick) {
        if (ownerId == null || companionId == null) return;
        GroupState group = GROUPS.computeIfAbsent(ownerId, ignored -> new GroupState());
        synchronized (group) {
            group.commitments.put(companionId, new Commitment(companionId,
                    "manual:" + companionId, normalize(objective, "team objective"),
                    normalize(role, "flex"), blankToNull(target), "team_plan",
                    TaskState.RUNNING, Math.max(0L, gameTick)));
        }
    }

    public static void requestSupport(UUID ownerId, UUID companionId,
                                      String request, String priority,
                                      String target, long gameTick) {
        if (ownerId == null || companionId == null) return;
        GroupState group = GROUPS.computeIfAbsent(ownerId, ignored -> new GroupState());
        synchronized (group) {
            group.requests.put(companionId, new SupportRequest(companionId,
                    normalize(request, "support requested"),
                    normalize(priority, "high"), blankToNull(target),
                    Math.max(0L, gameTick)));
        }
    }

    public static void clearCommitment(UUID ownerId, UUID companionId) {
        GroupState group = GROUPS.get(ownerId);
        if (group == null) return;
        synchronized (group) {
            group.commitments.remove(companionId);
            group.requests.remove(companionId);
        }
    }

    public static String summarize(UUID ownerId, UUID selfId, long gameTick) {
        GroupState group = GROUPS.get(ownerId);
        if (group == null) return "Team blackboard: no live teammate reports\n";
        synchronized (group) {
            expire(group, gameTick);
            StringBuilder out = new StringBuilder("Team blackboard (live evidence):\n");
            group.reports.values().stream()
                    .sorted(Comparator.comparing(MemberReport::name))
                    .forEach(report -> out.append("- ")
                            .append(report.companionId().equals(selfId) ? "self " : "ally ")
                            .append(report.name()).append(" @ ")
                            .append(report.position().compact()).append(" hp=")
                            .append(Math.round(report.healthRatio() * 100.0)).append("% posture=")
                            .append(report.posture().name().toLowerCase(Locale.ROOT))
                            .append(" threats=").append(report.immediateThreats())
                            .append(" task=").append(report.taskName()).append('\n'));
            for (Commitment commitment : group.commitments.values()) {
                out.append("- commitment ").append(shortId(commitment.companionId()))
                        .append(" role=").append(commitment.role())
                        .append(" objective=").append(commitment.objective());
                if (commitment.target() != null) out.append(" target=").append(commitment.target());
                out.append(" source=").append(commitment.source()).append('\n');
            }
            for (SupportRequest request : group.requests.values()) {
                out.append("- SUPPORT_REQUEST from=").append(shortId(request.companionId()))
                        .append(" priority=").append(request.priority())
                        .append(" request=").append(request.request());
                if (request.target() != null) out.append(" target=").append(request.target());
                out.append('\n');
            }
            duplicateWarnings(group.commitments.values()).forEach(warning ->
                    out.append("- coordination_warning: ").append(warning).append('\n'));
            out.append("Coordinate through commitments and support requests; do not duplicate an ally's objective without a reason.\n");
            return out.toString();
        }
    }

    public static void remove(UUID ownerId, UUID companionId) {
        GroupState group = GROUPS.get(ownerId);
        if (group == null) return;
        synchronized (group) {
            group.reports.remove(companionId);
            group.commitments.remove(companionId);
            group.requests.remove(companionId);
            if (group.reports.isEmpty() && group.commitments.isEmpty()
                    && group.requests.isEmpty()) GROUPS.remove(ownerId, group);
        }
    }

    public static void clearAll() {
        GROUPS.clear();
    }

    static List<String> duplicateWarnings(Iterable<Commitment> commitments) {
        LinkedHashMap<String, List<Commitment>> grouped = new LinkedHashMap<>();
        for (Commitment commitment : commitments) {
            if (commitment == null) continue;
            grouped.computeIfAbsent(canonical(commitment.objective()), ignored -> new ArrayList<>())
                    .add(commitment);
        }
        ArrayList<String> warnings = new ArrayList<>();
        for (Map.Entry<String, List<Commitment>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            warnings.add(entry.getValue().size() + " companions claim objective '"
                    + entry.getValue().get(0).objective() + "'");
        }
        return List.copyOf(warnings);
    }

    private static void expire(GroupState group, long gameTick) {
        group.reports.entrySet().removeIf(entry ->
                age(gameTick, entry.getValue().gameTick()) > REPORT_TTL_TICKS);
        group.requests.entrySet().removeIf(entry ->
                age(gameTick, entry.getValue().gameTick()) > REQUEST_TTL_TICKS);
        // Executor commitments are removed by task completion. Manual plans
        // are kept while the companion remains active and must be cleared or
        // replaced explicitly, matching a teammate's real commitment.
    }

    private static long age(long now, long observed) {
        return now >= observed ? now - observed : 0L;
    }

    private static String canonical(String value) {
        return normalize(value, "objective").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\p{IsHan}]+", " ").trim();
    }

    private static String shortId(UUID id) {
        return id == null ? "unknown" : id.toString().substring(0, 8);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class GroupState {
        private final Map<UUID, MemberReport> reports = new LinkedHashMap<>();
        private final Map<UUID, Commitment> commitments = new LinkedHashMap<>();
        private final Map<UUID, SupportRequest> requests = new LinkedHashMap<>();
    }
}
