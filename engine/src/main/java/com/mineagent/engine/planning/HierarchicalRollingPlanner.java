package com.mineagent.engine.planning;

import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.api.task.TaskState;
import com.mineagent.engine.world.SemanticWorldModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Maintains strategic, tactical, and execution horizons over {@link PlanGraph}.
 *
 * <p>The graph remains the verifier-backed plan of record. This controller
 * decides when its near-term window is no longer trustworthy: no executor
 * progress, a blocked ready node, repeated failures, or a material world-model
 * revision. Replanning preserves verified graph nodes and requests only a
 * bounded rolling window, which reduces both token use and plan churn.
 */
public final class HierarchicalRollingPlanner {
    public enum Reason {
        NEW_GOAL, PLAN_MISSING, ACTIVE_STEP_BLOCKED, EXECUTOR_STALLED,
        REPEATED_FAILURE, WORLD_CHANGED, WINDOW_EXHAUSTED
    }

    public record ReplanSignal(Reason reason, String detail, String goal,
                               String activeStep, long gameTick,
                               long planRevision, long worldRevision) {
        public ReplanSignal {
            reason = reason == null ? Reason.PLAN_MISSING : reason;
            detail = normalize(detail, reason.name());
            goal = normalize(goal, "Current owner goal");
            activeStep = blankToNull(activeStep);
            gameTick = Math.max(0L, gameTick);
            planRevision = Math.max(0L, planRevision);
            worldRevision = Math.max(0L, worldRevision);
        }

        public String event() {
            return "[ROLLING_REPLAN] reason=" + reason.name().toLowerCase(Locale.ROOT)
                    + " goal=" + goal + " active_step="
                    + (activeStep == null ? "none" : activeStep)
                    + " detail=" + detail
                    + "; preserve verified steps and repair only the next executable window";
        }
    }

    public record State(String strategicGoal, long goalEpoch,
                        long observedPlanRevision, long observedWorldRevision,
                        int consecutiveFailures, long revision) {}

    private static final long STALL_TICKS = 240L;
    private static final int FAILURE_REPLAN_THRESHOLD = 2;
    private static final int TACTICAL_WINDOW = 4;

    private final PlanGraph graph;
    private final SemanticWorldModel worldModel;
    private String strategicGoal = "";
    private long goalEpoch;
    private long observedPlanRevision;
    private long observedWorldRevision;
    private long lastProgressTick;
    private long lastProgressVersion = Long.MIN_VALUE;
    private String lastBlockedReason;
    private long lastSignalTick = Long.MIN_VALUE;
    private String lastSignalSignature = "";
    private int consecutiveFailures;
    private String activeTaskId;
    private String activeTaskName;
    private boolean taskActive;
    private long revision;

    public HierarchicalRollingPlanner(PlanGraph graph,
                                      SemanticWorldModel worldModel) {
        this.graph = java.util.Objects.requireNonNull(graph, "graph");
        this.worldModel = java.util.Objects.requireNonNull(worldModel, "worldModel");
    }

    /** A successfully accepted plan is the authoritative declaration of goal. */
    public synchronized void onPlanReplaced(String goal, long gameTick) {
        PlanGraph.State state = graph.exportState();
        String normalized = normalize(goal, state.goal());
        if (!normalized.equals(strategicGoal)) {
            strategicGoal = normalized;
            goalEpoch++;
        }
        // Failure count belongs to the invalid tactical window. Keeping it
        // after an accepted same-goal suffix repair immediately emitted the
        // same REPEATED_FAILURE signal before the repaired step could run.
        // Durable failure evidence remains in PlanGraph/ExperienceStore.
        consecutiveFailures = 0;
        observedPlanRevision = state.revision();
        observedWorldRevision = worldModel.revision();
        lastProgressTick = Math.max(0L, gameTick);
        lastSignalSignature = "";
        revision++;
    }

    public synchronized void onTaskAccepted(String taskId, String taskName,
                                            TaskSnapshot snapshot, long gameTick) {
        activeTaskId = blankToNull(taskId);
        activeTaskName = normalize(taskName, "body_task");
        taskActive = true;
        lastProgressTick = Math.max(0L, gameTick);
        lastProgressVersion = snapshot == null
                ? Long.MIN_VALUE : snapshot.progressVersion();
        lastBlockedReason = snapshot == null ? null : snapshot.blockedReason();
        lastSignalSignature = "";
        revision++;
    }

