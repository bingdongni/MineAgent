package com.mineagent.engine.telemetry;

import com.mineagent.api.llm.LLMResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, local-only counters used to tune the cognition runtime.
 *
 * <p>This deliberately does not send telemetry anywhere and never stores API
 * keys, prompts, or raw world dumps.  It gives the later phases a stable
 * measurement surface for token efficiency, cache behavior, action reliability
 * and memory recall without changing gameplay behavior.</p>
 */
public final class CognitionMetrics {
    public enum ActionOutcome { SUCCEEDED, FAILED, INVALID, CANCELLED, UNVERIFIED }
    private static final int MAX_TRACKED_ACTIONS = 2_048;
    private final java.util.LinkedHashSet<String> pendingActions = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> terminalActions = new java.util.LinkedHashSet<>();
    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder cancelledActions = new LongAdder();
    private final LongAdder unverifiedActions = new LongAdder();
    private final LongAdder llmCalls = new LongAdder();
    private final LongAdder llmFailures = new LongAdder();
    private final LongAdder llmLatencyNanos = new LongAdder();
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder cachedInputTokens = new LongAdder();
    private final LongAdder cacheWriteTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder actions = new LongAdder();
    private final LongAdder successfulActions = new LongAdder();
    private final LongAdder failedActions = new LongAdder();
    private final LongAdder invalidActions = new LongAdder();
    private final LongAdder memoryQueries = new LongAdder();
    private final LongAdder memoryUsefulHits = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> failureKinds = new ConcurrentHashMap<>();

    public void recordLlm(LLMResponse response, long latencyNanos, boolean failed) {
        llmCalls.increment();
        llmLatencyNanos.add(Math.max(0L, latencyNanos));
        if (failed) llmFailures.increment();
        if (response == null || response.usage() == null) return;
        LLMResponse.Usage usage = response.usage();
        inputTokens.add(Math.max(0, usage.promptTokens()));
        cachedInputTokens.add(Math.max(0, usage.cachedPromptTokens()));
        cacheWriteTokens.add(Math.max(0, usage.cacheCreationPromptTokens()));
        outputTokens.add(Math.max(0, usage.completionTokens()));
    }

    public void recordDecisionCache(boolean hit) {
        if (hit) cacheHits.increment(); else cacheMisses.increment();
    }

    public void recordAction(boolean accepted, boolean success, boolean invalid,
                              String failureKind) {
        actions.increment();
        if (accepted && success) successfulActions.increment();
        if (!success) failedActions.increment();
        if (invalid) invalidActions.increment();
        if (!success && failureKind != null && !failureKind.isBlank()) {
            failureKinds.computeIfAbsent(failureKind.trim(), ignored -> new LongAdder())
                    .increment();
        }
    }

    public void recordToolCall() { toolCalls.increment(); }

    public synchronized void recordActionDispatched(String actionId) {
        if (actionId == null || actionId.isBlank() || terminalActions.contains(actionId)) return;
        pendingActions.add(actionId);
        while (pendingActions.size() > MAX_TRACKED_ACTIONS) {
            pendingActions.remove(pendingActions.iterator().next());
        }
    }

    /** Count authoritative action terminals once; dispatch/query success is not body success. */
    public synchronized boolean recordActionOutcome(String actionId, ActionOutcome outcome,
                                                     String failureKind) {
        if (actionId == null || actionId.isBlank() || outcome == null
                || !terminalActions.add(actionId)) return false;
        pendingActions.remove(actionId);
        while (terminalActions.size() > MAX_TRACKED_ACTIONS) {
            terminalActions.remove(terminalActions.iterator().next());
        }
        switch (outcome) {
            case SUCCEEDED -> recordAction(true, true, false, null);
            case FAILED -> recordAction(true, false, false, failureKind);
            case INVALID -> recordAction(false, false, true, failureKind);
            case CANCELLED -> cancelledActions.increment();
            case UNVERIFIED -> unverifiedActions.increment();
        }
        return true;
    }

    public record Operations(long toolCalls, int pendingActions, long cancelledActions,
                             long unverifiedActions) {}

    public synchronized Operations operations() {
        return new Operations(toolCalls.sum(), pendingActions.size(),
                cancelledActions.sum(), unverifiedActions.sum());
    }

    public void recordMemoryQuery(boolean useful) {
        memoryQueries.increment();
        if (useful) memoryUsefulHits.increment();
    }

    public Snapshot snapshot() {
        long calls = llmCalls.sum();
        long totalInput = inputTokens.sum();
        long cached = cachedInputTokens.sum();
        long actionCount = actions.sum();
        long queryCount = memoryQueries.sum();
        return new Snapshot(calls, llmFailures.sum(), llmLatencyNanos.sum(),
                totalInput, cached, cacheWriteTokens.sum(), outputTokens.sum(),
                cacheHits.sum(), cacheMisses.sum(), actionCount,
                successfulActions.sum(), failedActions.sum(), invalidActions.sum(),
                queryCount, memoryUsefulHits.sum(),
                totalInput <= 0 ? 0.0 : (double) cached / totalInput,
                calls <= 0 ? 0.0 : (double) llmLatencyNanos.sum() / calls,
                actionCount <= 0 ? 0.0 : (double) successfulActions.sum() / actionCount,
                queryCount <= 0 ? 0.0 : (double) memoryUsefulHits.sum() / queryCount,
                failureKinds.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().sum())));
    }

    public record Snapshot(long llmCalls, long llmFailures, long llmLatencyNanos,
                           long inputTokens, long cachedInputTokens,
                           long cacheWriteTokens, long outputTokens,
                           long decisionCacheHits, long decisionCacheMisses,
                           long actions, long successfulActions, long failedActions,
                           long invalidActions, long memoryQueries,
                           long usefulMemoryHits, double providerCacheHitRate,
                           double averageLlmLatencyNanos,
                           double actionSuccessRate, double memoryHitRate,
                           Map<String, Long> failureKinds) {
        public long averageLlmLatencyMillis() {
            return Math.round(averageLlmLatencyNanos / 1_000_000.0);
        }
    }
}
