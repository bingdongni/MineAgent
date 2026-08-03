package com.mineagent.api.task;

import com.mineagent.api.entity.AgentPlayer;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Factory that pairs each {@link TaskRecord} type with its
 * {@link CompanionTask} runner.
 */
public final class CompanionTaskFactory {

    private static final Map<Class<? extends TaskRecord>,
            BiFunction<AgentPlayer, ? extends TaskRecord, ? extends CompanionTask<?>>> FACTORIES =
            new HashMap<>();

    private CompanionTaskFactory() {}

    /**
     * Register a task runner for a specific TaskRecord type.
     *
     * @param recordType the concrete TaskRecord class
     * @param factory    a function (player, record) → new CompanionTask
     */
    public static <R extends TaskRecord> void register(
            Class<R> recordType,
            BiFunction<AgentPlayer, R, ? extends CompanionTask<R>> factory) {
        FACTORIES.put(recordType, factory);
    }

    /**
     * Create a CompanionTask for the given record.
     *
     * @throws IllegalArgumentException if no runner is registered for this record type
     */
    @SuppressWarnings("unchecked")
    public static <R extends TaskRecord> CompanionTask<R> create(
            AgentPlayer player, R record) {
        var factory = FACTORIES.get(record.getClass());
        if (factory == null) {
            throw new IllegalArgumentException(
                    "No task runner registered for " + record.getClass().getSimpleName());
        }
        return ((BiFunction<AgentPlayer, R, CompanionTask<R>>) factory).apply(player, record);
    }

    /** Number of registered task types. */
    public static int size() {
        return FACTORIES.size();
    }

    /** Remove all registered factories (for testing). */
    public static void clear() {
        FACTORIES.clear();
    }
}
