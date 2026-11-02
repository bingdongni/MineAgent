package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Verifier-backed source of truth for long-horizon progress.
 *
 * <p>Planner and model outputs may propose a status, but only executor evidence
 * commits a node as verified. This prevents a textual todo list from claiming
 * completion while the body is still blocked or has failed.
 */
public final class PlanGraph {
    private static final int MAX_DRAFT_NODES = 64;
    private static final int MAX_PREREQUISITES = 16;
    public enum NodeStatus { PENDING, IN_PROGRESS, VERIFIED, INVALIDATED, BLOCKED }

    public enum GoalStatus { UNPLANNED, ACTIVE, VERIFYING, VERIFIED, BLOCKED }

    public enum Comparison { EQUALS, AT_LEAST, AT_MOST, PRESENT }

    public enum PrerequisiteStatus { UNKNOWN, CONTRADICTED }

    public record PrerequisiteGap(String nodeId, GoalCondition condition,
                                  PrerequisiteStatus status, String actualValue) {
        /** Observation values/ticks do not create a new paid wake for the same unmet requirement. */
        public String eventKey() { return nodeId + "|" + status + "|" + condition.describe(); }
        public String message() {
            return "Step '" + nodeId + "' requires " + condition.describe()
                    + (status == PrerequisiteStatus.UNKNOWN
                    ? "; obtain fresh observation evidence first"
                    : "; observed " + actualValue + "; repair the affected suffix or restore this prerequisite");
        }
    }

    /** A top-level acceptance condition grounded in the semantic world model. */
    public record GoalCondition(String subject, String predicate, String value,
                                Comparison comparison, double minimumConfidence) {
        public GoalCondition {
            subject = normalize(subject, "unknown");
            predicate = normalize(predicate, "observed");
            value = normalize(value, "*");
            comparison = comparison == null ? Comparison.EQUALS : comparison;
            minimumConfidence = Double.isFinite(minimumConfidence)
                    ? Math.max(0.0, Math.min(1.0, minimumConfidence)) : 0.6;
        }

        public boolean matches(SemanticWorldModel worldModel, long gameTick) {
            if (worldModel == null) return false;
            var fact = worldModel.find(subject, predicate, gameTick)
                    .filter(found -> found.confidenceAt(gameTick) >= minimumConfidence)
                    .orElse(null);
            if (fact == null) return false;
            if (comparison == Comparison.PRESENT) return true;
            if (comparison == Comparison.EQUALS) {
                return "*".equals(value) || fact.value().equalsIgnoreCase(value);
            }
            try {
                double actual = Double.parseDouble(fact.value());
                double expected = Double.parseDouble(value);
                if (!Double.isFinite(actual) || !Double.isFinite(expected)) return false;
                return comparison == Comparison.AT_LEAST
                        ? actual >= expected : actual <= expected;
            } catch (NumberFormatException notNumeric) {
                return false;
            }
        }

        public String describe() {
            return subject + " " + predicate + " "
                    + comparison.name().toLowerCase(java.util.Locale.ROOT)
                    + " " + value;
        }
    }

    public record Evidence(String source, String statement, boolean success,
                           long gameTick) {
        public Evidence {
            source = normalize(source, "executor");
            statement = normalize(statement, "No evidence detail");
            gameTick = Math.max(0L, gameTick);
        }
    }

    public record PlanNode(String id, String description, String successCriterion,
                           String priority, List<String> dependsOn,
                           NodeStatus status, int attempts,
                           String lastFailure, List<Evidence> evidence,
                           List<GoalCondition> prerequisites) {
        public PlanNode(String id, String description, String successCriterion,
                        String priority, List<String> dependsOn, NodeStatus status,
                        int attempts, String lastFailure, List<Evidence> evidence) {
            this(id, description, successCriterion, priority, dependsOn, status,
                    attempts, lastFailure, evidence, List.of());
        }
        public PlanNode {
            id = normalize(id, "step");
            description = normalize(description, "Unspecified step");
            successCriterion = normalize(successCriterion,
                    "A body task reports verified success");
            priority = normalize(priority, "medium");
            dependsOn = normalizeDependencies(dependsOn);
            status = status == null ? NodeStatus.PENDING : status;
            attempts = Math.max(0, attempts);
            lastFailure = blankToNull(lastFailure);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            prerequisites = checkedPrerequisites(prerequisites);
        }
    }

