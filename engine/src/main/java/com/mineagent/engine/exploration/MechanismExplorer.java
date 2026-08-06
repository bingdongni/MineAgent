package com.mineagent.engine.exploration;

import com.google.gson.JsonParser;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hypothesis-driven explorer for unfamiliar blocks, items, GUIs, and rules.
 *
 * <p>The explorer does not grant new powers or guess mod internals. It arms one
 * bounded experiment using an existing tool, records the action correlation,
 * and accepts a hypothesis only after a declared semantic observation appears.
 * High-risk and irreversible probes are rejected so novelty cannot bypass the
 * normal body scheduler or owner-safety constraints.
 */
public final class MechanismExplorer {
    public enum Risk { LOW, MEDIUM, HIGH }
    public enum Status { ARMED, RUNNING, OBSERVING, SUPPORTED, REFUTED, INCONCLUSIVE, ABORTED }

    public record Experiment(String id, String subject, String hypothesis,
                             String probeTool, String probeArguments,
                             String expectedSubject, String expectedPredicate,
                             String expectedValue, Risk risk, Status status,
                             String actionId, String evidence,
                             long createdTick, long actionTick,
                             long deadlineTick, long baselineWorldRevision,
                             int attempts) {
        public Experiment {
            id = normalize(id, "experiment");
            subject = normalize(subject, "unknown_mechanism");
            hypothesis = normalize(hypothesis, "Unknown behavior");
            probeTool = normalize(probeTool, "look_around");
            probeArguments = normalize(probeArguments, "{}");
            expectedSubject = normalize(expectedSubject, "tool:" + probeTool);
            expectedPredicate = normalize(expectedPredicate, "outcome");
            expectedValue = normalize(expectedValue, "success");
            risk = risk == null ? Risk.LOW : risk;
            status = status == null ? Status.ARMED : status;
            actionId = blankToNull(actionId);
            evidence = normalize(evidence, "Awaiting probe");
            createdTick = Math.max(0L, createdTick);
            actionTick = Math.max(0L, actionTick);
            deadlineTick = Math.max(actionTick, deadlineTick);
            baselineWorldRevision = Math.max(0L, baselineWorldRevision);
            attempts = Math.max(0, attempts);
        }

        public boolean terminal() {
            return status == Status.SUPPORTED || status == Status.REFUTED
                    || status == Status.INCONCLUSIVE || status == Status.ABORTED;
        }
    }

    public record Proposal(boolean accepted, Experiment experiment, String message) {}
    public record State(List<Experiment> experiments, long revision) {}

    @FunctionalInterface
    public interface EvidenceSink {
        void record(Experiment experiment, boolean supported, String evidence, long gameTick);
    }

