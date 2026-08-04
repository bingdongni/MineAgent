package com.mineagent.engine.memory;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Structured episodic and failure memory used by planning and rule revision.
 * Free-form reflections remain useful for conversation, but control decisions
 * need typed outcomes that can be filtered and contradicted reliably.
 */
public final class ExperienceStore {
    public enum FailureKind {
        NONE,
        NAVIGATION,
        PRECONDITION,
        INVALID_ACTION,
        WORLD_RULE_MISMATCH,
        TARGET_CHANGED,
        TIMEOUT,
        CANCELLED,
        EXECUTION_ERROR,
        UNKNOWN
    }

    public record Experience(String taskId, String action, String intent,
                             TaskState outcome, FailureKind failureKind,
                             String stage, long completedUnits, long totalUnits,
                             String evidence, long gameTick) {
        public Experience {
            taskId = normalize(taskId, "unknown-task");
            action = normalize(action, "unknown-action");
            intent = normalize(intent, "unknown-intent");
            outcome = outcome == null ? TaskState.FAILED : outcome;
            failureKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
            stage = normalize(stage, "unknown-stage");
            completedUnits = Math.max(0L, completedUnits);
            totalUnits = totalUnits <= 0L ? -1L : totalUnits;
            evidence = normalize(evidence, "No evidence detail");
            gameTick = Math.max(0L, gameTick);
        }
    }

    private static final int MAX_EXPERIENCES = 512;
    private final Deque<Experience> experiences = new ArrayDeque<>();

    public synchronized Experience record(String taskId, String action, String intent,
                                          TaskState outcome, TaskSnapshot snapshot,
                                          String message, long gameTick) {
        FailureKind kind = diagnose(outcome, snapshot, message);
        String evidence = snapshot != null && snapshot.evidence() != null
                ? snapshot.evidence() : message;
        Experience experience = new Experience(taskId, action, intent, outcome, kind,
                snapshot == null ? "unknown" : snapshot.stage(),
                snapshot == null ? 0L : snapshot.completedUnits(),
                snapshot == null ? -1L : snapshot.totalUnits(),
                evidence, gameTick);
        experiences.addLast(experience);
        while (experiences.size() > MAX_EXPERIENCES) experiences.removeFirst();
        return experience;
    }

    public synchronized String summarizeForPrompt(String currentIntent) {
        if (experiences.isEmpty()) return "";
        String needle = normalize(currentIntent, "").toLowerCase(Locale.ROOT);
        List<Experience> relevant = new ArrayList<>();
        var iterator = experiences.descendingIterator();
        while (iterator.hasNext() && relevant.size() < 6) {
            Experience experience = iterator.next();
            if (needle.isBlank()
                    || experience.intent().toLowerCase(Locale.ROOT).contains(needle)
                    || needle.contains(experience.intent().toLowerCase(Locale.ROOT))
                    || experience.outcome() != TaskState.SUCCESS) {
                relevant.add(experience);
            }
        }
        if (relevant.isEmpty()) return "";
        StringBuilder out = new StringBuilder("Relevant verified experiences:\n");
        for (Experience experience : relevant) {
            out.append("- ").append(experience.action()).append(" -> ")
                    .append(experience.outcome());
            if (experience.failureKind() != FailureKind.NONE) {
                out.append(" (").append(experience.failureKind()).append(')');
            }
            out.append(": ").append(experience.evidence()).append('\n');
        }
        return out.toString();
    }

    public synchronized List<Experience> exportAll() {
        return List.copyOf(experiences);
    }

    public synchronized void importAll(List<Experience> imported) {
        experiences.clear();
        if (imported == null) return;
        for (Experience experience : imported) {
            if (experience != null) experiences.addLast(experience);
        }
        while (experiences.size() > MAX_EXPERIENCES) experiences.removeFirst();
    }

    public static FailureKind diagnose(TaskState state, TaskSnapshot snapshot,
                                       String message) {
        if (state == TaskState.SUCCESS) return FailureKind.NONE;
        if (state == TaskState.CANCELLED) return FailureKind.CANCELLED;
        String text = ((snapshot == null ? "" : String.valueOf(snapshot.blockedReason()))
                + " " + String.valueOf(message)).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")
                || text.contains("deadline")) return FailureKind.TIMEOUT;
        if (text.contains("navigation") || text.contains("path")
                || text.contains("unreachable") || text.contains("no progress")) {
            return FailureKind.NAVIGATION;
        }
        if (text.contains("not in inventory") || text.contains("required item")
                || text.contains("no safe tool") || text.contains("precondition")) {
            return FailureKind.PRECONDITION;
        }
        if (text.contains("target") && (text.contains("gone")
                || text.contains("changed") || text.contains("not found"))) {
            return FailureKind.TARGET_CHANGED;
        }
        if (text.contains("refused") || text.contains("invalid action")
                || text.contains("cannot interact")) return FailureKind.INVALID_ACTION;
        if (text.contains("recipe") || text.contains("unexpected drop")
                || text.contains("rule mismatch")) return FailureKind.WORLD_RULE_MISMATCH;
        if (text.contains("exception") || text.contains("error")) {
            return FailureKind.EXECUTION_ERROR;
        }
        return FailureKind.UNKNOWN;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