    public record DraftNode(String id, String description, String successCriterion,
                            String priority, List<String> dependsOn,
                            NodeStatus requestedStatus, List<GoalCondition> prerequisites) {
        public DraftNode(String id, String description, String successCriterion,
                         String priority, List<String> dependsOn, NodeStatus requestedStatus) {
            this(id, description, successCriterion, priority, dependsOn, requestedStatus, List.of());
        }
        public DraftNode {
            id = id == null ? null : id.trim();
            dependsOn = normalizeDependencies(dependsOn);
            prerequisites = checkedPrerequisites(prerequisites);
        }
    }

    public record State(String goal, List<PlanNode> nodes,
                        List<IntentContract.Constraint> constraints,
                        List<GoalCondition> goalConditions,
                        GoalStatus goalStatus, int repairCount,
                        long revision) {
        public State {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
            goalConditions = goalConditions == null ? List.of()
                    : goalConditions.stream().filter(java.util.Objects::nonNull).toList();
            goalStatus = goalStatus == null ? GoalStatus.UNPLANNED : goalStatus;
            repairCount = Math.max(0, repairCount);
            revision = Math.max(0L, revision);
        }
    }

    public record UpdateResult(List<String> warnings, long revision, boolean accepted) {}

    private final LinkedHashMap<String, PlanNode> nodes = new LinkedHashMap<>();
    private final Map<String, String> taskBindings = new LinkedHashMap<>();
    private final Map<String, String> toolBindings = new LinkedHashMap<>();
    /** Transient readiness; a checkpoint cannot restore stale permission to act. */
    private final Map<String, List<PrerequisiteGap>> prerequisiteGaps = new LinkedHashMap<>();
    private List<IntentContract.Constraint> constraints = List.of();
    private List<GoalCondition> goalConditions = List.of();
    private GoalStatus goalStatus = GoalStatus.UNPLANNED;
    private int repairCount;
    private String goal = "";
    private long revision;

    public synchronized UpdateResult replacePlan(String newGoal, List<DraftNode> drafts,
                                                  List<IntentContract.Constraint> newConstraints) {
        return replacePlan(newGoal, drafts, newConstraints, null);
    }

    public synchronized UpdateResult replacePlan(String newGoal, List<DraftNode> drafts,
                                                  List<IntentContract.Constraint> newConstraints,
                                                  List<GoalCondition> newGoalConditions) {
        List<String> warnings = new ArrayList<>();
        boolean sameGoal = normalize(newGoal, goal).equals(goal);
        LinkedHashMap<String, PlanNode> previousNodes = new LinkedHashMap<>(nodes);
        LinkedHashMap<String, DraftNode> validated = validateDrafts(drafts, warnings);
        for (DraftNode draft : validated.values()) {
            for (String dependency : draft.dependsOn()) {
                if (!validated.containsKey(dependency)) {
                    warnings.add("Step '" + draft.id() + "' depends on missing step '"
                            + dependency + "'");
                }
            }
        }
        if (containsCycle(validated)) warnings.add("Plan dependency graph contains a cycle");
        if (!warnings.isEmpty()) {
            // Replacing a valid active plan with a malformed partial graph is
            // worse than rejecting the update. The caller can repair the
            // complete draft using these deterministic diagnostics.
            return new UpdateResult(List.copyOf(warnings), revision, false);
        }

        LinkedHashMap<String, PlanNode> replacement = new LinkedHashMap<>();
        int inProgress = 0;
        if (drafts != null) {
            for (DraftNode draft : validated.values()) {
                PlanNode previous = previousNodes.get(draft.id());
                boolean sameSemantics = sameGoal && sameSemantics(previous, draft);
                List<Evidence> evidence = sameSemantics
                        ? previous.evidence() : List.of();
                NodeStatus requested = draft.requestedStatus() == null
                        ? NodeStatus.PENDING : draft.requestedStatus();
                NodeStatus committed = requested;
                if (requested == NodeStatus.VERIFIED
                        && evidence.stream().noneMatch(Evidence::success)) {
                    committed = NodeStatus.IN_PROGRESS;
                    warnings.add("Step '" + draft.id()
                            + "' was not marked verified because it has no successful executor evidence");
                }
                if (committed == NodeStatus.IN_PROGRESS && ++inProgress > 1) {
                    committed = NodeStatus.PENDING;
                    warnings.add("Only one step may control the body; step '"
                            + draft.id() + "' was kept pending");
                }
                replacement.put(draft.id(), new PlanNode(draft.id(), draft.description(),
                        draft.successCriterion(), draft.priority(), draft.dependsOn(), committed,
                        sameSemantics ? previous.attempts() : 0,
                        sameSemantics ? previous.lastFailure() : null, evidence, draft.prerequisites()));
            }
        }
        for (Map.Entry<String, PlanNode> entry : replacement.entrySet()) {
            PlanNode node = entry.getValue();
            if (node.status() == NodeStatus.IN_PROGRESS
                    && !dependenciesVerified(node.dependsOn(), replacement)) {
                entry.setValue(replace(node, NodeStatus.PENDING, node.attempts(),
                        node.lastFailure(), node.evidence()));
                warnings.add("Step '" + node.id()
                        + "' cannot start before its dependencies are verified");
            }
        }
        nodes.clear();
        nodes.putAll(replacement);
        prerequisiteGaps.clear();
        taskBindings.entrySet().removeIf(entry -> {
            PlanNode before = previousNodes.get(entry.getValue());
            PlanNode after = nodes.get(entry.getValue());
            return !sameGoal || before == null || after == null || !sameSemantics(before, after);
        });
        toolBindings.entrySet().removeIf(entry -> {
            PlanNode before = previousNodes.get(entry.getValue());
            PlanNode after = nodes.get(entry.getValue());
            return !sameGoal || before == null || after == null || !sameSemantics(before, after);
        });
        String previousGoal = goal;
        goal = normalize(newGoal, goal.isBlank() ? "Current owner goal" : goal);
        if (newConstraints != null) constraints = List.copyOf(newConstraints);
        if (newGoalConditions != null) goalConditions = List.copyOf(newGoalConditions);
        else if (!goal.equals(previousGoal)) goalConditions = List.of();
        repairCount = 0;
        refreshGoalStatus();
        revision++;
        return new UpdateResult(List.copyOf(warnings), revision, true);
    }