    public synchronized void onTaskProgress(String taskId, TaskSnapshot snapshot,
                                            long gameTick) {
        if (!taskActive || taskId == null || !taskId.equals(activeTaskId)) return;
        if (snapshot == null) return;
        boolean progressed = snapshot.progressVersion() != lastProgressVersion;
        boolean blockerChanged = !java.util.Objects.equals(
                snapshot.blockedReason(), lastBlockedReason);
        if (!progressed && !blockerChanged) return;
        lastProgressVersion = snapshot.progressVersion();
        lastBlockedReason = snapshot.blockedReason();
        if (progressed) lastProgressTick = Math.max(lastProgressTick, gameTick);
        // Re-arm the gate only for genuinely new executor evidence. Repeated
        // blocked heartbeats used to produce a fresh replan signature forever.
        lastSignalSignature = "";
        revision++;
    }

    public synchronized void onTaskFinished(String taskId, TaskState state,
                                            long gameTick) {
        if (taskId != null && activeTaskId != null && !taskId.equals(activeTaskId)) return;
        taskActive = false;
        activeTaskId = null;
        activeTaskName = null;
        lastBlockedReason = null;
        lastSignalSignature = "";
        lastProgressTick = Math.max(lastProgressTick, gameTick);
        if (state == TaskState.SUCCESS) consecutiveFailures = 0;
        else if (state == TaskState.FAILED || state == TaskState.CANCELLED) {
            consecutiveFailures++;
        }
        revision++;
    }

    public synchronized void onSynchronousOutcome(boolean success, long gameTick) {
        lastProgressTick = Math.max(lastProgressTick, gameTick);
        if (success) consecutiveFailures = 0;
        else consecutiveFailures++;
        revision++;
    }

    /** Return a deduplicated replan signal only when the current window is invalid. */
    public synchronized ReplanSignal tick(long gameTick) {
        PlanGraph.State state = graph.exportState();
        PlanGraph.PlanNode current = graph.currentNode();
        PlanGraph.PlanNode blocked = state.nodes().stream()
                .filter(node -> node.status() == PlanGraph.NodeStatus.BLOCKED)
                .findFirst().orElse(null);
        long worldRevision = worldModel.revision();
        Reason reason = null;
        String detail = null;

        if (strategicGoal.isBlank() && !state.goal().isBlank()) {
            strategicGoal = state.goal();
            goalEpoch++;
        }
        if (!strategicGoal.isBlank() && state.nodes().isEmpty()) {
            reason = Reason.PLAN_MISSING;
            detail = "The strategic goal has no verifier-backed decomposition";
        } else if (blocked != null && current == null) {
            reason = Reason.ACTIVE_STEP_BLOCKED;
            current = blocked;
            detail = normalize(blocked.lastFailure(), "The active step is blocked");
        } else if (taskActive && lastProgressTick > 0L
                && gameTick - lastProgressTick >= STALL_TICKS) {
            reason = Reason.EXECUTOR_STALLED;
            detail = "No executor progress for " + (gameTick - lastProgressTick)
                    + " ticks while running " + activeTaskName;
        } else if (consecutiveFailures >= FAILURE_REPLAN_THRESHOLD) {
            reason = Reason.REPEATED_FAILURE;
            detail = consecutiveFailures + " consecutive executor failures";
        } else if (graph.hasActivePlan() && current == null) {
            reason = Reason.WINDOW_EXHAUSTED;
            detail = state.goalStatus() == PlanGraph.GoalStatus.VERIFYING
                    ? "All tactical milestones are verified, but top-level acceptance evidence is still missing"
                    : "No dependency-ready step remains in the tactical window";
        } else if (current != null && current.status() != PlanGraph.NodeStatus.IN_PROGRESS
                && worldRevision - observedWorldRevision >= 32L
                && state.revision() == observedPlanRevision
                && (current.lastFailure() != null || consecutiveFailures > 0)) {
            // A large semantic delta matters only between body actions. During
            // an action, ordinary movement and inventory updates are expected.
            reason = Reason.WORLD_CHANGED;
            detail = "World evidence changed materially since the current plan revision";
        }

        observedPlanRevision = Math.max(observedPlanRevision, state.revision());
        observedWorldRevision = Math.max(observedWorldRevision, worldRevision);
        if (reason == null) return null;
        String active = current == null ? null : current.id();
        String decisionEvidence = current == null ? null : current.lastFailure();
        String signature = reason + "|" + goalEpoch + "|" + active + "|"
                + normalize(decisionEvidence, "") + "|" + consecutiveFailures;
        // Graph revisions include progress/UI bookkeeping and are not decision
        // evidence. Keying the gate by revision caused one paid LLM wake per
        // repeated blocked snapshot. Display-only elapsed ticks also change on
        // every stall heartbeat, so the gate uses only semantic evidence.
        if (signature.equals(lastSignalSignature)) return null;
        lastSignalSignature = signature;
        lastSignalTick = gameTick;
        return new ReplanSignal(reason, detail,
                normalize(strategicGoal, state.goal()), active, gameTick,
                state.revision(), worldRevision);
    }

