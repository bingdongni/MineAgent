package com.mineagent.engine.scheduler;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.*;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.tools.management.TaskStatusTool;

import java.util.*;
import java.util.concurrent.*;

/**
 * The priority auction scheduler — runs every server tick and decides
 * which chain controls the companion's body.
 *
 * <p>Auction rules:
 * <ol>
 *   <li>Each chain reports its priority for this tick</li>
 *   <li>The highest-priority chain wins the body</li>
 *   <li>The previous winner (if different) is interrupted</li>
 *   <li>The winning chain's {@code tick()} is called</li>
 * </ol>
 *
 * <p><b>改进:</b>
 * <ul>
 *   <li>动态抢占阈值: 根据当前任务类型和情境动态调整</li>
 *   <li>紧急升级机制: 连续危险信号可临时提升优先级</li>
 *   <li>任务保护期: 新任务有短暂保护期，避免被立即打断</li>
 * </ul>
 */
public class PriorityAuction {

    private final AgentPlayer companion;
    private final List<TaskChain> chains;
    private TaskChain currentWinner = null;
    private CompanionTask<?> currentTask = null;
    private final IdleBehavior idle;
    private final AgentLoop loop;

    /** 当前任务开始时间（用于保护期） */
    /** Server game tick used for deterministic elapsed-time reporting. */
    private long taskStartGameTick = 0;

    /** 连续危险信号计数（用于紧急升级） */
    private int consecutiveDangerSignals = 0;

    /** 基础抢占阈值 */
    private static final float BASE_PREEMPT_THRESHOLD =
            com.mineagent.engine.survival.SurvivalDecisions.LLM_PREEMPT_THRESHOLD;

    /** 任务保护期（毫秒） */
    private static final long TASK_PROTECTION_TICKS = 10L;

    /** 紧急升级阈值：连续危险信号达到此次数后提升优先级 */
    private static final int DANGER_ESCALATION_THRESHOLD = 3;

    /**
     * 动态计算的抢占阈值。
     * 考虑因素：
     * 1. 任务运行时间（新任务有保护期）
     * 2. 连续危险信号（紧急升级）
     *
     * @param challengerPriority 当前挑战者链的最高优先级。
     *        保护期加成对生存级本能链（溺水/高空坠落，>= BREATH）无效 ——
     *        一个新开始 500ms 的普通任务不能挡住溺水自救。
     */
    private float calculateDynamicThreshold(float challengerPriority) {
        float threshold = BASE_PREEMPT_THRESHOLD;

        // 1. 任务保护期：新任务降低被抢占的可能性。
        //    但生存级链（溺水=6.0、MLG=10.0、危急怪物防御）豁免保护期 ——
        //    10 tick 的溺水延迟足以致死，保护期不能无差别挡生存链。
        if (currentTask != null
                && challengerPriority < com.mineagent.engine.survival.SurvivalDecisions.BREATH) {
            // Scheduling is tick-driven, so its protection window must use
            // the same clock. Wall time made pausing, low TPS and system-clock
            // adjustments unpredictably shorten or extend body ownership.
            long taskAge = Math.max(0L, gameTime() - taskStartGameTick);
            if (taskAge < TASK_PROTECTION_TICKS) {
                // 保护期内提高阈值
                threshold += 2.0f;
            }
        }

        // 2. 紧急升级：连续危险信号降低阈值
        if (consecutiveDangerSignals >= DANGER_ESCALATION_THRESHOLD) {
            threshold = Math.max(3.0f, threshold - 2.0f);
        }

        return threshold;
    }

    /**
     * 记录危险信号（由外部调用，如MobDefenseChain检测到威胁）
     */
    public void recordDangerSignal() {
        consecutiveDangerSignals++;
    }

    /**
     * 清除危险信号（威胁解除时调用）
     */
    public void clearDangerSignals() {
        consecutiveDangerSignals = 0;
    }

    public PriorityAuction(AgentPlayer companion, List<TaskChain> chains, AgentLoop loop) {
        this.companion = companion;
        this.chains = List.copyOf(chains);
        this.loop = Objects.requireNonNull(loop, "loop");
        this.idle = new IdleBehavior(companion);
    }

