package com.mineagent.engine.survival;

import com.mineagent.api.task.BodyLog;
import com.mineagent.engine.loop.AgentLoop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Concrete BodyLog implementation.
 * Thread-safe queue of narrative messages that are drained by the engine
 * tick and routed to the agent loop inbox.
 */
public final class CompanionBodyLog implements BodyLog {

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private volatile Consumer<String> inboxForwarder;

    public CompanionBodyLog() {}

    /**
     * Set the forwarder that delivers messages to the agent loop inbox.
     */
    public void setInboxForwarder(Consumer<String> forwarder) {
        this.inboxForwarder = forwarder;
    }

    @Override
    public void report(String narrative) {
        if (narrative == null || narrative.isEmpty()) return;
        queue.add(narrative);
    }

    /**
     * Drain all pending messages and forward them via the inbox forwarder.
     */
    public void flush() {
        Consumer<String> forwarder = this.inboxForwarder;
        List<String> batch = drainQueue();
        if (forwarder != null && !batch.isEmpty()) {
            for (String msg : batch) {
                try {
                    forwarder.accept(msg);
                } catch (Exception e) {
                    System.err.println("[MineAgent] BodyLog forward error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Drain all pending messages and forward them directly to an AgentLoop.
     * Called once per server tick by MineAgentEngine.onServerTick().
     */
    public void flush(AgentLoop loop) {
        List<String> batch = drainQueue();
        if (!batch.isEmpty() && loop != null) {
            for (String msg : batch) {
                try {
                    loop.onBodyLog(msg);
                } catch (Exception e) {
                    System.err.println("[MineAgent] BodyLog flush error: " + e.getMessage());
                }
            }
        }
    }

    private List<String> drainQueue() {
        List<String> batch = new ArrayList<>();
        String entry;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
        }
        return batch;
    }

    /** Number of pending messages. */
    public int pendingCount() {
        return queue.size();
    }
}
