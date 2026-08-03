package com.mineagent.engine.network.handler;

import com.mineagent.api.network.payload.PathDebugPayload;
import com.mineagent.api.network.payload.TaskResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.*;

/**
 * Handles incoming server→client packets.
 * <p>
 * All handler methods are called on the client thread (the platform modules
 * ensure this by scheduling via {@code client.execute()}).
 * <p>
 * Handles:
 * <ul>
 *   <li>{@code onTaskResult} - update client-side task status display</li>
 *   <li>{@code onPathDebug} - render path debug overlay</li>
 * </ul>
 */
public final class ClientPacketHandler {

    /** Active task results, keyed by companion ID. */
    private static final Map<UUID, TaskResultPayload> ACTIVE_TASK_RESULTS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** Active path debug data, keyed by companion ID. */
    private static final Map<UUID, PathDebugPayload> ACTIVE_PATH_DEBUGS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** Listeners for task result events. */
    private static final List<TaskResultListener> TASK_RESULT_LISTENERS =
            new ArrayList<>();

    /** Listeners for path debug events. */
    private static final List<PathDebugListener> PATH_DEBUG_LISTENERS =
            new ArrayList<>();

    private ClientPacketHandler() {}

    // ── Task result handling ───────────────────────────────────────

    /**
     * Handle an incoming TaskResultPayload from the server.
     * <p>
     * Updates the client-side task status display with the result.
     * Notifies all registered {@link TaskResultListener}s.
     *
     * @param client  the Minecraft client instance
     * @param payload the task result payload
     */
    public static void onTaskResult(Minecraft client, TaskResultPayload payload) {
        ACTIVE_TASK_RESULTS.put(payload.companionId(), payload);

        // Notify listeners
        for (TaskResultListener listener : TASK_RESULT_LISTENERS) {
            try {
                listener.onTaskResult(payload);
            } catch (Exception e) {
                System.err.println("[MineAgent] TaskResultListener error: "
                        + e.getMessage());
            }
        }

        // Display a chat message if the player is nearby
        if (client.player != null) {
            String status = payload.success() ? "§a✓" : "§c✗";
            String message = String.format("§7[MineAgent] %s %s: %s",
                    status, payload.toolCallId(), payload.message());
            client.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(message), false);
        }
    }

    /**
     * Get the latest task result for a companion.
     */
    public static TaskResultPayload getLatestTaskResult(UUID companionId) {
        return ACTIVE_TASK_RESULTS.get(companionId);
    }

    /**
     * Get all active task results.
     */
    public static Map<UUID, TaskResultPayload> getAllTaskResults() {
        synchronized (ACTIVE_TASK_RESULTS) {
            // Returning an unmodifiable view still exposed concurrent mutation
            // during renderer iteration. Return a stable snapshot instead.
            return Collections.unmodifiableMap(new LinkedHashMap<>(ACTIVE_TASK_RESULTS));
        }
    }

    /**
     * Register a listener for task result events.
     */
    public static void addTaskResultListener(TaskResultListener listener) {
        TASK_RESULT_LISTENERS.add(listener);
    }

    /**
     * Remove a task result listener.
     */
    public static void removeTaskResultListener(TaskResultListener listener) {
        TASK_RESULT_LISTENERS.remove(listener);
    }

    // ── Path debug handling ────────────────────────────────────────

    /**
     * Handle an incoming PathDebugPayload from the server.
     * <p>
     * Stores the path debug data and notifies all registered
     * {@link PathDebugListener}s. The actual rendering is done by
     * the HUD overlay, which reads from {@link #getLatestPathDebug}.
     *
     * @param client  the Minecraft client instance
     * @param payload the path debug payload
     */
    public static void onPathDebug(Minecraft client, PathDebugPayload payload) {
        ACTIVE_PATH_DEBUGS.put(payload.companionId(), payload);

        // Notify listeners
        for (PathDebugListener listener : PATH_DEBUG_LISTENERS) {
            try {
                listener.onPathDebug(payload);
            } catch (Exception e) {
                System.err.println("[MineAgent] PathDebugListener error: "
                        + e.getMessage());
            }
        }
    }

    /**
     * Get the latest path debug data for a companion.
     */
    public static PathDebugPayload getLatestPathDebug(UUID companionId) {
        return ACTIVE_PATH_DEBUGS.get(companionId);
    }

    /**
     * Get all active path debug data.
     */
    public static Map<UUID, PathDebugPayload> getAllPathDebugs() {
        synchronized (ACTIVE_PATH_DEBUGS) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(ACTIVE_PATH_DEBUGS));
        }
    }

    /**
     * Clear path debug data for a specific companion.
     */
    public static void clearPathDebug(UUID companionId) {
        ACTIVE_PATH_DEBUGS.remove(companionId);
    }

    /** Remove all cached UI state for one despawned companion only. */
    public static void clearCompanion(UUID companionId) {
        if (companionId == null) return;
        ACTIVE_TASK_RESULTS.remove(companionId);
        ACTIVE_PATH_DEBUGS.remove(companionId);
    }

    /**
     * Register a listener for path debug events.
     */
    public static void addPathDebugListener(PathDebugListener listener) {
        PATH_DEBUG_LISTENERS.add(listener);
    }

    /**
     * Remove a path debug listener.
     */
    public static void removePathDebugListener(PathDebugListener listener) {
        PATH_DEBUG_LISTENERS.remove(listener);
    }

    // ── Cleanup ────────────────────────────────────────────────────

    /**
     * Clear all client-side state (called on disconnect).
     */
    public static void clearAll() {
        ACTIVE_TASK_RESULTS.clear();
        ACTIVE_PATH_DEBUGS.clear();
    }

    // ── Listener interfaces ────────────────────────────────────────

    /**
     * Listener for task result events.
     */
    @FunctionalInterface
    public interface TaskResultListener {
        void onTaskResult(TaskResultPayload payload);
    }

    /**
     * Listener for path debug events.
     */
    @FunctionalInterface
    public interface PathDebugListener {
        void onPathDebug(PathDebugPayload payload);
    }
}