    /**
     * Run the auction for one tick. Returns the winning chain.
     */
    public TaskChain tick() {
        // 1. Find highest-priority chain
        TaskChain winner = null;
        float bestPriority = Float.NEGATIVE_INFINITY;

        for (TaskChain chain : chains) {
            try {
                float priority = chain.getPriority(companion);
                if (priority > bestPriority) {
                    bestPriority = priority;
                    winner = chain;
                }
            } catch (Exception e) {
                // Chain error — skip it this tick
                System.err.println("[MineAgent] Chain " + chain.name()
                        + " error: " + e.getMessage());
            }
        }

        // 2. 危险信号自检测：有生存级链（怪物防御及以上）在竞争即视为危险。
        //    这让紧急升级机制真实生效（此前 recordDangerSignal 无调用方）。
        if (bestPriority >= com.mineagent.engine.survival.SurvivalDecisions.MOB_DEFENSE) {
            consecutiveDangerSignals++;
        } else {
            consecutiveDangerSignals = 0;
        }

        // 3. 使用动态阈值判断是否可以抢占LLM任务
        float dynamicThreshold = calculateDynamicThreshold(bestPriority);
        if (currentTask != null && (winner == null || bestPriority <= dynamicThreshold)) {
            winner = null; // LLM task continues
            bestPriority = 0;
        }

        if (currentTask != null && winner != null) {
            // Crossing the preemption threshold must be a real ownership
            // transfer. Merely skipping task.tick() leaves its navigation,
            // block breaking, or use-item state active while the survival
            // chain drives the same body. Cancel through the normal terminal
            // path so task-specific onInterrupt cleanup always runs.
            finishCurrentTask(TaskState.CANCELLED, null);
        }

        // 3. Interrupt previous winner if it changed
        if (currentWinner != null && currentWinner != winner) {
            try {
                currentWinner.onInterrupt(companion);
            } catch (Exception e) {
                System.err.println("[MineAgent] Interrupt error: " + e.getMessage());
            }
        }

        // 4. Tick the winner
        if (winner != null) {
            // An instinct chain is driving the body — reset idle so
            // the next idle period starts fresh
            idle.reset();
            try {
                winner.tick(companion);
            } catch (Exception e) {
                System.err.println("[MineAgent] Chain tick error (" + winner.name()
                        + "): " + e.getMessage());
            }
        } else if (currentTask != null) {
            // The LLM task is driving the body — reset idle
            idle.reset();
            try {
                TaskState state = currentTask.tick();
                if (state != TaskState.RUNNING) {
                    finishCurrentTask(state, null);
                }
            } catch (Exception e) {
                System.err.println("[MineAgent] Task tick error: " + e.getMessage());
                finishCurrentTask(TaskState.FAILED, e);
            }
        } else {
            // Nothing is driving the body (e.g. LLM is still thinking
            // between tool calls, or the previous task just finished).
            // Run idle behavior so the companion doesn't freeze — it
            // looks around casually instead of standing perfectly still.
            try {
                idle.tick();
            } catch (Exception e) {
                System.err.println("[MineAgent] Idle tick error: " + e.getMessage());
            }

            // ── Reflex ticks: run background reflexes that modify idle
            // behavior. Previously these methods (e.g. PickupItemsReflex.
            // findNearestItem / nudgeToward) had NO callers, so the
            // companion never picked up items even when standing on top
            // of dropped loot. We tick each reflex here, in the idle
            // branch only, so reflexes don't interfere with active
            // chains or tasks.
            try {
                tickReflexes();
            } catch (Exception e) {
                System.err.println("[MineAgent] Reflex tick error: " + e.getMessage());
            }
        }

        currentWinner = winner;
        return winner;
    }

    /**
     * Submit a companion task for execution.
     * Only one task can run at a time — new tasks replace the old one.
     */
    public void submitTask(CompanionTask<?> task) {
        Objects.requireNonNull(task, "task");
        if (currentTask != null) {
            finishCurrentTask(TaskState.CANCELLED, null);
        }
        currentTask = task;
        taskStartGameTick = gameTime();
        String taskName = describe(task);
        TaskStatusTool.updateTaskInfo(companion.companionId(), task.record().toolCallId(), taskName,
                TaskState.RUNNING, "Running", null, 0L);
        try {
            // Start exactly once. Reinitializing a task twice discarded its
            // first navigator/state and could leave the abandoned instance's
            // inputs or break operation alive.
            task.start();
            pushTaskUpdate(taskName);
        } catch (RuntimeException | Error e) {
            finishCurrentTask(TaskState.FAILED, e);
            throw e;
        }
    }

