package com.mineagent.engine.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Closed-loop executor for learned, parameterized tool sequences.
 *
 * <p>Only one step is dispatched at a time. Asynchronous steps wait for the
 * scheduler's terminal result, and declared effects must be observed in the
 * semantic world model before the next step can start. A failed or unverifiable
 * step stops with a structured replan request instead of blindly replaying the
 * remaining actions against a changed world.
 */
public final class SkillRuntime {
    public enum Status {
        IDLE, RUNNING, WAITING_TASK, VERIFYING,
        SUCCEEDED, NEEDS_REPLAN, FAILED, CANCELLED
    }

    public enum FailurePolicy { REPLAN, RETRY_ONCE, ABORT }

    public record Condition(String subject, String predicate, String value,
                            double minimumConfidence) {
        public Condition {
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "*");
            minimumConfidence = unit(minimumConfidence);
        }
    }

    public record Step(String tool, JsonObject arguments,
                       List<Condition> preconditions,
                       List<Condition> expectedEffects,
                       FailurePolicy failurePolicy,
                       int maxAttempts, long timeoutTicks) {
        public Step {
            tool = normalizeTool(tool);
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
            expectedEffects = expectedEffects == null ? List.of() : List.copyOf(expectedEffects);
            failurePolicy = failurePolicy == null ? FailurePolicy.REPLAN : failurePolicy;
            maxAttempts = Math.max(1, Math.min(3, maxAttempts));
            timeoutTicks = Math.max(20L, Math.min(12_000L, timeoutTicks));
        }
    }

    public record DispatchResult(boolean accepted, boolean asynchronous,
                                 boolean success, String evidence) {
        public DispatchResult {
            evidence = normalize(evidence, success ? "accepted" : "dispatch failed");
        }
    }

    public record StartResult(boolean accepted, String runId, String message) {}

    public record Snapshot(String runId, String skillName, Status status,
                           int stepIndex, int stepCount, int attempt,
                           String activeTool, String lastEvidence,
                           long startedTick, long updatedTick) {
        public boolean terminal() {
            return status == Status.SUCCEEDED || status == Status.NEEDS_REPLAN
                    || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    @FunctionalInterface
    public interface ActionDispatcher {
        DispatchResult dispatch(String actionId, String tool, JsonObject arguments);
    }

    @FunctionalInterface
    public interface CompletionSink {
        void completed(Snapshot snapshot);
    }

    private static final int MAX_STEPS = 24;
    private static final Set<String> NEVER_NEST = Set.of(
            "execute_skill", "explore_mechanism", "todowrite",
            "task_stop", "coordinate_team");

    private final SkillLibrary library;
    private final SemanticWorldModel worldModel;
    private final ActionDispatcher dispatcher;
    private final CompletionSink completionSink;

    private String runId;
    private String skillName;
    private List<Step> steps = List.of();
    private Status status = Status.IDLE;
    private int stepIndex;
    private int attempt;
    private String activeActionId;
    private String lastEvidence = "idle";
    private long startedTick;
    private long updatedTick;
    private long stepStartedTick;
    private long verificationDeadlineTick;
    private long worldRevisionAtDispatch;
    private boolean taskPaused;
    private long taskPausedAtTick;

    public SkillRuntime(SkillLibrary library, SemanticWorldModel worldModel,
                        ActionDispatcher dispatcher, CompletionSink completionSink) {
        this.library = Objects.requireNonNull(library, "library");
        this.worldModel = Objects.requireNonNull(worldModel, "worldModel");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.completionSink = Objects.requireNonNull(completionSink, "completionSink");
    }

    public synchronized StartResult start(String requestedSkill,
                                          JsonObject overrides,
                                          long gameTick) {
        if (active()) {
            return new StartResult(false, runId,
                    "Skill runtime is already executing " + skillName);
        }
        SkillLibrary.Skill learned = library.get(requestedSkill).orElse(null);
        if (learned == null) {
            return new StartResult(false, null, "Unknown learned skill: " + requestedSkill);
        }
        List<Step> decoded;
        try {
            decoded = decode(learned.actionSequence(), overrides);
        } catch (IllegalArgumentException malformed) {
            return new StartResult(false, null,
                    "Skill cannot be executed: " + malformed.getMessage());
        }
        if (decoded.isEmpty()) {
            return new StartResult(false, null, "Skill has no executable actions");
        }
        runId = "skill-" + UUID.randomUUID().toString().substring(0, 12);
        skillName = learned.name();
        steps = decoded;
        status = Status.RUNNING;
        stepIndex = 0;
        attempt = 0;
        activeActionId = null;
        lastEvidence = "accepted";
        startedTick = Math.max(0L, gameTick);
        updatedTick = startedTick;
        stepStartedTick = startedTick;
        verificationDeadlineTick = 0L;
        taskPaused = false;
        taskPausedAtTick = 0L;
        worldRevisionAtDispatch = worldModel.revision();
        return new StartResult(true, runId,
                "Accepted " + steps.size() + " verified step(s)");
    }

    /** Advance at most one transition per server tick. */
    public synchronized void tick(long gameTick) {
        updatedTick = Math.max(updatedTick, gameTick);
        if (!active()) return;
        Step step = steps.get(stepIndex);

        if (status == Status.WAITING_TASK && !taskPaused
                && gameTick - stepStartedTick > step.timeoutTicks()) {
            failStep("step timed out after " + step.timeoutTicks() + " ticks", gameTick);
            return;
        }
        if (status == Status.VERIFYING) {
            if (effectsVerified(step, gameTick)) completeStep(gameTick);
            else if (gameTick > verificationDeadlineTick) {
                failStep("declared postcondition was not observed", gameTick);
            }
            return;
        }
        if (status != Status.RUNNING) return;

        List<Condition> missing = missing(step.preconditions(), 0L, gameTick);
        if (!missing.isEmpty()) {
            terminal(Status.NEEDS_REPLAN,
                    "precondition not grounded: " + describe(missing.get(0)), gameTick);
            return;
        }

        attempt++;
        stepStartedTick = gameTick;
        worldRevisionAtDispatch = worldModel.revision();
        activeActionId = runId + ":" + stepIndex + ":" + attempt;
        DispatchResult result;
        try {
            result = dispatcher.dispatch(activeActionId, step.tool(),
                    step.arguments().deepCopy());
        } catch (Throwable failure) {
            result = new DispatchResult(false, false, false,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        lastEvidence = result.evidence();
        if (!result.accepted() || !result.success()) {
            failStep(result.evidence(), gameTick);
        } else if (result.asynchronous()) {
            status = Status.WAITING_TASK;
        } else if (step.expectedEffects().isEmpty()) {
            completeStep(gameTick);
        } else {
            status = Status.VERIFYING;
            verificationDeadlineTick = saturatedAdd(gameTick, 200L);
        }
    }

    /** Accept only the terminal event for the exact internally dispatched step. */
    public synchronized boolean onTaskProgress(String taskId, TaskState taskState,
                                               long gameTick) {
        if (status != Status.WAITING_TASK || activeActionId == null
                || !activeActionId.equals(taskId)) return false;
        if (taskState == TaskState.PAUSED && !taskPaused) {
            taskPaused = true;
            taskPausedAtTick = gameTick;
        } else if (taskState == TaskState.RUNNING && taskPaused) {
            stepStartedTick = saturatedAdd(stepStartedTick,
                    Math.max(0L, gameTick - taskPausedAtTick));
            taskPaused = false;
            taskPausedAtTick = 0L;
        }
        return true;
    }

    /** Accept only the terminal event for the exact internally dispatched step. */
    public synchronized boolean onTaskFinished(String taskId, TaskState taskState,
                                               String evidence, long gameTick) {
        if (status != Status.WAITING_TASK || activeActionId == null
                || !activeActionId.equals(taskId)) return false;
        lastEvidence = normalize(evidence, taskState == null ? "unknown" : taskState.name());
        taskPaused = false;
        taskPausedAtTick = 0L;
        if (taskState != TaskState.SUCCESS) {
            failStep(lastEvidence, gameTick);
        } else if (steps.get(stepIndex).expectedEffects().isEmpty()) {
            completeStep(gameTick);
        } else {
            status = Status.VERIFYING;
            verificationDeadlineTick = saturatedAdd(gameTick, 200L);
            updatedTick = Math.max(updatedTick, gameTick);
        }
        return true;
    }

    public synchronized void cancel(String reason, long gameTick) {
        if (!active()) return;
        terminal(Status.CANCELLED, normalize(reason, "cancelled"), gameTick);
    }

    public synchronized boolean active() {
        return status == Status.RUNNING || status == Status.WAITING_TASK
                || status == Status.VERIFYING;
    }

    public synchronized boolean ownsAction(String actionId) {
        return actionId != null && actionId.equals(activeActionId);
    }

    public synchronized Snapshot snapshot() {
        String activeTool = stepIndex >= 0 && stepIndex < steps.size()
                ? steps.get(stepIndex).tool() : null;
        return new Snapshot(runId, skillName, status, stepIndex,
                steps.size(), attempt, activeTool, lastEvidence,
                startedTick, updatedTick);
    }

    public synchronized String summarizeForPrompt() {
        if (status == Status.IDLE) return "Skill runtime: idle\n";
        Snapshot value = snapshot();
        return "Skill runtime: run=" + value.runId() + " skill=" + value.skillName()
                + " status=" + value.status().name().toLowerCase(Locale.ROOT)
                + " step=" + Math.min(value.stepIndex() + 1, value.stepCount())
                + "/" + value.stepCount() + " tool=" + value.activeTool()
                + " evidence=" + value.lastEvidence() + "\n";
    }

    private void completeStep(long gameTick) {
        lastEvidence = "step verified: " + steps.get(stepIndex).tool();
        stepIndex++;
        attempt = 0;
        activeActionId = null;
        updatedTick = Math.max(updatedTick, gameTick);
        if (stepIndex >= steps.size()) {
            library.recordResult(skillName, true);
            terminal(Status.SUCCEEDED, "all skill effects verified", gameTick);
        } else {
            status = Status.RUNNING;
        }
    }

    private void failStep(String evidence, long gameTick) {
        Step step = steps.get(stepIndex);
        lastEvidence = normalize(evidence, "step failed");
        activeActionId = null;
        updatedTick = Math.max(updatedTick, gameTick);
        if (step.failurePolicy() == FailurePolicy.RETRY_ONCE
                && attempt < step.maxAttempts() && transientFailure(lastEvidence)) {
            status = Status.RUNNING;
            return;
        }
        library.recordResult(skillName, false);
        Status terminal = step.failurePolicy() == FailurePolicy.ABORT
                ? Status.FAILED : Status.NEEDS_REPLAN;
        terminal(terminal, "step " + (stepIndex + 1) + " " + step.tool()
                + " failed: " + lastEvidence, gameTick);
    }

    private void terminal(Status terminalStatus, String evidence, long gameTick) {
        status = terminalStatus;
        lastEvidence = normalize(evidence, terminalStatus.name());
        activeActionId = null;
        updatedTick = Math.max(updatedTick, gameTick);
        completionSink.completed(snapshot());
    }

    private boolean effectsVerified(Step step, long gameTick) {
        if (step.expectedEffects().isEmpty()) return true;
        // A declared postcondition must be newer than the action. Merely
        // finding the same old fact would falsely verify a no-op replay.
        return missing(step.expectedEffects(), stepStartedTick, gameTick).isEmpty()
                && worldModel.revision() > worldRevisionAtDispatch;
    }

    private List<Condition> missing(List<Condition> conditions,
                                    long notBeforeTick, long gameTick) {
        if (conditions == null || conditions.isEmpty()) return List.of();
        ArrayList<Condition> result = new ArrayList<>();
        for (Condition condition : conditions) {
            if (!worldModel.matches(condition.subject(), condition.predicate(),
                    condition.value(), notBeforeTick, condition.minimumConfidence(),
                    gameTick)) result.add(condition);
        }
        return List.copyOf(result);
    }

    private static List<Step> decode(String sequenceJson, JsonObject overrides) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(sequenceJson);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("action_sequence is invalid JSON", malformed);
        }
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("action_sequence must be an array");
        }
        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_STEPS) {
            throw new IllegalArgumentException("action_sequence must contain 1-"
                    + MAX_STEPS + " steps");
        }
        ArrayList<Step> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            if (!array.get(index).isJsonObject()) {
                throw new IllegalArgumentException("step " + index + " is not an object");
            }
            JsonObject source = array.get(index).getAsJsonObject();
            String tool = string(source, "tool", null);
            tool = normalizeTool(tool);
            if (NEVER_NEST.contains(tool)) {
                throw new IllegalArgumentException("step " + index
                        + " cannot invoke control tool " + tool);
            }
            JsonObject arguments = object(source, "args");
            if (arguments == null) arguments = new JsonObject();
            JsonObject patch = overrides == null ? null : object(overrides,
                    Integer.toString(index));
            if (patch != null) merge(arguments, patch);
            List<Condition> preconditions = conditions(source, "preconditions");
            List<Condition> effects = conditions(source, "expected_effects");
            FailurePolicy policy = switch (string(source, "on_failure", "replan")
                    .toLowerCase(Locale.ROOT)) {
                case "retry", "retry_once" -> FailurePolicy.RETRY_ONCE;
                case "abort" -> FailurePolicy.ABORT;
                default -> FailurePolicy.REPLAN;
            };
            int maxAttempts = integer(source, "max_attempts",
                    policy == FailurePolicy.RETRY_ONCE ? 2 : 1);
            // Engine tasks receive a 6000-tick authoritative deadline. The
            // enclosing skill must not time out first and leave its body task
            // running without an owner.
            long timeout = integer(source, "timeout_ticks", 6_400);
            result.add(new Step(tool, arguments, preconditions, effects,
                    policy, maxAttempts, timeout));
        }
        return List.copyOf(result);
    }

    private static List<Condition> conditions(JsonObject source, String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) return List.of();
        if (!source.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        ArrayList<Condition> result = new ArrayList<>();
        for (JsonElement element : source.getAsJsonArray(key)) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(key + " contains a non-object");
            }
            JsonObject value = element.getAsJsonObject();
            String subject = string(value, "subject", null);
            String predicate = string(value, "predicate", null);
            if (subject == null || predicate == null) {
                throw new IllegalArgumentException(key
                        + " conditions require subject and predicate");
            }
            result.add(new Condition(subject, predicate,
                    string(value, "value", "*"), number(value,
                    "minimum_confidence", 0.6)));
        }
        return List.copyOf(result);
    }

    private static void merge(JsonObject target, JsonObject patch) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return null;
        if (!source.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return source.getAsJsonObject(key).deepCopy();
    }

    private static String string(JsonObject source, String key, String fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()
                || !source.get(key).isJsonPrimitive()) return fallback;
        try {
            return source.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject source, String key, int fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double number(JsonObject source, String key, double fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) return fallback;
        try {
            return source.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean transientFailure(String evidence) {
        String value = normalize(evidence, "").toLowerCase(Locale.ROOT);
        return value.contains("timeout") || value.contains("timed out")
                || value.contains("interrupted") || value.contains("temporar")
                || value.contains("busy") || value.contains("retry");
    }

    private static String describe(Condition condition) {
        return condition.subject() + " " + condition.predicate()
                + "=" + condition.value();
    }

    private static String normalizeTool(String value) {
        String tool = normalize(value, "").toLowerCase(Locale.ROOT);
        if (!tool.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid tool name: " + value);
        }
        return tool;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return Math.max(0L, first + second);
    }
}
