package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-companion task queue — accepts TaskRecords from tools and
 * feeds them to the tick dispatcher on the server thread.
 */
public final class CompanionTickDispatcher {

    private CompanionTickDispatcher() {}

    private static final ConcurrentLinkedQueue<PendingTask> PENDING = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingWork> PENDING_WORK =
            new ConcurrentLinkedQueue<>();

    /** Submit a task record for dispatch. */
    public static void submit(AgentPlayer player, TaskRecord record) {
        PENDING.add(new PendingTask(
                java.util.Objects.requireNonNull(player, "player"),
                java.util.Objects.requireNonNull(record, "record")));
    }

    /** Drain and return all pending tasks. Called once per tick on the server thread. */
    public static java.util.List<PendingTask> drain() {
        var list = new java.util.ArrayList<PendingTask>();
        PendingTask t;
        while ((t = PENDING.poll()) != null) {
            list.add(t);
        }
        return list;
    }

    /** Queue bounded main-thread work which must continue across server ticks. */
    public static void submitWork(AgentPlayer player, TickWork work) {
        PENDING_WORK.add(new PendingWork(
                java.util.Objects.requireNonNull(player, "player"),
                java.util.Objects.requireNonNull(work, "work")));
    }

    /** Transfer newly submitted work to the engine's active work list. */
    public static java.util.List<PendingWork> drainWork() {
        var list = new java.util.ArrayList<PendingWork>();
        PendingWork work;
        while ((work = PENDING_WORK.poll()) != null) list.add(work);
        return list;
    }

    /**
     * One incremental server-thread operation.
     *
     * @return true once the operation has reached a terminal state
     */
    public interface TickWork {
        boolean tick();

        default void onFailure(Throwable failure) {}

        default void onDiscarded() {}
    }

    public record PendingTask(AgentPlayer player, TaskRecord record) {}
    public record PendingWork(AgentPlayer player, TickWork work) {}
}
