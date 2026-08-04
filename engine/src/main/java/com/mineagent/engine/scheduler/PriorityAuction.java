package com.mineagent.engine.scheduler;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskChain;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.engine.planning.IntentAwareTask;
import com.mineagent.engine.planning.IntentContract;
import com.mineagent.tools.management.TaskStatusTool;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tick scheduler that grants exclusive body control to one survival chain,
 * one LLM task, or idle/reflex behavior.
 */
public class PriorityAuction {

    private final AgentPlayer companion;
    private final List<TaskChain> chains;
    private final IdleBehavior idle;
    private final AgentLoop loop;

    private TaskChain currentWinner;
    private CompanionTask<?> currentTask;
    private long taskStartGameTick;
    private IntentContract currentIntent;
    private boolean taskPaused;
    private long taskPausedAtTick;
    private long remainingDeadlineTicks;
    private long lastProgressPublishTick = Long.MIN_VALUE;
    private long lastProgressVersion = Long.MIN_VALUE;
    private String lastProgressStage = "";
    private int consecutiveDangerSignals;

    private static final float BASE_PREEMPT_THRESHOLD =
            com.mineagent.engine.survival.SurvivalDecisions.LLM_PREEMPT_THRESHOLD;
    private static final long TASK_PROTECTION_TICKS = 10L;
    private static final int DANGER_ESCALATION_THRESHOLD = 3;

