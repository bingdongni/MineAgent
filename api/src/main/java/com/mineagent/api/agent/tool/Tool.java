package com.mineagent.api.agent.tool;

import com.google.gson.JsonObject;
import com.mineagent.api.entity.AgentPlayer;

import java.util.Map;
import java.util.function.Consumer;

/**
 * An agent tool — the LLM calls this by name, and it produces a result.
 * <p>
 * Two execution modes:
 * <ul>
 *   <li><b>Synchronous</b> (query tools): call {@link #onServerCall} and reply
 *       immediately via the {@code reply} callback.</li>
 *   <li><b>Asynchronous</b> (world-action tools): call {@link #onServerCall},
 *       which dispatches a {@link com.mineagent.api.task.TaskRecord} and returns
 *       a task_id; the final result arrives later as a task_finished event.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface Tool {

    /**
     * The tool's call name — the function name the LLM uses. Must be unique
     * across all registered tools. Use snake_case (e.g. {@code scan_blocks}).
     */
    String name();

    /**
     * Human-readable description of what the tool does, its parameters, and
     * its return format. This text is included in the LLM prompt, so it
     * directly affects the model's ability to use the tool correctly.
     */
    String description();

    /**
     * JSON Schema for the tool's parameters — an object with {@code type: "object"},
     * {@code properties}, and {@code required}. Returned as a plain Java map
     * so implementations can build it with {@link Schema} helpers or by hand.
     */
    Map<String, Object> parameterSchema();

    /**
     * Whether this call reserves the companion body and completes later via a
     * task event. AgentLoop uses this contract to prevent two physical tasks
     * from being dispatched in the same model response, where the scheduler
     * could only accept one and would cancel the other as "body busy".
     */
    default boolean dispatchesAsyncTask() {
        return false;
    }

    /**
     * Execute this tool on the server thread.
     *
     * @param toolCallId the LLM-assigned ID for this tool call (for correlating
     *                   results back to the request)
     * @param args       the parsed JSON arguments from the LLM
     * @param player     the companion player entity
     * @param reply      callback to deliver the result string; call exactly once
     */
    void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                      Consumer<String> reply);

    /**
     * Default timeout in seconds for this tool's execution.
     * Sync tools (queries) should return a short timeout (5s).
     * Async tools (world actions) should return a longer timeout (60s).
     * The AgentLoop uses this to set per-tool latch await timeouts.
     */
    default int defaultTimeoutSeconds() {
        return 30;
    }
}
