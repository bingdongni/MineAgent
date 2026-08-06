package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;

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
    public enum NodeStatus { PENDING, IN_PROGRESS, VERIFIED, INVALIDATED, BLOCKED }

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
                           String lastFailure, List<Evidence> evidence) {
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
        }
    }

    public record DraftNode(String id, String description, String successCriterion,
                            String priority, List<String> dependsOn,
                            NodeStatus requestedStatus) {
        public DraftNode {
            dependsOn = normalizeDependencies(dependsOn);
        }
    }

    public record State(String goal, List<PlanNode> nodes,
                        List<IntentContract.Constraint> constraints,
                        long revision) {}

    public record UpdateResult(List<String> warnings, long revision, boolean accepted) {}

    private final LinkedHashMap<String, PlanNode> nodes = new LinkedHashMap<>();
    private final Map<String, String> taskBindings = new LinkedHashMap<>();
    private final Map<String, String> toolBindings = new LinkedHashMap<>();
    private List<IntentContract.Constraint> constraints = List.of();
    private String goal = "";
    private long revision;

    public synchronized UpdateResult replacePlan(String newGoal, List<DraftNode> drafts,
                                                  List<IntentContract.Constraint> newConstraints) {
        List<String> warnings = new ArrayList<>();
        LinkedHashMap<String, PlanNode> previousNodes = new LinkedHashMap<>(nodes);
        LinkedHashMap<String, DraftNode> validated = new LinkedHashMap<>();
        if (drafts != null) {
            for (DraftNode draft : drafts) {
                if (draft == null || draft.id() == null || draft.id().isBlank()) {
                    warnings.add("Plan contains a node without an id");
                    continue;
                }
                if (validated.putIfAbsent(draft.id(), draft) != null) {
                    warnings.add("Duplicate step id '" + draft.id() + "'");
                }
            }
        }
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
                boolean sameSemantics = sameSemantics(previous, draft);
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
                        sameSemantics ? previous.lastFailure() : null, evidence));
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
        taskBindings.entrySet().removeIf(entry -> {
            PlanNode before = previousNodes.get(entry.getValue());
            PlanNode after = nodes.get(entry.getValue());
            return before == null || after == null || !sameSemantics(before, after);
        });
        toolBindings.entrySet().removeIf(entry -> {
            PlanNode before = previousNodes.get(entry.getValue());
            PlanNode after = nodes.get(entry.getValue());
            return before == null || after == null || !sameSemantics(before, after);
        });
        goal = normalize(newGoal, goal.isBlank() ? "Current owner goal" : goal);
        if (newConstraints != null) constraints = List.copyOf(newConstraints);
        revision++;
        return new UpdateResult(List.copyOf(warnings), revision, true);
    }

    public synchronized void bindTask(String taskId, String toolName,
                                      IntentContract contract, long gameTick) {
        if (taskId == null || taskId.isBlank()) return;
        PlanNode node = currentNode();
        if (node == null) {
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
        revision++;
    }

    public synchronized void recordProgress(String taskId, TaskSnapshot snapshot, long gameTick) {
        String nodeId = taskBindings.get(taskId);
        PlanNode node = nodeId == null ? null : nodes.get(nodeId);
        if (node == null || snapshot == null) return;
        String statement = snapshot.summary();
        if (snapshot.isBlocked()) statement += "; blocked: " + snapshot.blockedReason();
        List<Evidence> evidence = appendEvidence(node.evidence(),
                new Evidence("task:" + taskId, statement, false, gameTick));
        NodeStatus status = snapshot.isBlocked() ? NodeStatus.BLOCKED : NodeStatus.IN_PROGRESS;
        nodes.put(node.id(), replace(node, status, node.attempts(),
                snapshot.blockedReason(), evidence));
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
        revision++;
    }

    /** Bind all synchronous calls in one model response to the same ready node. */
    public synchronized void bindToolCall(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) return;
        PlanNode node = currentNode();
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
        revision++;
        return true;
    }

    public synchronized PlanNode currentNode() {
        for (PlanNode node : nodes.values()) {
            if (node.status() == NodeStatus.IN_PROGRESS) return node;
        }
        for (PlanNode node : nodes.values()) {
            if (node.status() == NodeStatus.PENDING
                    && dependenciesVerified(node.dependsOn(), nodes)) return node;
        }
        return null;
    }

    public synchronized boolean hasActivePlan() {
        return nodes.values().stream().anyMatch(node ->
                node.status() == NodeStatus.PENDING
                        || node.status() == NodeStatus.IN_PROGRESS
                        || node.status() == NodeStatus.BLOCKED);
    }

    public synchronized int progressPercent() {
        if (nodes.isEmpty()) return 0;
        long completed = nodes.values().stream()
                .filter(node -> node.status() == NodeStatus.VERIFIED).count();
        return (int) Math.round(completed * 100.0 / nodes.size());
    }

    public synchronized String summarizeForPrompt() {
        if (nodes.isEmpty()) return "Plan: none";
        StringBuilder out = new StringBuilder("Plan goal: ").append(goal)
                .append(" (revision ").append(revision).append(", ")
                .append(progressPercent()).append("% verified)\n");
        for (PlanNode node : nodes.values()) {
            out.append("- [").append(node.status()).append("] ")
                    .append(node.id()).append(": ").append(node.description());
            if (!node.dependsOn().isEmpty()) {
                out.append(" | depends_on=").append(String.join(",", node.dependsOn()));
            }
            if (node.lastFailure() != null) out.append(" | blocker=").append(node.lastFailure());
            out.append('\n');
        }
        if (!constraints.isEmpty()) {
            out.append("Constraints:\n");
            for (IntentContract.Constraint constraint : constraints) {
                out.append("- ").append(constraint.kind()).append(": ")
                        .append(constraint.description()).append('\n');
            }
        }
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(goal, List.copyOf(nodes.values()), constraints, revision);
    }

    public synchronized void importState(State state) {
        nodes.clear();
        taskBindings.clear();
        toolBindings.clear();
        if (state == null) return;
        goal = normalize(state.goal(), "Restored goal");
        constraints = state.constraints() == null ? List.of() : List.copyOf(state.constraints());
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
        revision = Math.max(0L, state.revision()) + 1L;
    }

    public synchronized List<IntentContract.Constraint> constraints() {
        return constraints;
    }

    private static PlanNode replace(PlanNode node, NodeStatus status, int attempts,
                                    String failure, List<Evidence> evidence) {
        return new PlanNode(node.id(), node.description(), node.successCriterion(),
                node.priority(), node.dependsOn(), status, attempts, failure, evidence);
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
                && previous.dependsOn().equals(draft.dependsOn());
    }

    private static boolean sameSemantics(PlanNode first, PlanNode second) {
        return first != null && second != null
                && first.description().equals(second.description())
                && first.successCriterion().equals(second.successCriterion())
                && first.dependsOn().equals(second.dependsOn());
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