    public PriorityAuction(AgentPlayer companion, List<TaskChain> chains, AgentLoop loop) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.chains = List.copyOf(Objects.requireNonNull(chains, "chains"));
        this.loop = Objects.requireNonNull(loop, "loop");
        this.idle = new IdleBehavior(companion);
    }

    /** Run one deterministic server-tick auction. */
    public TaskChain tick() {
        TaskChain winner = null;
        float bestPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : chains) {
            try {
                float priority = chain.getPriority(companion);
                if (Float.isFinite(priority) && priority > bestPriority) {
                    bestPriority = priority;
                    winner = chain;
                }
            } catch (Exception error) {
                System.err.println("[MineAgent] Chain " + safeChainName(chain)
                        + " priority error: " + error.getMessage());
            }
        }

        if (bestPriority >= com.mineagent.engine.survival.SurvivalDecisions.MOB_DEFENSE) {
            consecutiveDangerSignals++;
        } else {
            consecutiveDangerSignals = 0;
        }

        float threshold = calculateDynamicThreshold(bestPriority);
        if (currentTask != null && (winner == null || bestPriority <= threshold)) {
            winner = null;
        }

        if (currentTask != null && winner != null && !taskPaused) {
            pauseCurrentTask(winner);
        }

        if (winner != null || currentTask != null) {
            // Idle cleanup is a real progressive mining action. Relinquish it
            // before a survival chain or body task receives exclusive input.
            com.mineagent.engine.task.TaskContext.temporaryBlocks(companion)
                    .interrupt();
        }

        if (currentWinner != null && currentWinner != winner) {
            try {
                currentWinner.onInterrupt(companion);
            } catch (Exception error) {
                System.err.println("[MineAgent] Chain interrupt error: " + error.getMessage());
            }
        }

        if (winner != null) {
            idle.reset();
            try {
                winner.tick(companion);
            } catch (Exception error) {
                System.err.println("[MineAgent] Chain " + safeChainName(winner)
                        + " tick error: " + error.getMessage());
                try {
                    winner.onInterrupt(companion);
                } catch (Exception ignored) {}
                winner = null;
            }
        } else if (currentTask != null) {
            idle.reset();
            try {
                if (taskPaused && !resumeCurrentTask()) {
                    currentWinner = winner;
                    return winner;
                }
                TaskState state = currentTask.tick();
                publishProgress(false);
                if (state != TaskState.RUNNING) finishCurrentTask(state, null);
            } catch (Exception error) {
                System.err.println("[MineAgent] Task tick error: " + error.getMessage());
                finishCurrentTask(TaskState.FAILED, error);
            }
        } else {
            try {
                // Cleanup owns the same movement/mining channel as idle and
                // pickup reflexes. Never drive two bodies of input in one tick.
                if (!com.mineagent.engine.task.TaskContext.temporaryBlocks(companion)
                        .tickIdle()) {
                    idle.tick();
                    tickReflexes();
                } else {
                    idle.reset();
                }
            } catch (Exception error) {
                System.err.println("[MineAgent] Idle/reflex error: " + error.getMessage());
            }
        }

        currentWinner = winner;
        return winner;
    }

    public record TaskAdmission(boolean accepted, String taskId, String message) {}

    /**
     * Admit one body task. A duplicate dispatch must never silently destroy
     * work already in progress; callers receive the active task ID and can
     * explicitly stop or wait for it.
     */
    public TaskAdmission submitTask(CompanionTask<?> task) {
        Objects.requireNonNull(task, "task");
        String incomingId = task.record().toolCallId();
        if (currentTask != null) {
            String activeId = currentTask.record().toolCallId();
            String message = "Body is busy with task '" + activeId
                    + "'; wait for completion or call task_stop before dispatching another body task";
            TaskStatusTool.updateTaskInfo(companion.companionId(), incomingId,
                    describe(task), TaskState.CANCELLED, message, null, 0L, task.snapshot());
            loop.onBodyLog("[TASK_FINISHED] task_id=" + incomingId
                    + " state=CANCELLED message=" + message);
            return new TaskAdmission(false, activeId, message);
        }

        currentTask = task;
        taskStartGameTick = gameTime();
        taskPaused = false;
        currentIntent = task instanceof IntentAwareTask aware
                ? aware.intentContract()
                : IntentContract.generic(describe(task), "Executor reports success",
                        task.snapshot().targetX(), task.snapshot().targetY(), task.snapshot().targetZ());
        lastProgressPublishTick = Long.MIN_VALUE;
        lastProgressVersion = Long.MIN_VALUE;
        lastProgressStage = "";
        String taskName = describe(task);
        TaskStatusTool.updateTaskInfo(companion.companionId(), task.record().toolCallId(),
                taskName, TaskState.RUNNING, "Running", null, 0L, task.snapshot());
        try {
            task.start();
            loop.onTaskAccepted(task.record().toolCallId(), taskName, currentIntent,
                    task.snapshot(), taskStartGameTick);
            publishProgress(true);
            pushTaskUpdate(taskName);
        } catch (RuntimeException | Error error) {
            finishCurrentTask(TaskState.FAILED, error);
            throw error;
        }
        return new TaskAdmission(true, incomingId, "Accepted");
    }

    public boolean hasRunningTask() {
        return currentTask != null;
    }

    public void cancelTask() {
        if (currentTask != null) finishCurrentTask(TaskState.CANCELLED, null);
    }

    /** Cancel only when the caller still refers to the active task. */
    public boolean cancelTask(String taskId) {
        if (currentTask == null || taskId == null
                || !taskId.equals(currentTask.record().toolCallId())) return false;
        finishCurrentTask(TaskState.CANCELLED, null);
        return true;
    }

    private void finishCurrentTask(TaskState state, Throwable error) {
        CompanionTask<?> task = currentTask;
        if (task == null) return;
        currentTask = null;
        taskPaused = false;

        try {
            task.interrupt();
        } catch (Exception cleanupError) {
            System.err.println("[MineAgent] Task cleanup error: " + cleanupError.getMessage());
        }

        long now = gameTime();
        long elapsed = Math.max(0L, now - taskStartGameTick);
        boolean timedOut = task.record().deadline() > 0L && now >= task.record().deadline();
        String message;
        try {
            message = error == null
                    ? task.completionMessage(state, timedOut)
                    : "Task failed with " + error.getClass().getSimpleName() + ": "
                        + String.valueOf(error.getMessage());
        } catch (Exception ignored) {
            message = state.name().toLowerCase(Locale.ROOT);
        }

        String taskId = task.record().toolCallId();
        TaskStatusTool.updateTaskInfo(companion.companionId(), taskId,
                describe(task), state, message, null, elapsed, safeSnapshot(task));
        loop.onTaskFinished(taskId, describe(task), currentIntent, state,
                safeSnapshot(task), message, now);
        currentIntent = null;
        loop.onBodyLog("[TASK_FINISHED] task_id=" + taskId
                + " state=" + state.name() + " message=" + message);
        pushTaskUpdate("Idle");
    }

    private void pauseCurrentTask(TaskChain winner) {
        if (currentTask == null || taskPaused) return;
        try {
            currentTask.interrupt();
        } catch (Exception cleanupError) {
            finishCurrentTask(TaskState.FAILED, cleanupError);
            return;
        }
        taskPaused = true;
        taskPausedAtTick = gameTime();
        long deadline = currentTask.record().deadline();
        remainingDeadlineTicks = deadline <= 0L
                ? 0L : Math.max(1L, deadline - taskPausedAtTick);
        TaskSnapshot snapshot = safeSnapshot(currentTask);
        String reason = "Paused for survival chain " + safeChainName(winner);
        TaskStatusTool.updateTaskInfo(companion.companionId(),
                currentTask.record().toolCallId(), describe(currentTask),
                TaskState.PAUSED, reason, null,
                Math.max(0L, taskPausedAtTick - taskStartGameTick), snapshot);
        loop.onTaskProgress(currentTask.record().toolCallId(), snapshot,
                reason, taskPausedAtTick);
        pushTaskUpdate(reason);
    }

    /** Restart low-level execution from live world state, never a stale path. */
    private boolean resumeCurrentTask() {
        if (currentTask == null || !taskPaused) return true;
        long now = gameTime();
        if (remainingDeadlineTicks > 0L) {
            currentTask.record().extendDeadlineTo(now + remainingDeadlineTicks);
        }
        try {
            currentTask.resume();
            taskPaused = false;
            lastProgressVersion = Long.MIN_VALUE;
            lastProgressStage = "";
            publishProgress(true);
            pushTaskUpdate(describe(currentTask));
            return true;
        } catch (RuntimeException | Error error) {
            finishCurrentTask(TaskState.FAILED, error);
            return false;
        }
    }

    private void publishProgress(boolean force) {
        CompanionTask<?> task = currentTask;
        if (task == null) return;
        long now = gameTime();
        TaskSnapshot snapshot = safeSnapshot(task);
        boolean changed = snapshot.progressVersion() != lastProgressVersion
                || !snapshot.stage().equals(lastProgressStage)
                || snapshot.isBlocked();
        if (!force && !changed && now - lastProgressPublishTick < 20L) return;
        lastProgressPublishTick = now;
        lastProgressVersion = snapshot.progressVersion();
        lastProgressStage = snapshot.stage();
        long elapsed = Math.max(0L, now - taskStartGameTick);
        TaskStatusTool.updateTaskInfo(companion.companionId(), task.record().toolCallId(),
                describe(task), taskPaused ? TaskState.PAUSED : TaskState.RUNNING,
                snapshot.summary(), null, elapsed, snapshot);
        loop.onTaskProgress(task.record().toolCallId(), snapshot,
                snapshot.summary(), now);
    }

    private static TaskSnapshot safeSnapshot(CompanionTask<?> task) {
        try {
            TaskSnapshot snapshot = task.snapshot();
            return snapshot == null
                    ? TaskSnapshot.running("running", describe(task) + " is running")
                    : snapshot;
        } catch (RuntimeException snapshotFailure) {
            return TaskSnapshot.progress("snapshot_error", describe(task),
                    0L, -1L, null, null, null,
                    "Progress snapshot failed: " + snapshotFailure.getMessage(),
                    null, 0L);
        }
    }

    private float calculateDynamicThreshold(float challengerPriority) {
        float threshold = BASE_PREEMPT_THRESHOLD;
        if (currentTask != null
                && challengerPriority < com.mineagent.engine.survival.SurvivalDecisions.BREATH
                && Math.max(0L, gameTime() - taskStartGameTick) < TASK_PROTECTION_TICKS) {
            threshold += 2.0f;
        }
        if (consecutiveDangerSignals >= DANGER_ESCALATION_THRESHOLD) {
            threshold = Math.max(3.0f, threshold - 2.0f);
        }
        return threshold;
    }

    public void recordDangerSignal() {
        consecutiveDangerSignals++;
    }

    public void clearDangerSignals() {
        consecutiveDangerSignals = 0;
    }

    public TaskChain currentWinner() {
        return currentWinner;
    }

    public float getCurrentThreshold() {
        return calculateDynamicThreshold(Float.NEGATIVE_INFINITY);
    }

    private long gameTime() {
        return ((com.mineagent.engine.entity.CompanionEntity) companion)
                .serverPlayer().level().getGameTime();
    }

    private static String safeChainName(TaskChain chain) {
        try {
            return chain == null ? "unknown" : chain.name();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String describe(CompanionTask<?> task) {
        String simple = task.record().getClass().getSimpleName();
        return simple.endsWith("Record") && simple.length() > 6
                ? simple.substring(0, simple.length() - 6) : simple;
    }

    private void pushTaskUpdate(String description) {
        try {
            var owner = ((com.mineagent.engine.entity.CompanionEntity) companion)
                    .serverPlayerOwner();
            if (owner != null) {
                com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                        owner, companion.companionId(), "companion_task", description);
            }
        } catch (Exception ignored) {
            // UI transport must never break body scheduling.
        }
    }

    private void tickReflexes() {
        for (var reflex : com.mineagent.api.task.reflex.ReflexRegistry.all()) {
            if (!reflex.isEnabled(companion)) continue;
            try {
                if (reflex instanceof
                        com.mineagent.engine.survival.reflex.PickupItemsReflex pickup) {
                    pickup.findNearestItem(companion)
                            .ifPresent(item -> pickup.nudgeToward(companion, item));
                }
            } catch (Exception error) {
                System.err.println("[MineAgent] Reflex " + reflex.id()
                        + " tick error: " + error.getMessage());
            }
        }
    }
}