    public synchronized String summarizeForPrompt() {
        PlanGraph.State state = graph.exportState();
        if (state.nodes().isEmpty() && strategicGoal.isBlank()) {
            return "Hierarchical planner: idle\n";
        }
        StringBuilder out = new StringBuilder("Hierarchical rolling planner:\n");
        out.append("- strategic_goal=").append(normalize(strategicGoal, state.goal()))
                .append(" epoch=").append(goalEpoch)
                .append(" status=").append(state.goalStatus().name()
                        .toLowerCase(Locale.ROOT))
                .append(" repairs=").append(state.repairCount()).append('\n');
        List<PlanGraph.PlanNode> tactical = tacticalWindow(state.nodes());
        out.append("- tactical_window=");
        if (tactical.isEmpty()) out.append("empty");
        for (PlanGraph.PlanNode node : tactical) {
            out.append('\n').append("  * ").append(node.id()).append('(')
                    .append(node.status().name().toLowerCase(Locale.ROOT)).append("): ")
                    .append(compact(node.description(), 180));
            if (!node.dependsOn().isEmpty()) {
                out.append(" depends_on=").append(String.join(",", node.dependsOn()));
            }
            if (node.lastFailure() != null) {
                out.append(" blocker=").append(compact(node.lastFailure(), 140));
            }
        }
        out.append('\n');
        out.append("- execution=").append(taskActive
                ? activeTaskName + " task_id=" + activeTaskId : "idle")
                .append(" consecutive_failures=").append(consecutiveFailures).append('\n');
        if (!state.goalConditions().isEmpty()) {
            out.append("- strategic_acceptance=");
            for (int index = 0; index < state.goalConditions().size(); index++) {
                if (index > 0) out.append("; ");
                out.append(state.goalConditions().get(index).describe());
            }
            out.append('\n');
        }
        out.append("Plan only the next useful window; preserve verified prefix, explicit dependencies, constraints, contingencies, and observable success criteria.\n");
        return out.toString();
    }

    public synchronized State exportState() {
        return new State(strategicGoal, goalEpoch, observedPlanRevision,
                observedWorldRevision, consecutiveFailures, revision);
    }

    public synchronized void importState(State state) {
        if (state == null) return;
        strategicGoal = normalize(state.strategicGoal(), "");
        goalEpoch = Math.max(0L, state.goalEpoch());
        observedPlanRevision = Math.max(0L, state.observedPlanRevision());
        observedWorldRevision = Math.max(0L, state.observedWorldRevision());
        consecutiveFailures = Math.max(0, state.consecutiveFailures());
        revision = Math.max(0L, state.revision()) + 1L;
        // A live body action cannot be restored safely; PlanGraph already
        // converts interrupted in-progress nodes to BLOCKED during loading.
        taskActive = false;
        activeTaskId = null;
        activeTaskName = null;
        lastProgressVersion = Long.MIN_VALUE;
        lastBlockedReason = null;
        lastSignalSignature = "";
    }

    private static List<PlanGraph.PlanNode> tacticalWindow(List<PlanGraph.PlanNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return List.of();
        ArrayList<PlanGraph.PlanNode> candidates = new ArrayList<>();
        for (PlanGraph.PlanNode node : nodes) {
            if (node.status() != PlanGraph.NodeStatus.VERIFIED
                    && node.status() != PlanGraph.NodeStatus.INVALIDATED) {
                candidates.add(node);
            }
        }
        candidates.sort(Comparator.comparingInt(HierarchicalRollingPlanner::statusRank));
        return candidates.size() <= TACTICAL_WINDOW ? List.copyOf(candidates)
                : List.copyOf(candidates.subList(0, TACTICAL_WINDOW));
    }

    private static int statusRank(PlanGraph.PlanNode node) {
        return switch (node.status()) {
            case IN_PROGRESS -> 0;
            case PENDING -> 1;
            case BLOCKED -> 2;
            case VERIFIED, INVALIDATED -> 3;
        };
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String compact(String value, int limit) {
        String normalized = normalize(value, "unknown").replace('\n', ' ')
                .replace('\r', ' ');
        return normalized.length() <= limit ? normalized
                : normalized.substring(0, limit) + "...";
    }
}
