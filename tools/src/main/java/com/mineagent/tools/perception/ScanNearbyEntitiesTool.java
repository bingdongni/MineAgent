package com.mineagent.tools.perception;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.AgeableMob;

/**
 * List entities within a radius of the companion. Returns entity IDs,
 * types, positions, and health (for living entities).
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class ScanNearbyEntitiesTool implements Tool {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 64;
    private static final int MAX_RESULTS = 50;

    @Override
    public String name() { return "scan_nearby_entities"; }

    @Override
    public String description() {
        return """
            List entities within a radius of your position. Returns each
            entity's ID, type, position, and health (for living entities).
            Useful for finding mobs, animals, villagers, and dropped items.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("radius", "Scan radius in blocks (1-64, default 16)", 1, 64)
                .optionalString("entity_type", "Filter by entity type (e.g. 'minecraft:zombie'). If null, returns all entity types.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        Integer parsedRadius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : DEFAULT_RADIUS;
        if (parsedRadius == null || parsedRadius < 1 || parsedRadius > MAX_RADIUS) {
            reply.accept("{\"error\":\"radius must be an integer between 1 and 64.\"}");
            return;
        }
        int radius = parsedRadius;
        String entityType = ToolArgs.getString(args, "entity_type");
        if (entityType != null) {
            var entityId = net.minecraft.resources.ResourceLocation.tryParse(entityType);
            if (entityId == null
                    || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .containsKey(entityId)) {
                reply.accept(ToolArgs.errorJson("Unknown entity type: " + entityType));
                return;
            }
            entityType = entityId.toString();
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var pos = sp.position();
        double radiusSq = (double) radius * radius;

        var aabb = sp.getBoundingBox().inflate(radius);
        var entities = level.getEntities(sp, aabb, e -> true);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ENTITY SCAN (radius ").append(radius).append(") ===\n");

        int count = 0;
        int threatCount = 0;
        int playerInDanger = 0;

        for (var entity : entities) {
            if (count >= MAX_RESULTS) break;

            double distSq = entity.distanceToSqr(pos.x, pos.y, pos.z);
            if (distSq > radiusSq) continue;

            var entityTypeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()).toString();
            String cleanName = entityTypeId.replace("minecraft:", "");

            if (entityType != null && !entityTypeId.equals(entityType)) continue;

            // Direction
            double dx = entity.getX() - sp.getX();
            double dz = entity.getZ() - sp.getZ();
            String direction = getDirection(dx, dz);
            double dist = Math.sqrt(distSq);

            sb.append(String.format("%d. %s at (%.0f,%.0f,%.0f) %s dist=%.1f",
                    count + 1, cleanName, entity.getX(), entity.getY(), entity.getZ(),
                    direction, dist));

            // Health
            if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                sb.append(String.format(" HP=%.0f/%.0f", living.getHealth(), living.getMaxHealth()));
            }

            // Threat assessment
            boolean isHostile = entity instanceof net.minecraft.world.entity.monster.Monster;
            if (isHostile) {
                sb.append(" [HOSTILE]");
                threatCount++;
            }

            // Activity & Target
            if (entity instanceof net.minecraft.world.entity.Mob mob) {
                var target = mob.getTarget();
                if (target != null) {
                    String targetName;
                    if (target instanceof Player p) {
                        targetName = p.getName().getString();
                        playerInDanger++;
                        sb.append(" >> ATTACKING: ").append(targetName);
                        sb.append(String.format(" (HP=%.0f/%.0f)", p.getHealth(), p.getMaxHealth()));
                    } else {
                        targetName = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getKey(target.getType()).toString().replace("minecraft:", "");
                        sb.append(" >> ATTACKING: ").append(targetName);
                    }
                }
                if (mob.isAggressive()) sb.append(" [AGGRESSIVE]");
            }

            // Player status
            if (entity instanceof Player p) {
                if (p.hurtTime > 0) sb.append(" [TAKING DAMAGE]");
                if (p.getHealth() < p.getMaxHealth() * 0.3f) sb.append(" [CRITICAL]");
            }

            // Special states
            if (entity.isOnFire()) sb.append(" [BURNING]");
            if (entity.isInWater()) sb.append(" [IN WATER]");
            if (entity instanceof net.minecraft.world.entity.AgeableMob ageable && ageable.isBaby()) {
                sb.append(" [BABY]");
            }

            sb.append("\n");
            count++;
        }

        sb.append("\n--- SUMMARY ---\n");
        sb.append(String.format("Total: %d entities | Hostile: %d | Players in danger: %d\n",
                count, threatCount, playerInDanger));

        if (playerInDanger > 0) {
            sb.append("** WARNING: Player is under attack! **\n");
        }
        if (threatCount > 3) {
            sb.append("** CAUTION: Multiple hostiles nearby. Consider retreating. **\n");
        }

        reply.accept(sb.toString());
    }

    private String getDirection(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        if (angle >= 337.5 || angle < 22.5) return "N";
        if (angle < 67.5) return "NE";
        if (angle < 112.5) return "E";
        if (angle < 157.5) return "SE";
        if (angle < 202.5) return "S";
        if (angle < 247.5) return "SW";
        if (angle < 292.5) return "W";
        return "NW";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
