package com.mineagent.engine.planning;

import java.util.List;
import java.util.Objects;

/**
 * A task-level contract shared by planning, navigation and verification.
 *
 * <p>Global pathfinding switches cannot express intents such as "reach the
 * tree but do not pillar" or "the target block may be mined, every other
 * block is protected". This contract keeps those decisions local to the task
 * that understands their meaning.
 */
public record IntentContract(
        String goal,
        String successCriterion,
        Integer targetX,
        Integer targetY,
        Integer targetZ,
        TerrainPolicy terrainPolicy,
        List<Constraint> constraints
) {
    public IntentContract {
        goal = normalize(goal, "Complete the current task");
        successCriterion = normalize(successCriterion, "Executor reports verified success");
        terrainPolicy = terrainPolicy == null ? TerrainPolicy.CONSERVATIVE : terrainPolicy;
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public static IntentContract generic(String goal, String criterion,
                                         Integer x, Integer y, Integer z) {
        return new IntentContract(goal, criterion, x, y, z,
                TerrainPolicy.CONSERVATIVE, List.of());
    }

    public enum CleanupMode {
        /** Decide from live risk, scarcity, reversibility and owner preference. */
        CONTEXTUAL,
        /** Recovery is part of task completion unless a hard safety rule blocks it. */
        REQUIRED,
        /** Deliberately leave task-created supports in place. */
        LEAVE
    }

    public enum ConstraintKind { HARD, PREFERENCE }

    public record Constraint(String id, ConstraintKind kind,
                             String description, String scope) {
        public Constraint {
            id = normalize(id, "constraint");
            kind = kind == null ? ConstraintKind.PREFERENCE : kind;
            description = normalize(description, "Unspecified constraint");
            scope = normalize(scope, "task");
        }
    }

    public record TerrainPolicy(
            boolean allowBreakingObstacles,
            boolean allowBridge,
            boolean allowPillar,
            boolean allowParkour,
            int maxPlacedBlocks,
            int maxBrokenObstacles,
            int maxUpwardDeviation,
            CleanupMode cleanupMode
    ) {
        public static final TerrainPolicy CONSERVATIVE = new TerrainPolicy(
                false, false, false, false, 0, 0, 2, CleanupMode.CONTEXTUAL);

        public TerrainPolicy {
            maxPlacedBlocks = Math.max(0, maxPlacedBlocks);
            maxBrokenObstacles = Math.max(0, maxBrokenObstacles);
            maxUpwardDeviation = Math.max(0, maxUpwardDeviation);
            cleanupMode = Objects.requireNonNullElse(cleanupMode, CleanupMode.CONTEXTUAL);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
