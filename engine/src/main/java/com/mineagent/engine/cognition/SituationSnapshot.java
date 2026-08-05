package com.mineagent.engine.cognition;

import com.mineagent.api.task.TaskState;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Immutable evidence frame published by the server thread.
 *
 * <p>The LLM thread must never inspect live entities, levels or inventories.
 * This record contains only compact values copied during a server tick, so the
 * tactical layer can reason continuously without crossing Minecraft's thread
 * ownership boundary.
 */
public record SituationSnapshot(
        long gameTick,
        Position self,
        Vitals vitals,
        Environment environment,
        OwnerObservation owner,
        TaskObservation task,
        List<ActorObservation> actors
) {
    public enum ActorKind {
        OWNER,
        ALLIED_COMPANION,
        SAME_TEAM_PLAYER,
        OTHER_PLAYER,
        HOSTILE_MOB,
        PASSIVE_MOB,
        PROJECTILE,
        DROPPED_ITEM,
        OTHER
    }

    public record Position(String dimension, double x, double y, double z) {
        public Position {
            dimension = normalize(dimension, "minecraft:overworld");
            x = finite(x);
            y = finite(y);
            z = finite(z);
        }

        public double distanceTo(Position other) {
            if (other == null || !dimension.equals(other.dimension())) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = x - other.x();
            double dy = y - other.y();
            double dz = z - other.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String compact() {
            return dimension + ":" + Math.round(x) + "," + Math.round(y)
                    + "," + Math.round(z);
        }
    }

    public record Vitals(float health, float maxHealth, int food, int air,
                         boolean inWater, boolean inLava, boolean onFire,
                         boolean onGround, float fallDistance,
                         boolean armed, int armorPieces) {
        public Vitals {
            health = finite(health);
            maxHealth = Math.max(1.0f, finite(maxHealth));
            food = Math.max(0, food);
            air = Math.max(-20, air);
            fallDistance = Math.max(0.0f, finite(fallDistance));
            armorPieces = Math.max(0, Math.min(4, armorPieces));
        }

        public double healthRatio() {
            return Math.max(0.0, Math.min(1.0, health / maxHealth));
        }
    }

    public record Environment(int immediateHazards, int dropDepth,
                              int nearbyProjectiles, int nearbyDrops,
                              boolean breathableEyeCell) {
        public Environment {
            immediateHazards = Math.max(0, immediateHazards);
            dropDepth = Math.max(0, dropDepth);
            nearbyProjectiles = Math.max(0, nearbyProjectiles);
            nearbyDrops = Math.max(0, nearbyDrops);
        }
    }

    public record OwnerObservation(boolean present, Position position,
                                   float health, float maxHealth,
                                   boolean hurt, boolean sprinting,
                                   String activity, double distance) {
        public OwnerObservation {
            health = finite(health);
            maxHealth = Math.max(1.0f, finite(maxHealth));
            activity = normalize(activity, "unknown");
            distance = Double.isFinite(distance)
                    ? Math.max(0.0, distance) : Double.POSITIVE_INFINITY;
        }

        public double healthRatio() {
            return present ? Math.max(0.0, Math.min(1.0, health / maxHealth)) : 1.0;
        }

        public static OwnerObservation absent() {
            return new OwnerObservation(false, null, 0.0f, 1.0f,
                    false, false, "offline", Double.POSITIVE_INFINITY);
        }
    }

    public record TaskObservation(String taskId, String taskName,
                                  TaskState state, String stage,
                                  String blockedReason, long progressVersion) {
        public TaskObservation {
            taskId = blankToNull(taskId);
            taskName = normalize(taskName, taskId == null ? "idle" : "body_task");
            stage = normalize(stage, taskId == null ? "idle" : "running");
            blockedReason = blankToNull(blockedReason);
            progressVersion = Math.max(0L, progressVersion);
        }

        public boolean active() {
            return taskId != null && (state == TaskState.RUNNING || state == TaskState.PAUSED);
        }

        public boolean blocked() {
            return blockedReason != null;
        }

        public static TaskObservation idle() {
            return new TaskObservation(null, "idle", null, "idle", null, 0L);
        }
    }

    public record ActorObservation(UUID id, String type, ActorKind kind,
                                   Position position, double distance,
                                   float health, float maxHealth,
                                   boolean visible, boolean targetingSelf,
                                   boolean targetingOwner, boolean approaching,
                                   String activity) {
        public ActorObservation {
            type = normalize(type, "minecraft:unknown").toLowerCase(Locale.ROOT);
            kind = kind == null ? ActorKind.OTHER : kind;
            distance = Double.isFinite(distance)
                    ? Math.max(0.0, distance) : Double.POSITIVE_INFINITY;
            health = Math.max(0.0f, finite(health));
            maxHealth = Math.max(0.0f, finite(maxHealth));
            activity = normalize(activity, "unknown");
        }

        public boolean immediateThreat() {
            return (kind == ActorKind.HOSTILE_MOB
                    && (targetingSelf || targetingOwner || distance <= 4.0))
                    || (kind == ActorKind.PROJECTILE && approaching && distance <= 10.0);
        }
    }

    public SituationSnapshot {
        gameTick = Math.max(0L, gameTick);
        if (self == null) {
            self = new Position("minecraft:overworld", 0.0, 0.0, 0.0);
        }
        if (vitals == null) {
            vitals = new Vitals(20.0f, 20.0f, 20, 300,
                    false, false, false, true, 0.0f, false, 0);
        }
        if (environment == null) {
            environment = new Environment(0, 0, 0, 0, true);
        }
        if (owner == null) owner = OwnerObservation.absent();
        if (task == null) task = TaskObservation.idle();
        actors = actors == null ? List.of() : List.copyOf(actors);
    }

    public List<ActorObservation> immediateThreats() {
        return actors.stream().filter(ActorObservation::immediateThreat).toList();
    }

    public int threatsToOwner() {
        return (int) actors.stream().filter(ActorObservation::targetingOwner).count();
    }

    public int unknownPlayers() {
        return (int) actors.stream()
                .filter(actor -> actor.kind() == ActorKind.OTHER_PLAYER).count();
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
