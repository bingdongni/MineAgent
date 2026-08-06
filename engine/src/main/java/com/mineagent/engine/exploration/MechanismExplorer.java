package com.mineagent.engine.exploration;

import com.google.gson.JsonParser;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.skill.SkillLibrary;
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
 * Risk-bounded, evidence-driven adaptation to unfamiliar game mechanisms.
 *
 * <p>An experiment freezes its semantic baseline before dispatch, executes one
 * admitted existing tool, and then waits for a newer correlated observation.
 * Independent contexts accumulate in {@link MechanismKnowledgeBase}; only a
 * confirmed current-environment rule is compiled into a normal verified skill.
 * This keeps exploration below owner safety, survival and scheduler authority.
 */
public final class MechanismExplorer {
    public enum Risk { LOW, MEDIUM, HIGH }
    public enum Status { ARMED, RUNNING, OBSERVING, SUPPORTED, REFUTED, INCONCLUSIVE, ABORTED }

    public record FactSnapshot(boolean present, String value, long eventSequence,
                               String correlationId, long observedTick) {
        public FactSnapshot {
            value = value == null ? "" : compact(value, 256);
            correlationId = blankToNull(correlationId);
            eventSequence = Math.max(0L, eventSequence);
            observedTick = Math.max(0L, observedTick);
        }

        static FactSnapshot absent() {
            return new FactSnapshot(false, "", 0L, null, 0L);
        }
    }

    public record Experiment(String id, String subject, String hypothesis,
                             String probeTool, String probeArguments,
                             String expectedSubject, String expectedPredicate,
                             String expectedValue, Risk risk, Status status,
                             String actionId, String evidence,
                             long createdTick, long actionTick,
                             long deadlineTick, long baselineWorldRevision,
                             int attempts, String contextKey,
                             String compensationTool, String compensationArguments,
                             boolean reversible, int estimatedCost,
                             double estimatedInformationGain,
                             FactSnapshot baseline, boolean probeSucceeded,
                             boolean compensationAttempted,
                             boolean compensationSucceeded,
                             EnvironmentFingerprint environmentFingerprint) {
        public Experiment {
            id = normalize(id, "experiment");
            subject = normalize(subject, "unknown_mechanism");
            hypothesis = normalize(hypothesis, "Unknown behavior");
            probeTool = normalize(probeTool, "look_around").toLowerCase(Locale.ROOT);
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
            contextKey = normalize(contextKey, subject + "|" + probeArguments);
            compensationTool = blankToNull(compensationTool);
            compensationArguments = normalize(compensationArguments, "{}");
            estimatedCost = Math.max(1, Math.min(12, estimatedCost));
            estimatedInformationGain = unit(estimatedInformationGain);
            baseline = baseline == null ? FactSnapshot.absent() : baseline;
            environmentFingerprint = environmentFingerprint == null
                    ? EnvironmentFingerprint.unknown() : environmentFingerprint;
        }

        public boolean terminal() {
            return status == Status.SUPPORTED || status == Status.REFUTED
                    || status == Status.INCONCLUSIVE || status == Status.ABORTED;
        }
    }

    public record ProbeCandidate(String tool, String arguments, Risk risk,
                                 boolean reversible, String compensationTool,
                                 String compensationArguments, int estimatedCost,
                                 double estimatedInformationGain) {
        public ProbeCandidate {
            tool = normalize(tool, "").toLowerCase(Locale.ROOT);
            arguments = normalize(arguments, "{}");
            risk = risk == null ? Risk.LOW : risk;
            compensationTool = blankToNull(compensationTool);
            compensationArguments = normalize(compensationArguments, "{}");
            estimatedCost = Math.max(1, Math.min(12, estimatedCost));
            estimatedInformationGain = unit(estimatedInformationGain);
        }
    }

    public record Proposal(boolean accepted, Experiment experiment, String message) {}
    public record Recommendation(ProbeCandidate candidate, double score, String message) {}
    public record Budget(String subject, int remaining, int probes, long updatedTick,
                         EnvironmentFingerprint fingerprint) {
        public Budget {
            subject = normalize(subject, "unknown");
            remaining = Math.max(0, Math.min(MAX_BUDGET, remaining));
            probes = Math.max(0, Math.min(MAX_PROBES_PER_SUBJECT, probes));
            updatedTick = Math.max(0L, updatedTick);
            fingerprint = fingerprint == null
                    ? EnvironmentFingerprint.unknown() : fingerprint;
        }
    }
    public record State(List<Experiment> experiments,
                        MechanismKnowledgeBase.State knowledge,
                        List<Budget> budgets, long revision) {}