    /**
     * Replace only the unverified suffix of the current plan.
     *
     * <p>A rolling planner must not discard executor-confirmed milestones just
     * because its next tactical window changed. Dependencies may therefore
     * reference verified nodes omitted from the repair request. Unfinished
     * omitted nodes are intentionally removed; they belong to the invalid
     * suffix being replaced.
     */
    public synchronized UpdateResult repairPlan(String newGoal, List<DraftNode> drafts,
                                                 List<IntentContract.Constraint> newConstraints,
                                                 List<GoalCondition> newGoalConditions) {
        String requestedGoal = normalize(newGoal, goal);
        if (goal.isBlank() || !requestedGoal.equals(goal)) {
            return replacePlan(requestedGoal, drafts, newConstraints, newGoalConditions);
        }

        List<String> warnings = new ArrayList<>();
        LinkedHashMap<String, DraftNode> validated = validateDrafts(drafts, warnings);
        Set<String> available = new LinkedHashSet<>(validated.keySet());
        nodes.values().stream().filter(node -> node.status() == NodeStatus.VERIFIED)
                .map(PlanNode::id).forEach(available::add);
        if (available.size() > MAX_DRAFT_NODES) warnings.add("Plan may contain at most " + MAX_DRAFT_NODES + " nodes after repair");
        for (DraftNode draft : validated.values()) {
            for (String dependency : draft.dependsOn()) {
                if (!available.contains(dependency)) {
                    warnings.add("Step '" + draft.id()
                            + "' depends on missing or unverified step '" + dependency + "'");
                }
            }
        }

        // Build the combined graph used for cycle validation. Verified nodes
        // retain their original edges unless the repair explicitly replaces
        // that ID with different semantics.
        LinkedHashMap<String, DraftNode> combined = new LinkedHashMap<>();
        for (PlanNode node : nodes.values()) {
            if (node.status() == NodeStatus.VERIFIED
                    && !validated.containsKey(node.id())) {
                combined.put(node.id(), new DraftNode(node.id(), node.description(),
                        node.successCriterion(), node.priority(), node.dependsOn(),
                        NodeStatus.VERIFIED, node.prerequisites()));
            }
        }
        combined.putAll(validated);
        if (containsCycle(combined)) warnings.add("Plan dependency graph contains a cycle");
        if (!warnings.isEmpty()) {
            return new UpdateResult(List.copyOf(warnings), revision, false);
        }

        LinkedHashMap<String, PlanNode> previous = new LinkedHashMap<>(nodes);
        LinkedHashMap<String, PlanNode> replacement = new LinkedHashMap<>();
        for (PlanNode node : previous.values()) {
            if (node.status() == NodeStatus.VERIFIED
                    && !validated.containsKey(node.id())) {
                replacement.put(node.id(), node);
            }
        }

        int inProgress = 0;
        for (DraftNode draft : validated.values()) {
            PlanNode before = previous.get(draft.id());
            boolean same = sameSemantics(before, draft);
            List<Evidence> evidence = same ? before.evidence() : List.of();
            NodeStatus requested = draft.requestedStatus() == null
                    ? NodeStatus.PENDING : draft.requestedStatus();
            // Verified checkpoints are monotonic within one owner goal. A
            // repaired suffix cannot accidentally downgrade and repeat them.
            NodeStatus committed = same && before.status() == NodeStatus.VERIFIED
                    ? NodeStatus.VERIFIED : requested;
            if (committed == NodeStatus.VERIFIED
                    && evidence.stream().noneMatch(Evidence::success)) {
                committed = NodeStatus.PENDING;
                warnings.add("Step '" + draft.id()
                        + "' was not marked verified because it has no successful executor evidence");
            }
            if (committed == NodeStatus.IN_PROGRESS && ++inProgress > 1) {
                committed = NodeStatus.PENDING;
                warnings.add("Only one step may control the body; step '"
                        + draft.id() + "' was kept pending");
            }
            replacement.put(draft.id(), new PlanNode(draft.id(), draft.description(),
                    draft.successCriterion(), draft.priority(), draft.dependsOn(),
                    committed, same ? before.attempts() : 0,
                    same ? before.lastFailure() : null, evidence, draft.prerequisites()));
        }
        for (Map.Entry<String, PlanNode> entry : replacement.entrySet()) {
            PlanNode node = entry.getValue();
            if (node.status() == NodeStatus.IN_PROGRESS
                    && !dependenciesVerified(node.dependsOn(), replacement)) {
                entry.setValue(replace(node, NodeStatus.PENDING, node.attempts(),
                        node.lastFailure(), node.evidence()));
            }
        }

        nodes.clear();
        nodes.putAll(replacement);
        prerequisiteGaps.clear();
        taskBindings.clear();
        toolBindings.clear();
        if (newConstraints != null) constraints = List.copyOf(newConstraints);
        if (newGoalConditions != null) goalConditions = List.copyOf(newGoalConditions);
        repairCount++;
        goalStatus = GoalStatus.ACTIVE;
        refreshGoalStatus();
        revision++;
        return new UpdateResult(List.copyOf(warnings), revision, true);
    }