    /** Is there a running companion task? */
    public boolean hasRunningTask() {
        return currentTask != null;
    }

    /** Cancel the running task. */
    public void cancelTask() {
        if (currentTask != null) {
            finishCurrentTask(TaskState.CANCELLED, null);
        }
    }

    /** Cancel only when the supplied ID still identifies the running task. */
    public boolean cancelTask(String taskId) {
        if (currentTask == null || taskId == null
                || !taskId.equals(currentTask.record().toolCallId())) {
            return false;
        }
        finishCurrentTask(TaskState.CANCELLED, null);
        return true;
    }

    /**
     * Complete exactly one task and publish the same terminal state everywhere.
     * Interrupt is called on every terminal path because task implementations
     * own navigation and use-item state that must not outlive the task.
     */
    private void finishCurrentTask(TaskState state, Throwable error) {
        CompanionTask<?> task = currentTask;
        if (task == null) return;
        currentTask = null;

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
            message = error != null
                    ? "Task failed with " + error.getClass().getSimpleName() + ": "
                        + String.valueOf(error.getMessage())
                    : task.completionMessage(state, timedOut);
        } catch (Exception messageError) {
            message = state.name().toLowerCase(Locale.ROOT);
        }

        String taskId = task.record().toolCallId();
        TaskStatusTool.updateTaskInfo(companion.companionId(), taskId,
                describe(task), state, message, null, elapsed);
        loop.onBodyLog("[TASK_FINISHED] task_id=" + taskId
                + " state=" + state.name() + " message=" + message);
        pushTaskUpdate("Idle");
    }

    private long gameTime() {
        return ((com.mineagent.engine.entity.CompanionEntity) companion)
                .serverPlayer().level().getGameTime();
    }

    /** Get the current winning chain (for debugging). */
    public TaskChain currentWinner() {
        return currentWinner;
    }

    /** 获取当前动态阈值（用于调试，按无挑战者计算） */
    public float getCurrentThreshold() {
        return calculateDynamicThreshold(Float.NEGATIVE_INFINITY);
    }

    // ── UI task status push ────────────────────────────────────────

    /** Derive a human-readable task name from the record class
     *  (e.g. "MoveToRecord" → "MoveTo"). */
    private static String describe(CompanionTask<?> task) {
        String simple = task.record().getClass().getSimpleName();
        return simple.endsWith("Record") && simple.length() > 6
                ? simple.substring(0, simple.length() - 6) : simple;
    }

    /** Push the current task description to the owner's client UI
     *  (chat screen "Task:" line + status panel). Failures are swallowed —
     *  UI updates must never break the scheduler. */
    private void pushTaskUpdate(String description) {
        try {
            var owner = ((com.mineagent.engine.entity.CompanionEntity) companion)
                    .serverPlayerOwner();
            if (owner != null) {
                com.mineagent.engine.network.MineAgentNetwork.sendUiActionTo(
                        owner, companion.companionId(), "companion_task", description);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Tick background reflexes during idle. Each reflex that has
     * idle-time behavior is explicitly invoked here. This is the
     * ONLY place reflex actions are driven — previously methods
     * like PickupItemsReflex.nudgeToward() had zero callers.
     */
    private void tickReflexes() {
        for (var reflex : com.mineagent.api.task.reflex.ReflexRegistry.all()) {
            if (!reflex.isEnabled(companion)) continue;
            try {
                if (reflex instanceof com.mineagent.engine.survival.reflex.PickupItemsReflex pir) {
                    // Auto-pickup: if there's a dropped item nearby, walk
                    // toward it so vanilla collision-pickup triggers.
                    var item = pir.findNearestItem(companion);
                    if (item.isPresent()) {
                        pir.nudgeToward(companion, item.get());
                    }
                }
                // Other reflexes (AutoEat, FightBack, AvoidCreepers) are
                // driven by their corresponding SurvivalChain (FoodChain,
                // MobDefenseChain, etc.), so they don't need idle ticks.
            } catch (Exception e) {
                System.err.println("[MineAgent] Reflex " + reflex.id()
                        + " tick error: " + e.getMessage());
            }
        }
    }
}