    @FunctionalInterface
    public interface EvidenceSink {
        void record(Experiment experiment, boolean supported, String evidence, long gameTick);
    }

    @FunctionalInterface
    public interface CompensationDispatcher {
        CompensationResult dispatch(String actionId, String tool, String arguments);
    }

    public record CompensationResult(boolean accepted, boolean asynchronous,
                                     boolean success, String evidence) {
        public CompensationResult {
            evidence = normalize(evidence, success ? "compensated" : "compensation failed");
        }
    }

    private static final int MAX_EXPERIMENTS = 128;
    private static final int MAX_BUDGET = 12;
    private static final int MAX_PROBES_PER_SUBJECT = 6;
    private static final long BUDGET_RESET_TICKS = 24_000L;
    private static final long OBSERVATION_WINDOW_TICKS = 160L;
    private static final Set<String> SAFE_PROBE_TOOLS = Set.of(
            "look_around", "scan_blocks", "scan_nearby_entities",
            "get_self_status", "get_owner_status", "get_world_info",
            "inspect_block", "inspect_block_storage", "inspect_gui",
            "lookup_recipe", "resolve_need", "interact_at", "interact_entity",
            "close_gui", "transfer_items", "craft", "equip_item");
    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "interact_at", "interact_entity", "transfer_items", "craft", "equip_item");
    private static final Set<String> SAFE_COMPENSATION_TOOLS = Set.of(
            "close_gui", "transfer_items", "equip_item");

    private final SemanticWorldModel worldModel;
    private final EvidenceSink evidenceSink;
    private final CompensationDispatcher compensationDispatcher;
    private final MechanismKnowledgeBase knowledgeBase;
    private final AdaptationRuntime adaptationRuntime;
    private final LinkedHashMap<String, Experiment> experiments = new LinkedHashMap<>();
    private final LinkedHashMap<String, Budget> budgets = new LinkedHashMap<>();
    private long revision;

    public MechanismExplorer(SemanticWorldModel worldModel, EvidenceSink evidenceSink) {
        this(worldModel, evidenceSink, null, EnvironmentFingerprint.unknown(), null);
    }

    public MechanismExplorer(SemanticWorldModel worldModel, EvidenceSink evidenceSink,
                             SkillLibrary skillLibrary,
                             EnvironmentFingerprint fingerprint,
                             CompensationDispatcher compensationDispatcher) {
        this.worldModel = java.util.Objects.requireNonNull(worldModel, "worldModel");
        this.evidenceSink = java.util.Objects.requireNonNull(evidenceSink, "evidenceSink");
        this.compensationDispatcher = compensationDispatcher;
        this.knowledgeBase = new MechanismKnowledgeBase(fingerprint);
        this.adaptationRuntime = new AdaptationRuntime(knowledgeBase, skillLibrary);
    }

    /** Backward-compatible entry point with a safe inferred GUI compensation. */
    public synchronized Proposal propose(String subject, String hypothesis,
                                         String probeTool, String probeArguments,
                                         String expectedSubject,
                                         String expectedPredicate,
                                         String expectedValue, Risk risk,
                                         long gameTick) {
        String tool = normalize(probeTool, "").toLowerCase(Locale.ROOT);
        String compensation = "interact_at".equals(tool) ? "close_gui" : null;
        boolean reversible = risk != Risk.MEDIUM || compensation != null;
        return propose(subject, hypothesis, probeTool, probeArguments,
                expectedSubject, expectedPredicate, expectedValue, risk,
                subject + "|" + probeArguments, reversible, compensation, "{}",
                defaultCost(tool, risk), 0.6, gameTick);
    }

    public synchronized Proposal propose(String subject, String hypothesis,
                                         String probeTool, String probeArguments,
                                         String expectedSubject,
                                         String expectedPredicate,
                                         String expectedValue, Risk risk,
                                         String contextKey, boolean reversible,
                                         String compensationTool,
                                         String compensationArguments,
                                         int estimatedCost,
                                         double estimatedInformationGain,
                                         long gameTick) {
        String tool = normalize(probeTool, "").toLowerCase(Locale.ROOT);
        String compensation = blankToNull(compensationTool);
        if (subject == null || subject.isBlank() || subject.length() > 256
                || hypothesis == null || hypothesis.isBlank() || hypothesis.length() > 512
                || probeArguments == null || probeArguments.length() > 2_048
                || (expectedSubject != null && expectedSubject.length() > 256)
                || (expectedPredicate != null && expectedPredicate.length() > 128)
                || (expectedValue != null && expectedValue.length() > 256)
                || contextKey == null || contextKey.isBlank() || contextKey.length() > 256
                || !isJsonObject(probeArguments)
                || !isJsonObject(compensationArguments == null ? "{}" : compensationArguments)) {
            return new Proposal(false, null,
                    "Experiment text, context, or JSON arguments are invalid or too large");
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
        if (normalizedRisk == Risk.MEDIUM && (!reversible || compensation == null
                || !SAFE_COMPENSATION_TOOLS.contains(compensation))) {
            return new Proposal(false, null,
                    "A state-changing probe requires an explicit verified compensation tool");
        }
        if ("craft".equals(tool)) {
            return new Proposal(false, null,
                    "Crafting consumes inputs and has no generic lossless compensation; inspect recipes instead");
        }
        if (activeExperiment() != null) {
            return new Proposal(false, activeExperiment(),
                    "Finish or abort the active experiment before arming another");
        }
        int cost = Math.max(1, Math.min(MAX_BUDGET, estimatedCost));
        Budget budget = budgetFor(subject, gameTick);
        if (budget.probes() >= MAX_PROBES_PER_SUBJECT || budget.remaining() < cost) {
            return new Proposal(false, null,
                    "Exploration budget exhausted for " + subject
                            + "; use known evidence or request explicit owner direction");
        }
        String id = "exp-" + UUID.randomUUID().toString().substring(0, 12);
        String expectedSubjectValue = normalize(expectedSubject, "tool:" + tool);
        String expectedPredicateValue = normalize(expectedPredicate, "outcome");
        String expectedValueValue = normalize(expectedValue, "success");
        Experiment experiment = new Experiment(id, subject, hypothesis, tool,
                probeArguments, expectedSubjectValue, expectedPredicateValue,
                expectedValueValue, normalizedRisk, Status.ARMED, null,
                "Call the declared probe tool once", gameTick, 0L,
                saturatedAdd(gameTick, 600L), worldModel.revision(), 0,
                contextKey, compensation, compensationArguments, reversible,
                cost, estimatedInformationGain, snapshot(expectedSubjectValue,
                expectedPredicateValue, gameTick), false, false, false,
                knowledgeBase.currentFingerprint());
        experiments.put(id, experiment);
        budgets.put(canonical(subject), new Budget(subject,
                budget.remaining() - cost, budget.probes() + 1, gameTick,
                knowledgeBase.currentFingerprint()));
        revision++;
        trim();
        return new Proposal(true, experiment,
                "Experiment armed; call " + tool + " once with the exact proposed arguments");
    }

    /** Rank caller-supplied black-box probes by information per cost and safety. */
    public synchronized Recommendation recommend(Collection<ProbeCandidate> candidates,
                                                  String subject, long gameTick) {
        Budget budget = budgetFor(subject, gameTick);
        ProbeCandidate best = candidates == null ? null : candidates.stream()
                .filter(candidate -> validCandidate(candidate, budget))
                .max(Comparator.comparingDouble(MechanismExplorer::probeScore))
                .orElse(null);
        if (best == null) return new Recommendation(null, 0.0,
                "No candidate fits the remaining reversible exploration budget");
        return new Recommendation(best, probeScore(best),
                "Prefer the highest expected information per bounded cost; verify one probe only");
    }

    /** Correlate the next exact matching tool call; unrelated actions stay untouched. */
    public synchronized String onToolDispatched(String actionId, String toolName,
                                                String arguments, long gameTick) {
        Experiment active = activeExperiment();
        if (active == null || active.status() != Status.ARMED
                || !active.probeTool().equals(toolName)
                || !sameJsonObject(active.probeArguments(), arguments)) return null;
        Experiment running = lifecycle(active, Status.RUNNING, actionId,
                "Probe dispatched with args=" + compact(arguments, 240), gameTick,
                saturatedAdd(gameTick, OBSERVATION_WINDOW_TICKS),
                worldModel.revision(), active.attempts() + 1,
                snapshot(active.expectedSubject(), active.expectedPredicate(), gameTick),
                false);
        experiments.put(running.id(), running);
        revision++;
        return running.id();
    }

    public synchronized void onToolResult(String actionId, boolean success,
                                          boolean asynchronous, String evidence,
                                          long gameTick) {
        Experiment active = byAction(actionId);
        onToolResult(actionId, active == null ? null : active.probeTool(), success,
                asynchronous, evidence, gameTick);
    }

    /** Also profiles ordinary inspection calls, not just explicitly armed probes. */
    public synchronized void onToolResult(String actionId, String toolName,
                                          boolean success, boolean asynchronous,
                                          String evidence, long gameTick) {
        if (success && !asynchronous && toolName != null) {
            List<MechanismKnowledgeBase.Profile> observed =
                    knowledgeBase.observeToolResult(toolName, evidence, gameTick);
            // Publish the bounded profile projection into the same temporal
            // evidence substrate used by skill and experiment postconditions.
            // A later inspection can therefore verify a machine transition
            // without coupling the explorer to any specific mod or menu class.
            for (MechanismKnowledgeBase.Profile profile : observed) {
                String subject = "profile:" + profile.subject();
                worldModel.observe(subject, "kind",
                        profile.kind().name().toLowerCase(Locale.ROOT), null,
                        0.95, toolName, actionId, gameTick, 400L, false);
                profile.attributes().entrySet().stream().limit(48).forEach(entry ->
                        worldModel.observe(subject, "attribute." + entry.getKey(),
                                entry.getValue(), null, 0.95, toolName, actionId,
                                gameTick, 400L, false));
            }
        }
        Experiment active = byAction(actionId);
        if (active == null || active.terminal()) return;
        if (!success) {
            finish(active, Status.INCONCLUSIVE,
                    "Probe action failed before the hypothesis could be tested: "
                            + compact(evidence, 320), gameTick);
            return;
        }
        if (asynchronous) return;
        active = withProbeSucceeded(active);
        experiments.put(active.id(), active);
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
        active = withProbeSucceeded(active);
        experiments.put(active.id(), active);
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
                    "Observed a new declared postcondition " + expected(active), gameTick);
        } else if (contradicts(active, gameTick)) {
            finish(active, Status.REFUTED,
                    "Observed a new contradictory value for " + expected(active), gameTick);
        } else if (gameTick >= active.deadlineTick()) {
            String reason = unchangedFromBaseline(active, gameTick)
                    ? "Post-state was unchanged from baseline; no causal evidence for "
                    : "Observation window expired without ";
            finish(active, Status.INCONCLUSIVE, reason + expected(active), gameTick);
        }
    }

    public synchronized boolean abort(String experimentId, String reason, long gameTick) {
        Experiment active = experimentId == null ? activeExperiment() : experiments.get(experimentId);
        if (active == null || active.terminal()) return false;
        finish(active, Status.ABORTED, normalize(reason, "aborted"), gameTick);
        return true;
    }

    /** Adapter failures count only when the declared effect was explicitly unobserved. */
    public synchronized void onAdapterOutcome(String skillName, boolean success,
                                              String evidence, long gameTick) {
        if (success || evidence == null
                || !evidence.toLowerCase(Locale.ROOT).contains("postcondition")) return;
        MechanismKnowledgeBase.Rule stale = knowledgeBase
                .recordAdapterContradiction(skillName, gameTick);
        adaptationRuntime.invalidate(stale);
    }

    public synchronized Experiment get(String id) {
        return id == null ? null : experiments.get(id);
    }

    public synchronized Collection<Experiment> all() {
        return List.copyOf(experiments.values());
    }

    public synchronized MechanismKnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    public synchronized boolean hasRelevantNovelty(String query) {
        return knowledgeBase.hasRelevantNovelty(query);
    }

    public synchronized String summarizeForPrompt() {
        return summarizeForPrompt("");
    }

    /** Recall only rules relevant to the current owner goal to protect token budget. */
    public synchronized String summarizeForPrompt(String query) {
        Experiment active = activeExperiment();
        List<MechanismKnowledgeBase.Rule> relevant = knowledgeBase.relevantRules(query, 3);
        List<MechanismKnowledgeBase.Profile> profiles =
                knowledgeBase.relevantProfiles(query, 2);
        if (active == null && relevant.isEmpty() && profiles.isEmpty()) {
            return "Mechanism adaptation: idle\n";
        }
        StringBuilder out = new StringBuilder("Mechanism adaptation:\n");
        if (active != null) out.append("- active=").append(active.id())
                .append(" status=").append(active.status().name().toLowerCase(Locale.ROOT))
                .append(" subject=").append(active.subject())
                .append(" probe=").append(active.probeTool())
                .append(" expected=").append(expected(active)).append('\n');
        for (MechanismKnowledgeBase.Rule rule : relevant) {
            out.append("- rule subject=").append(rule.subject())
                    .append(" status=").append(rule.status().name().toLowerCase(Locale.ROOT))
                    .append(" confidence=").append(String.format(Locale.ROOT, "%.2f", rule.confidence()))
                    .append(" evidence=").append(rule.supports()).append('+')
                    .append(rule.refutations()).append('-')
                    .append(" hypothesis=").append(compact(rule.hypothesis(), 180));
            if (rule.reusable(knowledgeBase.currentFingerprint()) && rule.adapterSkill() != null) {
                out.append(" adapter=").append(rule.adapterSkill());
            }
            out.append('\n');
        }
        for (MechanismKnowledgeBase.Profile profile : profiles) {
            out.append("- unfamiliar subject=").append(profile.subject())
                    .append(" kind=").append(profile.kind().name().toLowerCase(Locale.ROOT))
                    .append(" novelty=").append(knowledgeBase.novelty(profile.subject())
                            .name().toLowerCase(Locale.ROOT));
            if (!profile.attributes().isEmpty()) {
                out.append(" observed=");
                profile.attributes().entrySet().stream().limit(4).forEach(entry ->
                        out.append(entry.getKey()).append('=').append(entry.getValue()).append(','));
            }
            out.append('\n');
        }
        out.append("Unknown is not false: prefer a reversible, budgeted probe; require independent support before reuse.\n");
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(List.copyOf(experiments.values()),
                knowledgeBase.exportState(), List.copyOf(budgets.values()), revision);
    }

    public synchronized void importState(State state) {
        experiments.clear();
        budgets.clear();
        if (state != null) {
            if (state.experiments() != null) for (Experiment experiment : state.experiments()) {
                if (experiment == null || experiment.id() == null) continue;
                Status restored = experiment.terminal() ? experiment.status() : Status.INCONCLUSIVE;
                String evidence = experiment.terminal() ? experiment.evidence()
                        : "Experiment was interrupted by a game restart; body ownership was not restored";
                Experiment normalized = lifecycle(experiment, restored, null, evidence,
                        experiment.actionTick(), experiment.deadlineTick(),
                        experiment.baselineWorldRevision(), experiment.attempts(),
                        experiment.baseline(), experiment.probeSucceeded());
                experiments.put(normalized.id(), normalized);
            }
            knowledgeBase.importState(state.knowledge());
            if (state.budgets() != null) for (Budget budget : state.budgets()) {
                if (budget != null) budgets.put(canonical(budget.subject()), budget);
            }
            revision = Math.max(0L, state.revision()) + 1L;
        }
        adaptationRuntime.rebuild(knowledgeBase.rules());
        trim();
    }

    private void beginObservation(Experiment experiment, String evidence, long gameTick) {
        if (matches(experiment, gameTick)) {
            finish(experiment, Status.SUPPORTED,
                    "Probe succeeded and observed a new " + expected(experiment), gameTick);
            return;
        }
        if (contradicts(experiment, gameTick)) {
            finish(experiment, Status.REFUTED,
                    "Probe observed a new contradictory value for " + expected(experiment), gameTick);
            return;
        }
        Experiment observing = lifecycle(experiment, Status.OBSERVING,
                experiment.actionId(), "Probe succeeded; awaiting " + expected(experiment)
                        + ". Result=" + compact(evidence, 240), experiment.actionTick(),
                saturatedAdd(gameTick, OBSERVATION_WINDOW_TICKS),
                experiment.baselineWorldRevision(), experiment.attempts(),
                experiment.baseline(), true);
        experiments.put(observing.id(), observing);
        revision++;
    }

    private boolean matches(Experiment experiment, long gameTick) {
        return newFact(experiment, gameTick)
                .filter(fact -> valueMatches(fact.value(), experiment.expectedValue()))
                .isPresent();
    }

    private boolean contradicts(Experiment experiment, long gameTick) {
        if ("*".equals(experiment.expectedValue())) return false;
        return newFact(experiment, gameTick)
                .filter(fact -> !valueMatches(fact.value(), experiment.expectedValue()))
                .isPresent();
    }

    private java.util.Optional<SemanticWorldModel.SemanticFact> newFact(
            Experiment experiment, long gameTick) {
        if (worldModel.revision() <= experiment.baselineWorldRevision()) {
            return java.util.Optional.empty();
        }
        return worldModel.find(experiment.expectedSubject(),
                        experiment.expectedPredicate(), gameTick)
                .filter(fact -> fact.observedTick() >= experiment.actionTick())
                .filter(fact -> fact.confidenceAt(gameTick) >= 0.6)
                // An identical heartbeat retains its old event sequence.  It
                // refreshes freshness but does not prove the probe caused state.
                .filter(fact -> !experiment.baseline().present()
                        || fact.eventSequence() > experiment.baseline().eventSequence())
                // For a world-state hypothesis, a later sample with the same
                // value is still observationally identical to the baseline.
                // Tool outcomes are events and are correlated separately.
                .filter(fact -> !experiment.baseline().present()
                        || experiment.expectedSubject().startsWith("tool:")
                        || !fact.value().equals(experiment.baseline().value()))
                .filter(fact -> !experiment.expectedSubject().equals(
                                "tool:" + experiment.probeTool())
                        || experiment.actionId() == null
                        || experiment.actionId().equals(fact.correlationId()));
    }

    private boolean unchangedFromBaseline(Experiment experiment, long gameTick) {
        if (!experiment.baseline().present()) return false;
        return worldModel.find(experiment.expectedSubject(), experiment.expectedPredicate(), gameTick)
                .map(fact -> fact.eventSequence() == experiment.baseline().eventSequence()
                        && fact.value().equals(experiment.baseline().value()))
                .orElse(false);
    }

    private void finish(Experiment experiment, Status status, String evidence, long gameTick) {
        Experiment finished = lifecycle(experiment, status, experiment.actionId(),
                evidence, experiment.actionTick(), gameTick,
                experiment.baselineWorldRevision(), experiment.attempts(),
                experiment.baseline(), experiment.probeSucceeded());
        experiments.put(finished.id(), finished);
        worldModel.observe("mechanism:" + finished.subject(), "hypothesis_status",
                status.name().toLowerCase(Locale.ROOT), null,
                status == Status.SUPPORTED ? 0.8 : 0.55,
                "mechanism_explorer", finished.id(), gameTick, 0L, true);

        // An explicit abort contains no likelihood evidence and should not
        // create an empty candidate rule merely because the owner stopped it.
        MechanismKnowledgeBase.Rule rule = status == Status.ABORTED ? null
                : knowledgeBase.record(finished, status, finished.evidence(), gameTick);
        String adapter = adaptationRuntime.synchronize(rule);
        if (adapter != null) {
            worldModel.observe("mechanism:" + finished.subject(), "adapter",
                    adapter, null, rule.confidence(), "adaptation_runtime",
                    finished.id(), gameTick, 0L, true);
        }
        // Execution errors and timeouts are not negative likelihood evidence.
        if (status == Status.SUPPORTED || status == Status.REFUTED) {
            evidenceSink.record(finished, status == Status.SUPPORTED,
                    finished.evidence(), gameTick);
        }
        if (finished.probeSucceeded() && finished.reversible()
                && finished.compensationTool() != null && status != Status.ABORTED) {
            finished = compensate(finished);
            experiments.put(finished.id(), finished);
        }
        revision++;
    }

    private Experiment compensate(Experiment experiment) {
        if (compensationDispatcher == null) {
            return withCompensation(experiment, true, false,
                    experiment.evidence() + "; compensation unavailable in this runtime");
        }
        CompensationResult result;
        try {
            result = compensationDispatcher.dispatch(experiment.id() + ":compensate",
                    experiment.compensationTool(), experiment.compensationArguments());
        } catch (Throwable failure) {
            result = new CompensationResult(false, false, false,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        boolean verified = result.accepted() && result.success() && !result.asynchronous();
        return withCompensation(experiment, true, verified,
                experiment.evidence() + "; compensation=" + compact(result.evidence(), 200));
    }

    private FactSnapshot snapshot(String subject, String predicate, long gameTick) {
        return worldModel.find(subject, predicate, gameTick)
                .map(fact -> new FactSnapshot(true, fact.value(), fact.eventSequence(),
                        fact.correlationId(), fact.observedTick()))
                .orElseGet(FactSnapshot::absent);
    }

    private Budget budgetFor(String subject, long gameTick) {
        Budget old = budgets.get(canonical(subject));
        if (old == null || !old.fingerprint().compatible(
                knowledgeBase.currentFingerprint())
                || gameTick - old.updatedTick() >= BUDGET_RESET_TICKS) {
            return new Budget(subject, MAX_BUDGET, 0, gameTick,
                    knowledgeBase.currentFingerprint());
        }
        return old;
    }

    private boolean validCandidate(ProbeCandidate candidate, Budget budget) {
        if (candidate == null || candidate.risk() == Risk.HIGH
                || !SAFE_PROBE_TOOLS.contains(candidate.tool())
                || candidate.estimatedCost() > budget.remaining()
                || !isJsonObject(candidate.arguments())) return false;
        if (!MEDIUM_RISK_TOOLS.contains(candidate.tool())) return true;
        return candidate.risk() == Risk.MEDIUM && candidate.reversible()
                && candidate.compensationTool() != null
                && SAFE_COMPENSATION_TOOLS.contains(candidate.compensationTool())
                && !"craft".equals(candidate.tool());
    }

    private static double probeScore(ProbeCandidate candidate) {
        double riskPenalty = candidate.risk() == Risk.MEDIUM ? 0.25 : 0.0;
        double reversibility = candidate.reversible() ? 0.1 : 0.0;
        return candidate.estimatedInformationGain() / candidate.estimatedCost()
                + reversibility - riskPenalty;
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
            String removable = experiments.values().stream().filter(Experiment::terminal)
                    .min(Comparator.comparingLong(Experiment::createdTick))
                    .map(Experiment::id).orElse(experiments.keySet().iterator().next());
            experiments.remove(removable);
        }
        while (budgets.size() > 128) budgets.remove(budgets.keySet().iterator().next());
    }

    private static Experiment lifecycle(Experiment value, Status status,
                                        String actionId, String evidence,
                                        long actionTick, long deadlineTick,
                                        long baselineRevision, int attempts,
                                        FactSnapshot baseline,
                                        boolean probeSucceeded) {
        return new Experiment(value.id(), value.subject(), value.hypothesis(),
                value.probeTool(), value.probeArguments(), value.expectedSubject(),
                value.expectedPredicate(), value.expectedValue(), value.risk(), status,
                actionId, evidence, value.createdTick(), actionTick, deadlineTick,
                baselineRevision, attempts, value.contextKey(), value.compensationTool(),
                value.compensationArguments(), value.reversible(), value.estimatedCost(),
                value.estimatedInformationGain(), baseline, probeSucceeded,
                value.compensationAttempted(), value.compensationSucceeded(),
                value.environmentFingerprint());
    }

    private static Experiment withProbeSucceeded(Experiment value) {
        return lifecycle(value, value.status(), value.actionId(), value.evidence(),
                value.actionTick(), value.deadlineTick(), value.baselineWorldRevision(),
                value.attempts(), value.baseline(), true);
    }

    private static Experiment withCompensation(Experiment value, boolean attempted,
                                               boolean succeeded, String evidence) {
        return new Experiment(value.id(), value.subject(), value.hypothesis(),
                value.probeTool(), value.probeArguments(), value.expectedSubject(),
                value.expectedPredicate(), value.expectedValue(), value.risk(), value.status(),
                value.actionId(), evidence, value.createdTick(), value.actionTick(),
                value.deadlineTick(), value.baselineWorldRevision(), value.attempts(),
                value.contextKey(), value.compensationTool(), value.compensationArguments(),
                value.reversible(), value.estimatedCost(), value.estimatedInformationGain(),
                value.baseline(), value.probeSucceeded(), attempted, succeeded,
                value.environmentFingerprint());
    }

    private static int defaultCost(String tool, Risk risk) {
        return MEDIUM_RISK_TOOLS.contains(tool) || risk == Risk.MEDIUM ? 4 : 1;
    }

    private static String expected(Experiment value) {
        return value.expectedSubject() + " " + value.expectedPredicate()
                + "=" + value.expectedValue();
    }

    private static boolean valueMatches(String actual, String expected) {
        return "*".equals(expected) || normalize(actual, "").equalsIgnoreCase(
                normalize(expected, ""));
    }

    private static String compact(String value, int limit) {
        String normalized = normalize(value, "none").replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String canonical(String value) {
        return normalize(value, "unknown").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
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
