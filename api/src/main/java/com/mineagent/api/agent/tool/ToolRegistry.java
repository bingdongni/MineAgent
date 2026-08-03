package com.mineagent.api.agent.tool;

import java.util.*;

/**
 * Global registry of {@link Tool} instances. Tools are registered in order
 * (order is preserved for prompt-caching stability) and looked up by name.
 *
 * <p>Thread-safe for registration (init-time) and read-only access (runtime).
 */
public final class ToolRegistry {

    private static final LinkedHashMap<String, Tool> TOOLS = new LinkedHashMap<>();

    private ToolRegistry() {}

    /** Register a tool. Throws if a tool with the same name already exists. */
    public static void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        Tool prev = TOOLS.putIfAbsent(tool.name(), tool);
        if (prev != null) {
            throw new IllegalStateException("Tool already registered: " + tool.name());
        }
    }

    /** Look up a tool by name. Returns empty if not found. */
    public static Optional<Tool> get(String name) {
        return Optional.ofNullable(TOOLS.get(name));
    }

    /** All registered tools, in registration order. */
    public static Collection<Tool> all() {
        return Collections.unmodifiableCollection(TOOLS.values());
    }

    /** Number of registered tools. */
    public static int size() {
        return TOOLS.size();
    }

    /** Remove all registered tools (for testing). */
    public static void clear() {
        TOOLS.clear();
    }
}