    public synchronized void bindTask(String taskId, String toolName,
                                      IntentContract contract, long gameTick) {
        if (taskId == null || taskId.isBlank()) return;
        PlanNode node = nodeForAction();
        if (node == null) {
            if (hasActivePlan()) return;
            String id = "body-" + Math.max(1L, revision + 1L);
            node = new PlanNode(id,
                    contract == null ? normalize(toolName, "Body task") : contract.goal(),
                    contract == null ? "Executor reports success" : contract.successCriterion(),
                    "medium", List.of(), NodeStatus.IN_PROGRESS, 1, null, List.of());
            nodes.put(id, node);
        } else {
            node = replace(node, NodeStatus.IN_PROGRESS, node.attempts() + 1,
                    null, node.evidence());
            nodes.put(node.id(), node);
        }
        taskBindings.put(taskId, node.id());
        goalStatus = GoalStatus.ACTIVE;
        revision++;
    }

    public synchronized void recordProgress(String taskId, TaskSnapshot snapshot, long gameTick) {
        String nodeId = taskBindings.get(taskId);
        PlanNode node = nodeId == null ? null : nodes.get(nodeId);
        if (node == null || snapshot == null) return;
        String statement = snapshot.summary();
        if (snapshot.isBlocked()) statement += "; blocked: " + snapshot.blockedReason();
        NodeStatus status = snapshot.isBlocked() ? NodeStatus.BLOCKED : NodeStatus.IN_PROGRESS;
        Evidence latest = node.evidence().isEmpty()
                ? null : node.evidence().getLast();
        String source = "task:" + taskId;
        if (node.status() == status
                && java.util.Objects.equals(node.lastFailure(), snapshot.blockedReason())
                && latest != null && !latest.success()
                && source.equals(latest.source())
                && statement.equals(latest.statement())) {
            // Progress heartbeats are useful to the UI, but identical evidence
            // must not mutate the verifier graph. A revision per heartbeat made
            // the rolling planner believe the world changed every tick.
            return;
        }
        List<Evidence> evidence = appendEvidence(node.evidence(),
                new Evidence(source, statement, false, gameTick));
        nodes.put(node.id(), replace(node, status, node.attempts(),
                snapshot.blockedReason(), evidence));
        refreshGoalStatus();
        revision++;
    }

