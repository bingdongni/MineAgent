package com.mineagent.engine.exploration;

import com.google.gson.JsonParser;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.world.SemanticWorldModel;
import com.mineagent.engine.planning.PlanGraph;

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
                             EnvironmentFingerprint environmentFingerprint,
                             List<PlanGraph.GoalCondition> prerequisites) {
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
            estimatedCost = Math.max(defaultCost(probeTool, risk), Math.min(12, estimatedCost));
            estimatedInformationGain = unit(estimatedInformationGain);
            baseline = baseline == null ? FactSnapshot.absent() : baseline;
            environmentFingerprint = environmentFingerprint == null
                    ? EnvironmentFingerprint.unknown() : environmentFingerprint;
            prerequisites = LearningConditions.checked(prerequisites);
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
            estimatedCost = Math.max(defaultCost(tool, risk), Math.min(12, estimatedCost));
            estimatedInformationGain = unit(estimatedInformationGain);
        }
    }

    public record Proposal(boolean accepted, Experiment experiment, String message) {}
    public record Recommendation(ProbeCandidate candidate, double score, String message) {}
    public record Budget(String subject, int remaining, int probes, long updatedTick,
                         EnvironmentFingerprint fingerprint, int recoveryRefills) {
        public Budget(String subject, int remaining, int probes, long updatedTick, EnvironmentFingerprint fingerprint) {
            this(subject, remaining, probes, updatedTick, fingerprint, 0);
        }
        public Budget {
            subject = normalize(subject, "unknown");
            remaining = Math.max(0, Math.min(MAX_BUDGET, remaining));
            recoveryRefills = Math.max(0, Math.min(1, recoveryRefills));
            probes = Math.max(0, Math.min(MAX_PROBES_PER_SUBJECT + 2, probes));
            updatedTick = Math.max(0L, updatedTick);
            fingerprint = fingerprint == null
                    ? EnvironmentFingerprint.unknown() : fingerprint;
        }
    }
    public record State(List<Experiment> experiments,
                        MechanismKnowledgeBase.State knowledge,
                        List<Budget> budgets, long revision,
                        PredictiveLearning.State learning, com.mineagent.engine.memory.TaskEpisodeMemory.State tasks) {
        public State(List<Experiment> experiments, MechanismKnowledgeBase.State knowledge,
                     List<Budget> budgets, long revision) {
            this(experiments, knowledge, budgets, revision, null, null);
        }
        public State(List<Experiment> experiments, MechanismKnowledgeBase.State knowledge,
                     List<Budget> budgets, long revision, PredictiveLearning.State learning) {
            this(experiments, knowledge, budgets, revision, learning, null);
        }
    }

    public record LearningOpportunity(String subject, String reason, double priority,
                                      int remainingBudget) {}

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
            "inspect_block", "inspect_entity", "inspect_item", "inspect_block_storage", "inspect_gui",
            "lookup_recipe", "resolve_need", "interact_at", "interact_entity",
            "close_gui", "transfer_items", "transfer_port", "craft", "equip_item");
    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "interact_at", "interact_entity", "transfer_items", "transfer_port", "craft", "equip_item");
    private static final Set<String> SAFE_COMPENSATION_TOOLS = Set.of(
            "close_gui", "transfer_items", "transfer_port", "equip_item");

    private final SemanticWorldModel worldModel;
    private final EvidenceSink evidenceSink;
    private final CompensationDispatcher compensationDispatcher;
    private final MechanismKnowledgeBase knowledgeBase;
    private final AdaptationRuntime adaptationRuntime;
    private final PredictiveLearning learning = new PredictiveLearning();
    private final com.mineagent.engine.memory.TaskEpisodeMemory taskMemory = new com.mineagent.engine.memory.TaskEpisodeMemory();
    private final LinkedHashMap<String, Experiment> experiments = new LinkedHashMap<>();
    private final LinkedHashMap<String, Budget> budgets = new LinkedHashMap<>();
    private long revision;
    private java.util.function.Consumer<String> invalidationSink = id -> {};

    public synchronized void onInvalidation(java.util.function.Consumer<String> sink) {
        invalidationSink = java.util.Objects.requireNonNull(sink);
    }

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
        return propose(subject, hypothesis, probeTool, probeArguments, expectedSubject,
                expectedPredicate, expectedValue, risk, contextKey, reversible,
                compensationTool, compensationArguments, estimatedCost,
                estimatedInformationGain, gameTick, List.of());
    }

    public synchronized Proposal propose(String subject, String hypothesis,
                                         String probeTool, String probeArguments,
                                         String expectedSubject, String expectedPredicate,
                                         String expectedValue, Risk risk, String contextKey,
                                         boolean reversible, String compensationTool,
                                         String compensationArguments, int estimatedCost,
                                         double estimatedInformationGain, long gameTick,
                                         List<PlanGraph.GoalCondition> prerequisites) {
        List<PlanGraph.GoalCondition> conditions;
        try { conditions = LearningConditions.checked(prerequisites); }
        catch (IllegalArgumentException invalid) { return new Proposal(false, null, invalid.getMessage()); }
        if (expectedSubject != null) {
            var before = worldModel.find(expectedSubject, normalize(expectedPredicate, "outcome"), gameTick);
            if (!expectedSubject.startsWith("tool:") && before.isPresent()
                    && before.get().confidenceAt(gameTick) >= 0.7) {
                var guarded = new ArrayList<>(conditions);
                var guard = new PlanGraph.GoalCondition(expectedSubject, normalize(expectedPredicate, "outcome"),
                        before.get().value(), PlanGraph.Comparison.EQUALS, 0.7);
                if (!guarded.contains(guard)) guarded.add(guard);
                try { conditions = LearningConditions.checked(guarded); }
                catch (IllegalArgumentException invalid) { return new Proposal(false, null, invalid.getMessage()); }
            }
            var identity = worldModel.find(expectedSubject, "block_id", gameTick);
            if (identity.isPresent() && identity.get().confidenceAt(gameTick) >= 0.7) {
                var guarded = new ArrayList<>(conditions);
                var guard = new PlanGraph.GoalCondition(expectedSubject, "block_id", identity.get().value(),
                        PlanGraph.Comparison.EQUALS, 0.7);
                if (!guarded.contains(guard)) guarded.add(guard);
                try { conditions = LearningConditions.checked(guarded); }
                catch (IllegalArgumentException invalid) { return new Proposal(false, null, invalid.getMessage()); }
            }
        }
        try { conditions = identityConditions(conditions, expectedSubject, gameTick); }
        catch (IllegalArgumentException invalid) { return new Proposal(false, null, invalid.getMessage()); }
        String blocker = LearningConditions.blocker(conditions, worldModel, gameTick);
        if (blocker != null) return new Proposal(false, null, blocker);
        String tool = normalize(probeTool, "").toLowerCase(Locale.ROOT);
        String compensation = blankToNull(compensationTool);
        if (subject == null || subject.isBlank() || subject.length() > 256
                || hypothesis == null || hypothesis.isBlank() || hypothesis.length() > 512
                || probeArguments == null || probeArguments.length() > 2_048
                || (expectedSubject != null && expectedSubject.length() > 256)
                || (expectedPredicate != null && expectedPredicate.length() > 128)
                || (expectedValue != null && expectedValue.length() > 256)
                || contextKey == null || contextKey.isBlank() || contextKey.length() > 256
                || !PredictiveLearning.supportsArguments(probeArguments)
                || !isJsonObject(compensationArguments == null ? "{}" : compensationArguments)) {
            return new Proposal(false, null,
                    "Experiment text, context, or JSON arguments are invalid or too large");
        }
        if (!tool.matches("[a-z][a-z0-9_]{0,63}") || !SAFE_PROBE_TOOLS.contains(tool)) {
            return new Proposal(false, null,
                    "Probe tool is not permitted for controlled exploration: " + probeTool);
        }
        if ("transfer_port".equals(tool)) {
            try {
                var bound = JsonParser.parseString(probeArguments).getAsJsonObject();
                if (expectedSubject == null || !expectedSubject.matches("block:[a-z0-9_.-]+:[a-z0-9_./-]+:-?[0-9]+,-?[0-9]+,-?[0-9]+"))
                    return new Proposal(false,null,"A port experiment requires an observed block effect target");
                {
                    String[] coordinates=expectedSubject.substring(expectedSubject.lastIndexOf(':')+1).split(",");
                    int axis=0;for(String field:List.of("x","y","z"))
                        if(bound.get(field).getAsBigDecimal().intValueExact()!=Integer.parseInt(coordinates[axis++]))
                            return new Proposal(false,null,"Port action and effect must address the same observed block");
                    String dimension = expectedSubject.substring(6, expectedSubject.lastIndexOf(':'));
                    if (bound.has("dimension") && !bound.get("dimension").getAsString().equals(dimension))
                        return new Proposal(false, null, "Port action and observed target dimension differ");
                    bound.addProperty("dimension", dimension);
                    var face = worldModel.find(expectedSubject, "capability_face", gameTick)
                            .filter(fact -> fact.confidenceAt(gameTick) >= 0.7).orElse(null);
                    if (face == null) return new Proposal(false, null, "Observe the public port face before learning a transfer");
                    if (bound.has("face") && !bound.get("face").getAsString().equals(face.value()))
                        return new Proposal(false, null, "Port action must use the observed face");
                    bound.addProperty("face", face.value());
                }
                int amount = bound.get("amount").getAsBigDecimal().intValueExact();
                if (amount < 1 || amount > 64) throw new IllegalArgumentException();
                int slot=bound.get("inventory_slot").getAsBigDecimal().intValueExact();
                if(slot<0||slot>35||!bound.get("item_id").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        ||!Set.of("into_block","from_block").contains(bound.get("direction").getAsString()))
                    throw new IllegalArgumentException();
                if ("into_block".equals(bound.get("direction").getAsString())) {
                    var guarded = new ArrayList<>(conditions);
                    guarded.add(new PlanGraph.GoalCondition("inventory:" + bound.get("item_id").getAsString(), "count",
                            Integer.toString(amount), PlanGraph.Comparison.AT_LEAST, 0.7));
                    conditions = LearningConditions.checked(guarded);
                    String inputBlocker = LearningConditions.blocker(conditions, worldModel, gameTick);
                    if (inputBlocker != null) return new Proposal(false, null, inputBlocker);
                }
                probeArguments = bound.toString();
                if (!"transfer_port".equals(compensation))
                    return new Proposal(false,null,"A learned port transfer requires its exact reverse port compensation");
                {
                    var undo = JsonParser.parseString(compensationArguments).getAsJsonObject();
                    for (String field : List.of("x","y","z","inventory_slot","item_id","amount"))
                        if (!bound.has(field) || !bound.get(field).equals(undo.get(field)))
                            return new Proposal(false,null,"Port compensation must restore the same target, item, amount and carried slot");
                    String reverse="into_block".equals(bound.get("direction").getAsString())?"from_block":"into_block";
                    if (!undo.has("direction") || !reverse.equals(undo.get("direction").getAsString()))
                        return new Proposal(false,null,"Port compensation must reverse the observed transfer direction");
                    for (String field : List.of("face", "dimension")) if (bound.has(field)) undo.add(field, bound.get(field).deepCopy());
                    compensationArguments = undo.toString();
                }
            } catch (RuntimeException invalid) { return new Proposal(false, null, "Invalid observed port-transfer contract"); }
        }
        if (expectedSubject != null && (expectedSubject.startsWith("block:") || expectedSubject.startsWith("entity:"))) {
            String identityPredicate = expectedSubject.startsWith("block:") ? "block_id" : "entity_type";
            var identity = conditions.stream().filter(condition -> condition.subject().equals(expectedSubject)
                    && condition.predicate().equals(identityPredicate) && condition.comparison() == PlanGraph.Comparison.EQUALS).findFirst();
            if (identity.isPresent() && !canonical(subject).equals(canonical(identity.get().value())))
                return new Proposal(false, null, "Use the observed registered mechanism ID as subject: " + identity.get().value());
        }
        if ("interact_entity".equals(tool) && expectedSubject != null && expectedSubject.startsWith("entity:")
                && conditions.stream().anyMatch(condition -> condition.predicate().equals("entity_type") && condition.subject().equals(expectedSubject))) {
            try {
                String uuid = UUID.fromString(expectedSubject.substring(7)).toString();
                var arguments = JsonParser.parseString(probeArguments).getAsJsonObject();
                int entityId = arguments.get("entity_id").getAsBigDecimal().intValueExact();
                var observedId = worldModel.find(expectedSubject,"entity_id",gameTick)
                        .filter(fact -> fact.confidenceAt(gameTick)>=0.7).orElse(null);
                if (observedId == null || !Integer.toString(entityId).equals(observedId.value()))
                    return new Proposal(false,null,"Observe the actor's current runtime entity_id before probing");
                if (arguments.has("expected_uuid") && !uuid.equalsIgnoreCase(arguments.get("expected_uuid").getAsString()))
                    return new Proposal(false,null,"Probe UUID does not match the observed target");
                arguments.addProperty("expected_uuid",uuid); probeArguments = arguments.toString();
            } catch (RuntimeException invalid) { return new Proposal(false,null,"Invalid observed actor target or entity_id"); }
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
        if (destructiveProbe(tool, probeArguments)) {
            return new Proposal(false, null,
                    "Attack interactions have no generic compensation; use owner-directed normal actions instead");
        }
        if (activeExperiment() != null) {
            return new Proposal(false, activeExperiment(),
                    "Finish or abort the active experiment before arming another");
        }
        int cost = Math.max(defaultCost(tool, normalizedRisk), Math.min(MAX_BUDGET, estimatedCost));
        Budget budget = budgetFor(subject, gameTick);
        if (budget.probes() >= MAX_PROBES_PER_SUBJECT + budget.recoveryRefills() * 2 || budget.remaining() < cost) {
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
                knowledgeBase.currentFingerprint(), conditions);
        experiments.put(id, experiment);
        budgets.put(canonical(subject), new Budget(subject,
                budget.remaining() - cost, budget.probes() + 1, gameTick,
                knowledgeBase.currentFingerprint(), budget.recoveryRefills()));
        revision++;
        trim();
        return new Proposal(true, experiment,
                "Experiment armed; call " + tool + " once with the exact proposed arguments");
    }

    /** Rank caller-supplied black-box probes by information per cost and safety. */
    public synchronized Recommendation recommend(Collection<ProbeCandidate> candidates,
                                                  String subject, long gameTick) {
        Budget budget = budgetFor(subject, gameTick);
        if (budget.probes() >= MAX_PROBES_PER_SUBJECT + budget.recoveryRefills() * 2)
            return new Recommendation(null, 0.0, "Exploration probe allowance exhausted for " + subject);
        ProbeCandidate best = candidates == null ? null : candidates.stream()
                .limit(64)
                .filter(candidate -> validCandidate(candidate, budget))
                .max(Comparator.comparingDouble(candidate -> probeScore(candidate, subject, gameTick)))
                .orElse(null);
        if (best == null) return new Recommendation(null, 0.0,
                "No candidate fits the remaining reversible exploration budget");
        return new Recommendation(best, probeScore(best, subject, gameTick),
                "Empirical prediction uncertainty and learning progress override cold-start estimates when evidence exists; verify one probe only");
    }

    public synchronized PredictiveLearning.Prediction predict(String subject, String tool,
                                                               String arguments, String predicate,
                                                               String before) {
        return learning.predict(subject, tool, arguments, predicate, before,
                knowledgeBase.currentFingerprint().toString());
    }

    public synchronized PredictiveLearning.Plan plan(String subject, String predicate,
                                                      String before, String goal, int maxSteps) {
        return learning.plan(subject, predicate, before, goal,
                knowledgeBase.currentFingerprint().toString(), maxSteps);
    }

    /** A bounded curriculum of observed knowledge gaps; proposals do not dispatch actions. */
    public synchronized List<LearningOpportunity> curriculum(String query, long gameTick, int limit) {
        String objective = query == null ? "" : query.trim();
        return knowledgeBase.exportState().profiles().stream()
                .filter(profile -> knowledgeBase.novelty(profile.subject())
                        != MechanismKnowledgeBase.Novelty.VERIFIED)
                .filter(profile -> objective.isBlank()
                        || com.mineagent.engine.memory.TextSimilarity.score(objective,
                                profile.subject() + " " + profile.attributes()) >= 0.20)
                .map(profile -> {
                    double relevance = objective.isBlank() ? 0.25
                            : com.mineagent.engine.memory.TextSimilarity.score(objective,
                                    profile.subject() + " " + profile.attributes());
                    boolean contradicted = knowledgeBase.rules().stream().anyMatch(rule ->
                            rule.subject().equals(profile.subject())
                                    && (rule.status() == MechanismKnowledgeBase.RuleStatus.REFUTED
                                    || rule.status() == MechanismKnowledgeBase.RuleStatus.STALE));
                    Budget budget = budgetFor(profile.subject(), gameTick);
                    return new LearningOpportunity(profile.subject(), contradicted
                            ? "Revalidate a contradicted or changed mechanism"
                            : "Test one missing mechanism rule, then transfer to an independent setup",
                            relevance + (contradicted ? 0.75 : 0.25), budget.remaining());
                })
                .filter(opportunity -> opportunity.remainingBudget() > 0
                        && (objective.isBlank() || opportunity.priority() > 0.25))
                .sorted(Comparator.comparingDouble(LearningOpportunity::priority).reversed()
                        .thenComparing(LearningOpportunity::subject))
                .limit(Math.max(0, Math.min(5, limit))).toList();
    }

    /** Correlate the next exact matching tool call; unrelated actions stay untouched. */
    public synchronized String onToolDispatched(String actionId, String toolName,
                                                String arguments, long gameTick) {
        Experiment active = activeExperiment();
        if (active == null || active.status() != Status.ARMED
                || !active.probeTool().equals(toolName)
                || !sameJsonObject(active.probeArguments(), arguments)) return null;
        String blocker = LearningConditions.blocker(active.prerequisites(), worldModel, gameTick);
        if (blocker != null) {
            finish(active, Status.INCONCLUSIVE, "Prerequisites changed before dispatch: " + blocker, gameTick);
            return null;
        }
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
            if ("transfer_port".equals(toolName)) {
                try {
                    var root = JsonParser.parseString(evidence).getAsJsonObject();
                    if (root.has("observation")) projectBlockObservation("inspect_block", root.get("observation").toString(), actionId, gameTick);
                } catch (RuntimeException unavailable) { /* a transfer without an observation cannot prove a block effect */ }
            }
            ActorObservation.project(toolName, evidence, actionId, gameTick, worldModel);
            projectBlockObservation(toolName, evidence, actionId, gameTick);
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

    private void projectBlockObservation(String tool, String raw, String actionId, long tick) {
        if (!"inspect_block".equals(tool) || raw == null || raw.length() > 24_000) return;
        try {
            var root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("fact_subject")) return;
            String subject = root.get("fact_subject").getAsString();
            if (!subject.startsWith("block:") || subject.length() > 256) return;
            java.util.Set<String> observedProperties = new java.util.HashSet<>();
            if (root.has("properties") && root.get("properties").isJsonObject())
                root.getAsJsonObject("properties").keySet().forEach(name -> observedProperties.add("property." + name));
            boolean typeChanged = root.has("block_id") && worldModel.find(subject, "block_id", tick)
                    .map(fact -> !fact.value().equals(root.get("block_id").getAsString())).orElse(false);
            boolean sampledCapabilities = root.has("capabilities") || root.has("capability_status");
            var reportedCapabilities = new java.util.HashSet<String>();
            if (root.has("capabilities") && root.get("capabilities").isJsonObject()) {
                var caps=root.getAsJsonObject("capabilities");
                if(caps.has("fields") && caps.get("fields").isJsonObject()
                        && caps.has("channels") && caps.get("channels").isJsonObject()) {
                    var channels=caps.getAsJsonObject("channels");
                    caps.getAsJsonObject("fields").entrySet().stream().limit(96).forEach(entry->{
                        String channel=entry.getKey().split("\\.",2)[0];
                        if(channels.has(channel) && "available".equals(channels.get(channel).getAsString()))
                            reportedCapabilities.add("capability."+entry.getKey());
                    });
                }
            }
            for (var old : worldModel.factsForSubject(subject, tick)) {
                if ((old.predicate().startsWith("property.") && !observedProperties.contains(old.predicate()))
                        || (old.predicate().startsWith("capability.") && (typeChanged
                        || (sampledCapabilities && !reportedCapabilities.contains(old.predicate()))))) {
                    worldModel.retract(subject, old.predicate(), tool, actionId, tick, false);
                }
            }
            if (root.has("block_id")) worldModel.observe(subject, "block_id", root.get("block_id").getAsString(),
                    null, 0.95, tool, actionId, tick, 400L, false);
            if (root.has("dimension")) worldModel.observe(subject,"dimension",root.get("dimension").getAsString(),
                    null,0.95,tool,actionId,tick,400L,false);
            if (root.has("capability_face")) worldModel.observe(subject,"capability_face",root.get("capability_face").getAsString(),
                    null,0.95,tool,actionId,tick,100L,false);
            if (root.has("properties") && root.get("properties").isJsonObject()) {
                root.getAsJsonObject("properties").entrySet().stream().limit(24)
                        .filter(entry -> entry.getValue().isJsonPrimitive()).forEach(entry ->
                        worldModel.observe(subject, "property." + entry.getKey(), entry.getValue().getAsString(),
                                null, 0.95, tool, actionId, tick, 400L, false));
            }
            if (root.has("capabilities") && root.get("capabilities").isJsonObject()) {
                var caps = root.getAsJsonObject("capabilities");
                if (caps.has("fields") && caps.get("fields").isJsonObject()
                        && caps.has("channels") && caps.get("channels").isJsonObject()) {
                    var channels = caps.getAsJsonObject("channels");
                    caps.getAsJsonObject("fields").entrySet().stream().limit(96)
                            .filter(entry -> entry.getValue().isJsonPrimitive())
                            .filter(entry -> {
                                String channel = entry.getKey().split("\\.", 2)[0];
                                return channels.has(channel) && "available".equals(channels.get(channel).getAsString());
                            }).forEach(entry -> worldModel.observe(subject, "capability." + entry.getKey(),
                                    entry.getValue().getAsString(), null, 0.95, tool, actionId, tick, 100L, false));
                }
            }
        } catch (RuntimeException malformed) { /* malformed observations provide no invented facts */ }
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
        if (stale != null) grantRecoveryBudget(stale.subject(), gameTick);
        adaptationRuntime.invalidate(stale);
        publishRuleValidity(stale, gameTick);
    }

    public synchronized Experiment get(String id) {
        return id == null ? null : experiments.get(id);
    }

    public synchronized Collection<Experiment> all() {
        return List.copyOf(experiments.values());
    }

    public synchronized List<LearningReflection> reflect(String query, long tick, int limit) {
        String goal = query == null ? "" : query.trim();
        return experiments.values().stream().filter(Experiment::terminal)
                .filter(experiment -> goal.isBlank() || com.mineagent.engine.memory.TextSimilarity.score(goal,
                        experiment.subject() + " " + experiment.hypothesis()) >= 0.2)
                .sorted(Comparator.comparingLong(Experiment::deadlineTick).reversed())
                .limit(Math.max(0, Math.min(8, limit)))
                .map(experiment -> LearningReflection.from(experiment, worldModel, tick)).toList();
    }

    public record Transfer(boolean eligible, String ruleId, String skillName,
                           com.google.gson.JsonObject overrides, String message) {}

    /** Read-only transfer admission. Matching a name never establishes equivalent behavior. */
    public synchronized Transfer transfer(String ruleId, String target, String dimension, long tick) {
        var rule = knowledgeBase.rules().stream().filter(value -> value.id().equals(ruleId))
                .findFirst().orElseGet(() -> activateArchived(ruleId, tick));
        if (rule == null || !rule.reusable(knowledgeBase.currentFingerprint()) || rule.adapterSkill() == null)
            return new Transfer(false, ruleId, null, null, "Rule is not currently verified; obtain independent evidence first");
        boolean actor = target != null && target.startsWith("entity:");
        String identityPredicate = actor ? "entity_type" : "block_id";
        var identity = rule.prerequisites().stream().filter(condition ->
                condition.subject().equals(rule.expectedSubject()) && condition.predicate().equals(identityPredicate)
                        && condition.comparison() == PlanGraph.Comparison.EQUALS).findFirst().orElse(null);
        boolean currentDimension = actor ? worldModel.find(target,"dimension",tick)
                .filter(fact -> fact.confidenceAt(tick)>=0.7).map(fact->fact.value().equals(dimension)).orElse(false)
                : target != null && target.startsWith("block:" + dimension + ":");
        if (identity == null || !currentDimension)
            return new Transfer(false, ruleId, null, null, "A current-dimension target and observed source identity are required");
        var guards = rule.prerequisites().stream().map(condition -> new PlanGraph.GoalCondition(
                condition.subject().equals(rule.expectedSubject()) ? target : condition.subject(),
                condition.predicate(), condition.value(), condition.comparison(), condition.minimumConfidence())).toList();
        String blocker = LearningConditions.blocker(guards, worldModel, tick);
        if (blocker != null) return new Transfer(false, ruleId, null, null, blocker);
        try {
            var original = JsonParser.parseString(rule.probeArguments()).getAsJsonObject();
            com.google.gson.JsonObject bindings = new com.google.gson.JsonObject();
            if (actor) {
                if (!original.has("entity_id") || !original.has("expected_uuid")) throw new IllegalArgumentException();
                var runtimeId = worldModel.find(target,"entity_id",tick).filter(fact->fact.confidenceAt(tick)>=0.7).orElseThrow();
                int entityId = Integer.parseInt(runtimeId.value());
                if (entityId < 0) throw new IllegalArgumentException();
                bindings.addProperty("target_entity_id",entityId);
                bindings.addProperty("target_uuid",UUID.fromString(target.substring(7)).toString());
            } else {
                String[] coordinates = target.substring(target.lastIndexOf(':') + 1).split(",", -1);
                if (coordinates.length != 3) throw new IllegalArgumentException();
                int i = 0;
                for (String axis : List.of("x", "y", "z")) {
                    if (!original.has(axis)) throw new IllegalArgumentException();
                    bindings.addProperty("target_" + axis, Integer.parseInt(coordinates[i++]));
                }
            }
            bindings.addProperty("effect_subject", target);
            com.google.gson.JsonObject overrides = new com.google.gson.JsonObject();
            overrides.add("bindings", bindings);
            return new Transfer(true, ruleId, rule.adapterSkill(), overrides,
                    "Observed identity and conditions match; execute_skill will recheck conditions and verify the new target's effect");
        } catch (RuntimeException invalid) {
            return new Transfer(false, ruleId, null, null, "This rule does not expose a complete observed target binding");
        }
    }

    public synchronized PredictiveLearning.Prediction predict(String subject, String tool,
            String arguments, String predicate, String before, String target,
            List<PlanGraph.GoalCondition> prerequisites, long tick) {
        prerequisites = identityConditions(prerequisites, target, tick);
        String blocker = LearningConditions.blocker(prerequisites, worldModel, tick);
        if (blocker != null) throw new IllegalArgumentException(blocker);
        return learning.predict(subject, tool, LearningConditions.roleArguments(arguments,target,prerequisites), predicate, before,
                LearningConditions.predictiveScope(knowledgeBase.currentFingerprint(), prerequisites, target, predicate));
    }

    public synchronized PredictiveLearning.Plan plan(String subject, String predicate, String before,
            String goal, int maxSteps, String target, List<PlanGraph.GoalCondition> prerequisites, long tick) {
        prerequisites = identityConditions(prerequisites, target, tick);
        String blocker = LearningConditions.blocker(prerequisites, worldModel, tick);
        if (blocker != null) throw new IllegalArgumentException(blocker);
        return learning.plan(subject, predicate, before, goal,
                LearningConditions.predictiveScope(knowledgeBase.currentFingerprint(), prerequisites, target, predicate), maxSteps);
    }

    private List<PlanGraph.GoalCondition> identityConditions(List<PlanGraph.GoalCondition> original, String target, long tick) {
        var conditions = new ArrayList<>(LearningConditions.checked(original));
        if (target != null) for (String predicate : List.of("block_id", "entity_type", "dimension", "capability_face")) {
            worldModel.find(target, predicate, tick).filter(fact -> fact.confidenceAt(tick) >= 0.7).ifPresent(fact -> {
                var guard = new PlanGraph.GoalCondition(target, predicate, fact.value(), PlanGraph.Comparison.EQUALS, 0.7);
                if (!conditions.contains(guard)) conditions.add(guard);
            });
        }
        return LearningConditions.checked(conditions);
    }

    public synchronized MechanismKnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    public com.mineagent.engine.memory.TaskEpisodeMemory taskMemory() { return taskMemory; }

    /** Called before body/tool admission, not after the side effect. */
    public synchronized String actionBlocker(String tool, String arguments, long tick) {
        Experiment active = activeExperiment();
        if (active == null || active.status() != Status.ARMED
                || !active.probeTool().equals(tool) || !sameJsonObject(active.probeArguments(), arguments)) return null;
        String blocker = LearningConditions.blocker(active.prerequisites(), worldModel, tick);
        if (blocker != null) finish(active, Status.INCONCLUSIVE, "Prerequisites changed: " + blocker, tick);
        return blocker;
    }

    public synchronized boolean hasRelevantNovelty(String query) {
        return knowledgeBase.hasRelevantNovelty(query);
    }

    public synchronized String summarizeForPrompt() {
        return summarizeForPrompt("");
    }

    /** Reactivate stored evidence only; execution still requires fresh bound target guards. */
    public synchronized MechanismKnowledgeBase.Rule activateArchived(String id, long tick) {
        var before = knowledgeBase.rules();
        var restored = knowledgeBase.restoreArchived(id).orElse(null);
        if (restored == null) return null;
        var ids = knowledgeBase.rules().stream().map(MechanismKnowledgeBase.Rule::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var old : before) if (!ids.contains(old.id())) {
            adaptationRuntime.invalidate(old);
            worldModel.observe("mechanism_rule:" + old.id(), "reusable", "false", null, 1,
                    "mechanism_activation", old.id(), tick, 0L, false);
        }
        adaptationRuntime.synchronize(restored); publishRuleValidity(restored, tick);
        return knowledgeBase.rules().stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(restored);
    }

    public synchronized MechanismKnowledgeBase.Rule seedTeamCandidate(MechanismKnowledgeBase.Rule source, long tick) {
        var before = knowledgeBase.rules();
        var candidate = knowledgeBase.seedCandidate(source, tick);
        if (candidate == null) return null;
        var ids = knowledgeBase.rules().stream().map(MechanismKnowledgeBase.Rule::id).collect(java.util.stream.Collectors.toSet());
        for (var old : before) if (!ids.contains(old.id())) {
            adaptationRuntime.invalidate(old);
            worldModel.observe("mechanism_rule:" + old.id(), "reusable", "false", null, 1,
                    "team_candidate", old.id(), tick, 0, false);
        }
        publishRuleValidity(candidate, tick); revision++;
        return candidate;
    }

    public synchronized void restorePlanEvidence(PlanGraph plan,long tick) {
        plan.refreshReadiness(worldModel,tick);var gap=plan.prerequisiteGap();if(gap==null)return;
        var relevant=com.mineagent.engine.planning.ExperienceRepairPlanner.relevantOperators(
                knowledgeBase.archivedRules().stream().filter(r->r.reusable(knowledgeBase.currentFingerprint())).toList(),gap.condition());
        int activated=0;
        var active=knowledgeBase.rules().stream().map(MechanismKnowledgeBase.Rule::id).collect(java.util.stream.Collectors.toSet());
        for(var rule:relevant)if(!active.contains(rule.id())&&activated++<8)activateArchived(rule.id(),tick);
    }

    public synchronized String experienceReference(String query) {
        for (var archived : knowledgeBase.recallArchived(query, 2)) activateArchived(archived.id(), 0L);
        StringBuilder out = new StringBuilder("contract_version=3\n");
        int count = 0;
        for (var rule : knowledgeBase.relevantRules(query, 4)) {
            if (!rule.reusable(knowledgeBase.currentFingerprint()) || rule.adapterSkill() == null) continue;
            String line = "rule=" + rule.id() + " subject=" + rule.subject() + " skill=" + rule.adapterSkill()
                    + " guards=" + rule.prerequisites().stream().map(PlanGraph.GoalCondition::describe).toList()
                    + " effect=" + rule.expectedPredicate() + "=" + rule.expectedValue() + "\n";
            if (out.length() + line.length() > 3_600) break;
            out.append(line); count++;
        }
        if (count == 0) out.append("No currently verified relevant rules. Observe or test before automatic reuse.\n");
        out.append(taskMemory.render(query, knowledgeBase.currentFingerprint().toString(), Math.min(1_800, 5_600 - out.length())));
        return out.toString();
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
            if (rule.reusable(knowledgeBase.currentFingerprint()) && rule.adapterSkill() != null) {
                out.append("- current_rule=").append(rule.id()).append(" subject=").append(rule.subject()).append(" reusable=true adapter=")
                        .append(rule.adapterSkill()).append("; see experience reference\n");
                continue;
            }
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
                knowledgeBase.exportState(), List.copyOf(budgets.values()), revision,
                learning.exportState(), taskMemory.exportState());
    }

    public synchronized void importState(State state) {
        knowledgeBase.archivedRules().forEach(rule->invalidationSink.accept(rule.id()));
        for (var old : knowledgeBase.rules()) {
            adaptationRuntime.invalidate(old);
            long tick = worldModel.find("mechanism_rule:" + old.id(), "reusable", 0L)
                    .map(SemanticWorldModel.SemanticFact::observedTick).orElse(old.updatedTick());
            worldModel.observe("mechanism_rule:" + old.id(), "reusable", "false", null, 1.0,
                    "mechanism_restore", old.id(), tick, 0L, false);
        }
        experiments.clear();
        budgets.clear();
        taskMemory.importState(state == null ? null : state.tasks());
        learning.importState(state != null && state.knowledge() != null && state.knowledge().contextVersion() == 2
                ? state.learning() : null);
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
        } else {
            // importState(null) is the explicit in-memory reset path used by
            // MemoryPersistence.clearAll(); rebuild adaptation from empty
            // knowledge rather than retaining stale mod rules.
            knowledgeBase.importState(null);
            revision++;
        }
        adaptationRuntime.rebuild(knowledgeBase.rules());
        knowledgeBase.rules().forEach(rule -> publishRuleValidity(rule, 0L));
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
        // Freeze the verified probe observation before publishing learner audit facts.
        // Internal status/adapter projections must not replace the actual outcome.
        var observed = status == Status.SUPPORTED || status == Status.REFUTED
                ? newFact(experiment, gameTick).orElse(null) : null;
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
        var previousRule=knowledgeBase.ruleFor(finished).orElse(null);
        boolean previouslyVerified = status == Status.REFUTED && previousRule!=null
                && previousRule.reusable(knowledgeBase.currentFingerprint());
        var previousRules = knowledgeBase.rules();
        MechanismKnowledgeBase.Rule rule = status == Status.ABORTED ? null
                : knowledgeBase.record(finished, status, finished.evidence(), gameTick);
        var contradicted = observed == null ? List.<MechanismKnowledgeBase.Rule>of()
                : knowledgeBase.recordObservedContradictions(finished, observed.value(), gameTick);
        for (var other : contradicted) {
            adaptationRuntime.invalidate(other); publishRuleValidity(other, gameTick);
        }
        boolean newlyContradictedAuthority = contradicted.stream().anyMatch(other -> previousRules.stream()
                .anyMatch(old -> old.id().equals(other.id()) && old.reusable(knowledgeBase.currentFingerprint())));
        var currentRuleIds = knowledgeBase.rules().stream().map(MechanismKnowledgeBase.Rule::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var previous : previousRules) if (!currentRuleIds.contains(previous.id())) {
            adaptationRuntime.invalidate(previous);
            worldModel.observe("mechanism_rule:" + previous.id(),"reusable","false",null,1,
                    "mechanism_eviction",finished.id(),gameTick,0,false);
        }
        if (previouslyVerified || newlyContradictedAuthority) grantRecoveryBudget(finished.subject(), gameTick);
        String adapter = adaptationRuntime.synchronize(rule);
        publishRuleValidity(rule, gameTick);
        if (adapter != null) {
            worldModel.observe("mechanism:" + finished.subject(), "adapter",
                    adapter, null, rule.confidence(), "adaptation_runtime",
                    finished.id(), gameTick, 0L, true);
        }
        // Execution errors and timeouts are not negative likelihood evidence.
        if (status == Status.SUPPORTED || status == Status.REFUTED) {
            if (observed != null && observed.eventSequence() > finished.baseline().eventSequence()
                    && observed.observedTick() >= finished.actionTick()) {
                boolean previousWindowInvalid = previousRule != null
                        && (previousRule.status() == MechanismKnowledgeBase.RuleStatus.STALE || previousRule.status() == MechanismKnowledgeBase.RuleStatus.REFUTED);
                boolean replacementWindow = (previousRule == null || previousRule.supports() == 0) && previousRules.stream().anyMatch(old ->
                        (old.status() == MechanismKnowledgeBase.RuleStatus.STALE || old.status() == MechanismKnowledgeBase.RuleStatus.REFUTED)
                                && MechanismKnowledgeBase.sameContract(old, experiment));
                if(status==Status.SUPPORTED && (previousWindowInvalid || replacementWindow || newlyContradictedAuthority)) {
                    learning.startRevalidation(finished.subject(),finished.probeTool(),
                            LearningConditions.roleArguments(finished.probeArguments(),finished.expectedSubject(),finished.prerequisites()),
                            finished.expectedPredicate(),finished.baseline().present()?finished.baseline().value():"<absent>",
                            LearningConditions.predictiveScope(finished.environmentFingerprint(),finished.prerequisites(),finished.expectedSubject(),finished.expectedPredicate()));
                }
                learning.observe(finished.id(), finished.subject(), finished.probeTool(),
                        LearningConditions.roleArguments(finished.probeArguments(),finished.expectedSubject(),finished.prerequisites()),
                        finished.expectedPredicate(), finished.baseline().present()
                                ? finished.baseline().value() : "<absent>",
                        observed.value(), PredictiveLearning.physicalContext(finished),
                        LearningConditions.predictiveScope(finished.environmentFingerprint(),
                                finished.prerequisites(), finished.expectedSubject(), finished.expectedPredicate()), gameTick);
            }
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

    private void publishRuleValidity(MechanismKnowledgeBase.Rule rule, long tick) {
        if (rule != null) {
            if (!rule.reusable(knowledgeBase.currentFingerprint())) invalidationSink.accept(rule.id());
            tick = Math.max(tick, worldModel.find("mechanism_rule:" + rule.id(), "reusable", 0L)
                    .map(SemanticWorldModel.SemanticFact::observedTick).orElse(0L));
            worldModel.observe("mechanism_rule:" + rule.id(), "reusable",
                Boolean.toString(rule.reusable(knowledgeBase.currentFingerprint())), null,
                1.0, "mechanism_explorer", rule.id(), tick, 0L, false);
        }
    }

    private void grantRecoveryBudget(String subject, long tick) {
        Budget budget = budgetFor(subject, tick);
        if (budget.recoveryRefills() == 0) budgets.put(canonical(subject), new Budget(subject,
                Math.min(MAX_BUDGET, budget.remaining() + 8), budget.probes(), tick,
                knowledgeBase.currentFingerprint(), 1));
    }

    /** A real data reload invalidates unknown dependencies conservatively, retaining audit history. */
    public synchronized void onRulesReloaded(long tick) {
        abort(null, "Game data rules were reloaded; revalidate affected mechanisms", tick);
        for (var rule : knowledgeBase.invalidateAll()) {
            adaptationRuntime.invalidate(rule); publishRuleValidity(rule, tick);
        }
        learning.importState(null); budgets.clear(); revision++;
    }

    private Budget budgetFor(String subject, long gameTick) {
        Budget old = budgets.get(canonical(subject));
        if (old == null || !old.fingerprint().compatible(
                knowledgeBase.currentFingerprint())
                || gameTick < old.updatedTick() || gameTick - old.updatedTick() >= BUDGET_RESET_TICKS) {
            return new Budget(subject, MAX_BUDGET, 0, gameTick,
                    knowledgeBase.currentFingerprint());
        }
        return old;
    }

    private boolean validCandidate(ProbeCandidate candidate, Budget budget) {
        if (candidate == null || candidate.risk() == Risk.HIGH
                || !SAFE_PROBE_TOOLS.contains(candidate.tool())
                || candidate.estimatedCost() > budget.remaining()
                || !PredictiveLearning.supportsArguments(candidate.arguments())
                || destructiveProbe(candidate.tool(), candidate.arguments())) return false;
        if (!MEDIUM_RISK_TOOLS.contains(candidate.tool())) return true;
        return candidate.risk() == Risk.MEDIUM && candidate.reversible()
                && candidate.compensationTool() != null
                && SAFE_COMPENSATION_TOOLS.contains(candidate.compensationTool())
                && !"craft".equals(candidate.tool());
    }

    private static boolean destructiveProbe(String tool, String arguments) {
        if (!"interact_at".equals(tool) && !"interact_entity".equals(tool)) return false;
        try {
            var button = JsonParser.parseString(arguments).getAsJsonObject().get("button");
            return button != null && button.isJsonPrimitive() && "attack".equalsIgnoreCase(button.getAsString());
        } catch (RuntimeException invalid) { return true; }
    }

    private double probeScore(ProbeCandidate candidate, String subject, long tick) {
        double riskPenalty = candidate.risk() == Risk.MEDIUM ? 0.25 : 0.0;
        double reversibility = candidate.reversible() ? 0.1 : 0.0;
        double value = learning.explorationValue(subject, candidate.tool(), candidate.arguments(),
                knowledgeBase.currentFingerprint().toString(), candidate.estimatedInformationGain());
        // Conditional trials live in their observed contract, not the unscoped fallback above.
        // Use only contracts applicable to this exact observed target and current before-state.
        double empirical = -1.0;
        var rules = knowledgeBase.rules().stream().filter(rule -> rule.subject().equals(subject)
                && rule.probeTool().equals(candidate.tool())
                && rule.fingerprint().compatible(knowledgeBase.currentFingerprint()))
                .sorted(Comparator.comparingLong(MechanismKnowledgeBase.Rule::updatedTick).reversed())
                .limit(64).toList();
        for (var rule : rules) {
            String target = candidateTarget(rule, candidate.arguments(), tick);
            if (target == null) continue;
            var guards = rule.prerequisites().stream().map(condition -> new PlanGraph.GoalCondition(
                    condition.subject().equals(rule.expectedSubject()) ? target : condition.subject(),
                    condition.predicate(), condition.value(), condition.comparison(), condition.minimumConfidence())).toList();
            if (LearningConditions.blocker(guards, worldModel, tick) != null) continue;
            String arguments = LearningConditions.roleArguments(candidate.arguments(), target, guards);
            String original = LearningConditions.roleArguments(rule.probeArguments(), rule.expectedSubject(), rule.prerequisites());
            if (!PredictiveLearning.abstractArguments(arguments).equals(PredictiveLearning.abstractArguments(original))) continue;
            String before = worldModel.find(target, rule.expectedPredicate(), tick)
                    .filter(fact -> fact.confidenceAt(tick) >= 0.7).map(SemanticWorldModel.SemanticFact::value).orElse("<absent>");
            var prediction = learning.predict(subject, candidate.tool(), arguments, rule.expectedPredicate(), before,
                    LearningConditions.predictiveScope(knowledgeBase.currentFingerprint(), guards, target, rule.expectedPredicate()));
            if (prediction.independentContexts() > 0)
                empirical = Math.max(empirical, PredictiveLearning.explorationValue(prediction));
        }
        if (empirical >= 0) value = empirical;
        return value / candidate.estimatedCost()
                + reversibility - riskPenalty;
    }

    private String candidateTarget(MechanismKnowledgeBase.Rule rule, String arguments, long tick) {
        try {
            var args = JsonParser.parseString(arguments).getAsJsonObject();
            if (rule.expectedSubject().startsWith("block:")) {
                String prefix = rule.expectedSubject().substring(0, rule.expectedSubject().lastIndexOf(':') + 1);
                return prefix + args.get("x").getAsBigDecimal().intValueExact() + ","
                        + args.get("y").getAsBigDecimal().intValueExact() + ","
                        + args.get("z").getAsBigDecimal().intValueExact();
            }
            if (rule.expectedSubject().startsWith("entity:")) {
                String target = args.has("expected_uuid") ? "entity:" + UUID.fromString(args.get("expected_uuid").getAsString())
                        : rule.expectedSubject();
                String runtimeId = Integer.toString(args.get("entity_id").getAsBigDecimal().intValueExact());
                return worldModel.find(target, "entity_id", tick).filter(fact -> fact.confidenceAt(tick) >= 0.7
                        && fact.value().equals(runtimeId)).isPresent() ? target : null;
            }
            return rule.expectedSubject();
        } catch (RuntimeException unavailableTarget) { return null; }
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
                value.environmentFingerprint(), value.prerequisites());
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
                value.environmentFingerprint(), value.prerequisites());
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