    private static final int MAX_EXPERIMENTS = 128;
    private static final long OBSERVATION_WINDOW_TICKS = 160L;
    private static final Set<String> SAFE_PROBE_TOOLS = Set.of(
            "look_around", "scan_blocks", "scan_nearby_entities",
            "get_self_status", "get_owner_status", "get_world_info",
            "inspect_block", "inspect_block_storage", "inspect_gui",
            "lookup_recipe", "resolve_need", "interact_at", "interact_entity",
            "close_gui", "transfer_items", "craft", "equip_item");
    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "interact_at", "interact_entity", "transfer_items", "craft", "equip_item");

    private final SemanticWorldModel worldModel;
    private final EvidenceSink evidenceSink;
    private final LinkedHashMap<String, Experiment> experiments = new LinkedHashMap<>();
    private long revision;

    public MechanismExplorer(SemanticWorldModel worldModel, EvidenceSink evidenceSink) {
        this.worldModel = java.util.Objects.requireNonNull(worldModel, "worldModel");
        this.evidenceSink = java.util.Objects.requireNonNull(evidenceSink, "evidenceSink");
    }

    public synchronized Proposal propose(String subject, String hypothesis,
                                         String probeTool, String probeArguments,
                                         String expectedSubject,
                                         String expectedPredicate,
                                         String expectedValue, Risk risk,
                                         long gameTick) {
        String tool = normalize(probeTool, "").toLowerCase(Locale.ROOT);
        if (subject == null || subject.isBlank() || subject.length() > 256
                || hypothesis == null || hypothesis.isBlank() || hypothesis.length() > 512
                || probeArguments == null || probeArguments.length() > 2_048
                || (expectedSubject != null && expectedSubject.length() > 256)
                || (expectedPredicate != null && expectedPredicate.length() > 128)
                || (expectedValue != null && expectedValue.length() > 256)
                || !isJsonObject(probeArguments)) {
            return new Proposal(false, null,
                    "Experiment text or probe_arguments is invalid or too large");
        }
        if (!tool.matches("[a-z][a-z0-9_]{0,63}") || !SAFE_PROBE_TOOLS.contains(tool)) {
            return new Proposal(false, null,
                    "Probe tool is not permitted for controlled exploration: " + probeTool);
        }
        Risk normalizedRisk = risk == null ? Risk.LOW : risk;
        if (normalizedRisk == Risk.HIGH) {
            return new Proposal(false, null,
                    "High-risk experiments require an explicit owner-directed normal action");
        }
        if (MEDIUM_RISK_TOOLS.contains(tool) && normalizedRisk == Risk.LOW) {
            return new Proposal(false, null,
                    "Probe " + tool + " must declare risk=medium because it may change state");
        }
        if (activeExperiment() != null) {
            return new Proposal(false, activeExperiment(),
                    "Finish or abort the active experiment before arming another");
        }
        String id = "exp-" + UUID.randomUUID().toString().substring(0, 12);
        String expectedSubjectValue = normalize(expectedSubject, "tool:" + tool);
        String expectedPredicateValue = normalize(expectedPredicate, "outcome");
        String expectedValueValue = normalize(expectedValue, "success");
        Experiment experiment = new Experiment(id, subject, hypothesis, tool,
                probeArguments, expectedSubjectValue, expectedPredicateValue,
                expectedValueValue, normalizedRisk, Status.ARMED, null,
                "Call the declared probe tool once", gameTick, 0L,
                saturatedAdd(gameTick, 600L),
                worldModel.revision(), 0);
        experiments.put(id, experiment);
        revision++;
        trim();
        return new Proposal(true, experiment,
                "Experiment armed; call " + tool + " once with the proposed arguments");
    }

    /** Correlate the next matching tool call; unrelated tools remain untouched. */
    public synchronized String onToolDispatched(String actionId, String toolName,
                                                String arguments, long gameTick) {
        Experiment active = activeExperiment();
        if (active == null || active.status() != Status.ARMED
                || !active.probeTool().equals(toolName)
                || !sameJsonObject(active.probeArguments(), arguments)) return null;
        Experiment running = replace(active, Status.RUNNING, actionId,
                "Probe dispatched with args=" + compact(arguments, 240),
                gameTick, saturatedAdd(gameTick, OBSERVATION_WINDOW_TICKS),
                worldModel.revision(), active.attempts() + 1);
        experiments.put(running.id(), running);
        revision++;
        return running.id();
    }

    public synchronized void onToolResult(String actionId, boolean success,
                                          boolean asynchronous, String evidence,
                                          long gameTick) {
        Experiment active = byAction(actionId);
        if (active == null || active.terminal()) return;
        if (!success) {
            finish(active, Status.INCONCLUSIVE,
                    "Probe action failed before the hypothesis could be tested: "
                            + compact(evidence, 320), gameTick);
            return;
        }
        if (asynchronous) return;
        beginObservation(active, evidence, gameTick);
    }

    public synchronized void onTaskFinished(String actionId, TaskState state,
                                            String evidence, long gameTick) {
        Experiment active = byAction(actionId);
        if (active == null || active.terminal()) return;
        if (state != TaskState.SUCCESS) {
            finish(active, Status.INCONCLUSIVE,
                    "Probe task ended " + state + ": " + compact(evidence, 320), gameTick);
            return;
        }
        beginObservation(active, evidence, gameTick);
    }

    public synchronized void tick(long gameTick) {
        Experiment active = activeExperiment();
        if (active == null) return;
        if (active.status() == Status.ARMED || active.status() == Status.RUNNING) {
            if (gameTick >= active.deadlineTick()) {
                finish(active, Status.INCONCLUSIVE,
                        active.status() == Status.ARMED
                                ? "Armed probe was never dispatched"
                                : "Probe did not reach a terminal executor state",
                        gameTick);
            }
            return;
        }
        if (matches(active, gameTick)) {
            finish(active, Status.SUPPORTED,
                    "Observed declared postcondition " + expected(active), gameTick);
        } else if (contradicts(active, gameTick)) {
            finish(active, Status.REFUTED,
                    "Observed a contradictory value for " + expected(active), gameTick);
        } else if (gameTick >= active.deadlineTick()) {
            finish(active, Status.INCONCLUSIVE,
                    "Observation window expired without " + expected(active), gameTick);
        }
    }

    public synchronized boolean abort(String experimentId, String reason, long gameTick) {
        Experiment active = experimentId == null ? activeExperiment()
                : experiments.get(experimentId);
        if (active == null || active.terminal()) return false;
        finish(active, Status.ABORTED, normalize(reason, "aborted"), gameTick);
        return true;
    }

    public synchronized Experiment get(String id) {
        return id == null ? null : experiments.get(id);
    }

    public synchronized Collection<Experiment> all() {
        return List.copyOf(experiments.values());
    }

    public synchronized String summarizeForPrompt() {
        Experiment active = activeExperiment();
        StringBuilder out = new StringBuilder("Mechanism exploration:\n");
        if (active == null) out.append("- active=none\n");
        else out.append("- active=").append(active.id()).append(" status=")
                .append(active.status().name().toLowerCase(Locale.ROOT))
                .append(" subject=").append(active.subject())
                .append(" hypothesis=").append(active.hypothesis())
                .append(" probe=").append(active.probeTool())
                .append(" expected=").append(expected(active)).append('\n');
        experiments.values().stream().filter(Experiment::terminal)
                .sorted(Comparator.comparingLong(Experiment::createdTick).reversed())
                .limit(4).forEach(experiment -> out.append("- learned ")
                        .append(experiment.subject()).append(" -> ")
                        .append(experiment.status().name().toLowerCase(Locale.ROOT))
                        .append(" evidence=").append(experiment.evidence()).append('\n'));
        out.append("Use one reversible probe and one observable postcondition; absence of evidence is inconclusive, not a rule.\n");
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(List.copyOf(experiments.values()), revision);
    }

    public synchronized void importState(State state) {
        experiments.clear();
        if (state != null && state.experiments() != null) {
            for (Experiment experiment : state.experiments()) {
                if (experiment == null || experiment.id() == null) continue;
                Status restored = experiment.terminal()
                        ? experiment.status() : Status.INCONCLUSIVE;
                String evidence = experiment.terminal() ? experiment.evidence()
                        : "Experiment was interrupted by a game restart";
                Experiment normalized = new Experiment(experiment.id(),
                        experiment.subject(), experiment.hypothesis(),
                        experiment.probeTool(), experiment.probeArguments(),
                        experiment.expectedSubject(), experiment.expectedPredicate(),
                        experiment.expectedValue(), experiment.risk(), restored,
                        null, evidence, experiment.createdTick(),
                        experiment.actionTick(), experiment.deadlineTick(),
                        experiment.baselineWorldRevision(), experiment.attempts());
                experiments.put(normalized.id(), normalized);
            }
            revision = Math.max(0L, state.revision()) + 1L;
        }
        trim();
    }

    private void beginObservation(Experiment experiment, String evidence, long gameTick) {
        if (matches(experiment, gameTick)) {
            finish(experiment, Status.SUPPORTED,
                    "Probe succeeded and observed " + expected(experiment), gameTick);
            return;
        }
        if (contradicts(experiment, gameTick)) {
            finish(experiment, Status.REFUTED,
                    "Probe observed a contradictory value for " + expected(experiment),
                    gameTick);
            return;
        }
        Experiment observing = replace(experiment, Status.OBSERVING,
                experiment.actionId(), "Probe succeeded; awaiting " + expected(experiment)
                        + ". Result=" + compact(evidence, 240),
                experiment.actionTick(), saturatedAdd(gameTick, OBSERVATION_WINDOW_TICKS),
                experiment.baselineWorldRevision(), experiment.attempts());
        experiments.put(observing.id(), observing);
        revision++;
    }

    private boolean matches(Experiment experiment, long gameTick) {
        return worldModel.revision() > experiment.baselineWorldRevision()
                && worldModel.matches(experiment.expectedSubject(),
                experiment.expectedPredicate(), experiment.expectedValue(),
                experiment.actionTick(), 0.6, gameTick);
    }

    private boolean contradicts(Experiment experiment, long gameTick) {
        if ("*".equals(experiment.expectedValue())) return false;
        return worldModel.find(experiment.expectedSubject(),
                        experiment.expectedPredicate(), gameTick)
                .filter(fact -> fact.observedTick() >= experiment.actionTick())
                .filter(fact -> fact.confidenceAt(gameTick) >= 0.6)
                .map(fact -> !fact.value().trim().equalsIgnoreCase(
                        experiment.expectedValue().trim()))
                .orElse(false);
    }

    private void finish(Experiment experiment, Status status,
                        String evidence, long gameTick) {
        Experiment finished = replace(experiment, status, experiment.actionId(),
                evidence, experiment.actionTick(), gameTick,
                experiment.baselineWorldRevision(), experiment.attempts());
        experiments.put(finished.id(), finished);
        worldModel.observe("mechanism:" + finished.subject(), "hypothesis_status",
                status.name().toLowerCase(Locale.ROOT), null,
                status == Status.SUPPORTED ? 0.8 : 0.55,
                "mechanism_explorer", finished.id(), gameTick, 0L, true);
        // Inconclusive and aborted experiments carry no likelihood evidence;
        // counting them as failures would teach a false negative rule.
        if (status == Status.SUPPORTED || status == Status.REFUTED) {
            evidenceSink.record(finished, status == Status.SUPPORTED,
                    finished.evidence(), gameTick);
        }
        revision++;
    }

    private Experiment activeExperiment() {
        for (Experiment experiment : experiments.values()) {
            if (!experiment.terminal()) return experiment;
        }
        return null;
    }

    private Experiment byAction(String actionId) {
        if (actionId == null) return null;
        for (Experiment experiment : experiments.values()) {
            if (actionId.equals(experiment.actionId())) return experiment;
        }
        return null;
    }

    private void trim() {
        while (experiments.size() > MAX_EXPERIMENTS) {
            String removable = experiments.values().stream()
                    .filter(Experiment::terminal)
                    .min(Comparator.comparingLong(Experiment::createdTick))
                    .map(Experiment::id)
                    .orElse(experiments.keySet().iterator().next());
            experiments.remove(removable);
        }
    }

    private static Experiment replace(Experiment value, Status status,
                                      String actionId, String evidence,
                                      long actionTick, long deadlineTick,
                                      long baselineRevision, int attempts) {
        return new Experiment(value.id(), value.subject(), value.hypothesis(),
                value.probeTool(), value.probeArguments(), value.expectedSubject(),
                value.expectedPredicate(), value.expectedValue(), value.risk(),
                status, actionId, evidence, value.createdTick(), actionTick,
                deadlineTick, baselineRevision, attempts);
    }

    private static String expected(Experiment value) {
        return value.expectedSubject() + " " + value.expectedPredicate()
                + "=" + value.expectedValue();
    }

    private static String compact(String value, int limit) {
        String normalized = normalize(value, "none").replace('\n', ' ')
                .replace('\r', ' ');
        return normalized.length() <= limit ? normalized
                : normalized.substring(0, limit) + "...";
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return Math.max(0L, first + second);
    }

    private static boolean isJsonObject(String value) {
        try {
            return JsonParser.parseString(value).isJsonObject();
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean sameJsonObject(String first, String second) {
        try {
            var left = JsonParser.parseString(first);
            var right = JsonParser.parseString(second);
            return left.isJsonObject() && right.isJsonObject() && left.equals(right);
        } catch (RuntimeException malformed) {
            return false;
        }
    }
}