    public synchronized void recordOutcome(String taskId, TaskState state,
                                           TaskSnapshot snapshot, String message,
                                           long gameTick) {
        String nodeId = taskBindings.remove(taskId);
        PlanNode node = nodeId == null ? null : nodes.get(nodeId);
        if (node == null) return;
        boolean success = state == TaskState.SUCCESS;
        String statement = snapshot != null && snapshot.evidence() != null
                ? snapshot.evidence() : normalize(message, state.name());
        List<Evidence> evidence = appendEvidence(node.evidence(),
                new Evidence("task:" + taskId, statement, success, gameTick));
        NodeStatus status = success ? NodeStatus.VERIFIED
                : state == TaskState.CANCELLED ? NodeStatus.INVALIDATED : NodeStatus.BLOCKED;
        nodes.put(node.id(), replace(node, status, node.attempts(),
                success ? null : message, evidence));
        refreshGoalStatus();
        revision++;
    }

    /** Bind all synchronous calls in one model response to the same ready node. */
    public synchronized void bindToolCall(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) return;
        PlanNode node = nodeForAction();
        if (node != null) toolBindings.put(toolCallId, node.id());
    }

    /**
     * Commit a synchronous world-changing tool result as executor evidence.
     * Query tools never call this path. It prevents verified inventory/GUI
     * actions such as craft or equip from leaving their plan node pending
     * forever merely because they complete inside one server callback.
     */
    public synchronized boolean recordToolOutcome(String toolCallId, String toolName,
                                                  boolean success, String evidence,
                                                  long gameTick) {
        String boundNodeId = toolBindings.remove(toolCallId);
        // An absent binding means no plan owned this call, or a newer plan
        // invalidated the old binding. Never let late evidence verify whatever
        // node happens to be current now.
        PlanNode node = boundNodeId == null ? null : nodes.get(boundNodeId);
        if (node == null) return false;
        List<Evidence> updated = appendEvidence(node.evidence(), new Evidence(
                "tool:" + normalize(toolName, "tool") + ":"
                        + normalize(toolCallId, "call"),
                normalize(evidence, success ? "verified" : "failed"),
                success, gameTick));
        boolean alreadyFailedInBatch = node.status() == NodeStatus.BLOCKED
                && node.lastFailure() != null;
        boolean verified = success && !alreadyFailedInBatch;
        nodes.put(node.id(), replace(node,
                verified ? NodeStatus.VERIFIED : NodeStatus.BLOCKED,
                node.attempts() + 1,
                verified ? null : alreadyFailedInBatch ? node.lastFailure() : evidence,
                updated));
        refreshGoalStatus();
        revision++;
        return true;
    }

    public synchronized PlanNode currentNode() {
        for (PlanNode node : nodes.values()) {
            if (node.status() == NodeStatus.IN_PROGRESS
                    && (ownsExecution(node) || prerequisitesReady(node))) return node;
        }
        return bestReady(NodeStatus.PENDING);
    }

    /**
     * Select a milestone that may own a newly admitted recovery action.
     * Blocked nodes are deliberately excluded from {@link #currentNode()} so
     * the rolling planner can request repair, but an alternative action issued
     * during that repair still belongs to the same milestone. Creating a new
     * anonymous body node here used to detach retries from their failure
     * history and made long plans grow without bound.
     */
    public synchronized PlanNode nodeForAction() {
        PlanNode ready = currentNode();
        if (ready != null) return ready;
        return bestReady(NodeStatus.BLOCKED);
    }

    /** Recheck live requirements, without rewriting historical achievements or executor ownership. */
    public synchronized void refreshReadiness(SemanticWorldModel worldModel, long gameTick) {
        prerequisiteGaps.clear();
        for (PlanNode node : nodes.values()) {
            if (node.prerequisites().isEmpty() || node.status() == NodeStatus.VERIFIED
                    || node.status() == NodeStatus.INVALIDATED || ownsExecution(node)) continue;
            List<PrerequisiteGap> gaps = new ArrayList<>();
            for (GoalCondition condition : node.prerequisites()) {
                var fact = worldModel == null ? null : worldModel.find(condition.subject(), condition.predicate(), gameTick)
                        .filter(found -> found.confidenceAt(gameTick) >= condition.minimumConfidence()).orElse(null);
                if (fact == null) gaps.add(new PrerequisiteGap(node.id(), condition, PrerequisiteStatus.UNKNOWN, null));
                else if (!condition.matches(worldModel, gameTick)) gaps.add(new PrerequisiteGap(node.id(), condition,
                        PrerequisiteStatus.CONTRADICTED, fact.value()));
            }
            prerequisiteGaps.put(node.id(), List.copyOf(gaps));
        }
    }

    public synchronized PrerequisiteGap prerequisiteGap() {
        for (PlanNode node : nodes.values()) {
            if ((node.status() != NodeStatus.PENDING && node.status() != NodeStatus.BLOCKED
                    && node.status() != NodeStatus.IN_PROGRESS) || ownsExecution(node)
                    || node.prerequisites().isEmpty() || !dependenciesVerified(node.dependsOn(), nodes)) continue;
            List<PrerequisiteGap> gaps = prerequisiteGaps.get(node.id());
            if (gaps == null) return new PrerequisiteGap(node.id(), node.prerequisites().getFirst(),
                    PrerequisiteStatus.UNKNOWN, null);
            if (!gaps.isEmpty()) return gaps.getFirst();
        }
        return null;
    }

    /** Server-side admission gate. Queries and normal survival scheduling remain outside this gate. */
    public synchronized String actionBlocker(SemanticWorldModel worldModel, long gameTick) {
        refreshReadiness(worldModel, gameTick);
        if (!hasActivePlan() || nodeForAction() != null) return null;
        PrerequisiteGap gap = prerequisiteGap();
        return gap == null ? "No dependency-ready milestone remains; observe or repair the plan before another world-changing action"
                : gap.message();
    }

    private boolean prerequisitesReady(PlanNode node) {
        return ownsExecution(node) || node.prerequisites().isEmpty() || prerequisiteGaps.containsKey(node.id())
                && prerequisiteGaps.get(node.id()).isEmpty();
    }

    private boolean ownsExecution(PlanNode node) {
        return taskBindings.containsValue(node.id()) || toolBindings.containsValue(node.id());
    }

    private PlanNode bestReady(NodeStatus status) {
        PlanNode best = null;
        Map<String, Integer> depths = new java.util.HashMap<>();
        for (PlanNode candidate : nodes.values()) {
            if (candidate.status() != status || !dependenciesVerified(candidate.dependsOn(), nodes)
                    || !prerequisitesReady(candidate)) continue;
            if (best == null || priorityRank(candidate) > priorityRank(best)
                    || priorityRank(candidate) == priorityRank(best)
                    && remainingDepth(candidate.id(), depths, new HashSet<>())
                    > remainingDepth(best.id(), depths, new HashSet<>())) best = candidate;
        }
        return best;
    }

    /** Unit-duration critical path estimate, not a claim about actual task latency. */
    private int remainingDepth(String id, Map<String, Integer> memo, Set<String> visiting) {
        if (memo.containsKey(id)) return memo.get(id);
        if (visiting.size() >= MAX_DRAFT_NODES || !visiting.add(id)) return 0;
        int depth = 1;
        for (PlanNode node : nodes.values()) {
            if (node.status() != NodeStatus.VERIFIED && node.status() != NodeStatus.INVALIDATED
                    && node.dependsOn().contains(id)) depth = Math.max(depth,
                    1 + remainingDepth(node.id(), memo, visiting));
        }
        visiting.remove(id);
        memo.put(id, depth);
        return depth;
    }

    private static int priorityRank(PlanNode node) {
        return switch (node.priority()) { case "high" -> 2; case "low" -> 0; default -> 1; };
    }

    public synchronized boolean hasActivePlan() {
        return !nodes.isEmpty() && goalStatus != GoalStatus.VERIFIED;
    }

    public synchronized int progressPercent() {
        if (nodes.isEmpty()) return 0;
        long completed = nodes.values().stream()
                .filter(node -> node.status() == NodeStatus.VERIFIED).count();
        int nodeProgress = (int) Math.round(completed * 100.0 / nodes.size());
        // A complete tactical window is not the same as an accepted strategic
        // goal when explicit world conditions remain unverified.
        return nodeProgress == 100 && goalStatus != GoalStatus.VERIFIED
                ? 99 : nodeProgress;
    }

    /** Refresh top-level acceptance after the latest server world snapshot. */
    public synchronized boolean verifyGoal(SemanticWorldModel worldModel,
                                           long gameTick) {
        GoalStatus before = goalStatus;
        if (before == GoalStatus.VERIFIED) return false;
        refreshGoalStatus();
        if (goalStatus == GoalStatus.VERIFYING
                && goalConditions.stream().allMatch(condition ->
                condition.matches(worldModel, gameTick))) {
            goalStatus = GoalStatus.VERIFIED;
        }
        if (goalStatus != before) revision++;
        return before != GoalStatus.VERIFIED && goalStatus == GoalStatus.VERIFIED;
    }

    public synchronized List<GoalCondition> goalConditions() {
        return goalConditions;
    }

    /** Only explicitly watched carried-item counts receive negative evidence from a full inventory scan. */
    public synchronized Set<String> watchedInventoryItems() {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        List<GoalCondition> conditions = new ArrayList<>(goalConditions);
        for (PlanNode node : nodes.values()) {
            if (node.status() != NodeStatus.VERIFIED && node.status() != NodeStatus.INVALIDATED) {
                conditions.addAll(node.prerequisites());
            }
        }
        for (GoalCondition condition : conditions) {
            if (condition.subject().startsWith("inventory:") && condition.predicate().equals("count")) {
                String id = condition.subject().substring("inventory:".length());
                if (!id.isBlank() && id.length() <= 256) items.add(id);
            }
        }
        return java.util.Collections.unmodifiableSet(items);
    }

    public synchronized GoalStatus goalStatus() {
        return goalStatus;
    }

    public synchronized String summarizeForPrompt() {
        if (nodes.isEmpty()) return "Plan: none";
        StringBuilder out = new StringBuilder("Plan goal: ").append(goal)
                .append(" (revision ").append(revision).append(", ")
                .append(progressPercent()).append("% verified, status=")
                .append(goalStatus.name().toLowerCase(java.util.Locale.ROOT))
                .append(", repairs=").append(repairCount).append(")\n");
        for (PlanNode node : nodes.values()) {
            out.append("- [").append(node.status()).append("] ")
                    .append(node.id()).append(": ").append(node.description());
            if (!node.dependsOn().isEmpty()) {
                out.append(" | depends_on=").append(String.join(",", node.dependsOn()));
            }
            if (node.lastFailure() != null) out.append(" | blocker=").append(node.lastFailure());
            if (!node.prerequisites().isEmpty()) out.append(" | prerequisites=")
                    .append(node.prerequisites().stream().limit(2).map(GoalCondition::describe).toList());
            out.append('\n');
        }
        if (!constraints.isEmpty()) {
            out.append("Constraints:\n");
            for (IntentContract.Constraint constraint : constraints) {
                out.append("- ").append(constraint.kind()).append(": ")
                        .append(constraint.description()).append('\n');
            }
        }
        if (!goalConditions.isEmpty()) {
            out.append("Goal acceptance conditions:\n");
            for (GoalCondition condition : goalConditions) {
                out.append("- ").append(condition.describe()).append('\n');
            }
        }
        PrerequisiteGap gap = prerequisiteGap();
        if (gap != null) out.append("Live prerequisite: ").append(gap.message()).append('\n');
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(goal, List.copyOf(nodes.values()), constraints,
                goalConditions, goalStatus, repairCount, revision);
    }

    public synchronized void importState(State state) {
        nodes.clear();
        taskBindings.clear();
        toolBindings.clear();
        prerequisiteGaps.clear();
        if (state == null) {
            // clearAll() deliberately uses the same import boundary as file
            // restore. Returning here would leave the old goal and verified
            // status alive in memory after its JSON checkpoint was deleted.
            goal = "";
            constraints = List.of();
            goalConditions = List.of();
            goalStatus = GoalStatus.UNPLANNED;
            repairCount = 0;
            revision++;
            return;
        }
        goal = normalize(state.goal(), "Restored goal");
        constraints = state.constraints() == null ? List.of() : List.copyOf(state.constraints());
        goalConditions = state.goalConditions() == null
                ? List.of() : List.copyOf(state.goalConditions());
        goalStatus = state.goalStatus() == null
                ? GoalStatus.UNPLANNED : state.goalStatus();
        repairCount = Math.max(0, state.repairCount());
        if (state.nodes() != null) {
            for (PlanNode node : state.nodes()) {
                if (node == null || node.id() == null || node.id().isBlank()) continue;
                NodeStatus status = node.status() == NodeStatus.IN_PROGRESS
                        ? NodeStatus.BLOCKED : node.status();
                String failure = node.status() == NodeStatus.IN_PROGRESS
                        ? "Execution was interrupted by a game restart" : node.lastFailure();
                nodes.put(node.id(), replace(node, status, node.attempts(),
                        failure, node.evidence()));
            }
        }
        // A completed goal is a durable checkpoint. Every other structural
        // state is recomputed after converting interrupted body ownership to a
        // blocker, so restart cannot restore a false RUNNING state.
        if (goalStatus != GoalStatus.VERIFIED) refreshGoalStatus();
        revision = Math.max(0L, state.revision()) + 1L;
    }

    public synchronized List<IntentContract.Constraint> constraints() {
        return constraints;
    }

    private void refreshGoalStatus() {
        if (nodes.isEmpty()) {
            goalStatus = GoalStatus.UNPLANNED;
            return;
        }
        boolean hasRequired = false;
        boolean allRequiredVerified = true;
        boolean blockedOnly = true;
        for (PlanNode node : nodes.values()) {
            if (node.status() == NodeStatus.INVALIDATED) continue;
            hasRequired = true;
            if (node.status() != NodeStatus.VERIFIED) allRequiredVerified = false;
            if (node.status() == NodeStatus.PENDING
                    || node.status() == NodeStatus.IN_PROGRESS) blockedOnly = false;
        }
        if (!hasRequired) {
            goalStatus = GoalStatus.BLOCKED;
        } else if (allRequiredVerified) {
            goalStatus = goalConditions.isEmpty()
                    ? GoalStatus.VERIFIED : GoalStatus.VERIFYING;
        } else {
            goalStatus = blockedOnly ? GoalStatus.BLOCKED : GoalStatus.ACTIVE;
        }
    }

    private static PlanNode replace(PlanNode node, NodeStatus status, int attempts,
                                    String failure, List<Evidence> evidence) {
        return new PlanNode(node.id(), node.description(), node.successCriterion(),
                node.priority(), node.dependsOn(), status, attempts, failure, evidence, node.prerequisites());
    }

    private static boolean dependenciesVerified(List<String> dependencies,
                                                Map<String, PlanNode> graph) {
        for (String dependency : dependencies == null ? List.<String>of() : dependencies) {
            PlanNode node = graph.get(dependency);
            if (node == null || node.status() != NodeStatus.VERIFIED) return false;
        }
        return true;
    }

    private static boolean sameSemantics(PlanNode previous, DraftNode draft) {
        return previous != null && draft != null
                && previous.description().equals(normalize(draft.description(), "Unspecified step"))
                && previous.successCriterion().equals(normalize(draft.successCriterion(),
                "A body task reports verified success"))
                && previous.dependsOn().equals(draft.dependsOn())
                && previous.prerequisites().equals(draft.prerequisites());
    }

    private static boolean sameSemantics(PlanNode first, PlanNode second) {
        return first != null && second != null
                && first.description().equals(second.description())
                && first.successCriterion().equals(second.successCriterion())
                && first.dependsOn().equals(second.dependsOn())
                && first.prerequisites().equals(second.prerequisites());
    }

    private static LinkedHashMap<String, DraftNode> validateDrafts(
            List<DraftNode> drafts, List<String> warnings) {
        LinkedHashMap<String, DraftNode> validated = new LinkedHashMap<>();
        if (drafts == null) return validated;
        if (drafts.size() > MAX_DRAFT_NODES) {
            warnings.add("Plan may contain at most " + MAX_DRAFT_NODES + " nodes");
            return validated;
        }
        for (DraftNode draft : drafts) {
            if (draft == null || draft.id() == null || draft.id().isBlank()) {
                warnings.add("Plan contains a node without an id");
                continue;
            }
            if (validated.putIfAbsent(draft.id(), draft) != null) {
                warnings.add("Duplicate step id '" + draft.id() + "'");
            }
        }
        return validated;
    }

    private static List<GoalCondition> checkedPrerequisites(List<GoalCondition> values) {
        if (values == null) return List.of();
        if (values.size() > MAX_PREREQUISITES) throw new IllegalArgumentException("A step may have at most " + MAX_PREREQUISITES + " prerequisites");
        return List.copyOf(values);
    }

    private static boolean containsCycle(Map<String, DraftNode> graph) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) {
            if (visitCycle(id, graph, visiting, visited)) return true;
        }
        return false;
    }

    private static boolean visitCycle(String id, Map<String, DraftNode> graph,
                                      Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        DraftNode node = graph.get(id);
        if (node != null) {
            for (String dependency : node.dependsOn()) {
                if (visitCycle(dependency, graph, visiting, visited)) return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private static List<Evidence> appendEvidence(List<Evidence> existing, Evidence evidence) {
        ArrayList<Evidence> result = new ArrayList<>(existing == null ? List.of() : existing);
        // Progress can update every tick. Retaining a short evidence tail is
        // sufficient for auditing without turning the plan into raw history.
        if (!result.isEmpty()) {
            Evidence last = result.get(result.size() - 1);
            if (!last.success() && !evidence.success()
                    && last.statement().equals(evidence.statement())) return List.copyOf(result);
        }
        result.add(evidence);
        while (result.size() > 16) result.remove(0);
        return List.copyOf(result);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalizeDependencies(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String dependency = value.trim();
            // A self edge is kept for validation so it produces a rejected
            // update rather than silently changing the requested semantics.
            result.add(dependency);
        }
        return List.copyOf(result);
    }
}
