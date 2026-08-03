package com.mineagent.engine.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scan for entities in an area — provides efficient entity scanning
 * for finding hostile mobs, friendly mobs, dropped items, and all
 * entities. Used by tools (ScanNearbyEntitiesTool) and survival chains
 * (MobDefenseChain, FightBackReflex, PickupItemsReflex).
 *
 * <p>Uses the level's entity lookup system (AABB-based) which is
 * already spatially indexed for efficiency.
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class EntityScanner {

    /** Maximum number of entities to return from any scan. */
    private static final int MAX_RESULTS = 128;

    private EntityScanner() {}

    // ── Scan Hostile Mobs ─────────────────────────────────────────

    /**
     * Scan for hostile mobs within a radius of the center position.
     *
     * <p>Hostile mobs are instances of {@link Monster} (zombies,
     * skeletons, creepers, spiders, etc.).
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param radius the scan radius in blocks
     * @return list of entity info for hostile mobs (limited to {@value #MAX_RESULTS})
     */
    public static List<EntityInfo> scanHostile(ServerLevel level, BlockPos center, int radius) {
        List<EntityInfo> results = new ArrayList<>();
        if (level == null || center == null || radius <= 0) return results;

        try {
            AABB box = new AABB(
                    center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                    center.getX() + radius + 1, center.getY() + radius + 1, center.getZ() + radius + 1
            );

            for (Entity entity : level.getEntities((Entity) null, box)) {
                if (!(entity instanceof Monster monster)) continue;
                if (!monster.isAlive()) continue;

                results.add(toEntityInfo(monster, true));
                if (results.size() >= MAX_RESULTS) break;
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] EntityScanner.scanHostile error: " + e.getMessage());
        }

        return results;
    }

    // ── Scan Friendly Mobs ────────────────────────────────────────

    /**
     * Scan for peaceful/friendly mobs within a radius of the center position.
     *
     * <p>Friendly mobs include animals ({@link Animal}), villagers
     * ({@link AbstractVillager}), and other non-hostile living entities.
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param radius the scan radius in blocks
     * @return list of entity info for friendly mobs
     */
    public static List<EntityInfo> scanFriendly(ServerLevel level, BlockPos center, int radius) {
        List<EntityInfo> results = new ArrayList<>();
        if (level == null || center == null || radius <= 0) return results;

        try {
            AABB box = new AABB(
                    center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                    center.getX() + radius + 1, center.getY() + radius + 1, center.getZ() + radius + 1
            );

            for (Entity entity : level.getEntities((Entity) null, box)) {
                if (!entity.isAlive()) continue;

                // Hostile mobs are excluded
                if (entity instanceof Monster) continue;

                // Only living entities (not items, projectiles, etc.)
                if (entity instanceof LivingEntity living) {
                    // Must be a peaceful mob (animal, villager, iron golem, etc.)
                    if (isFriendlyMob(living)) {
                        results.add(toEntityInfo(living, false));
                        if (results.size() >= MAX_RESULTS) break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] EntityScanner.scanFriendly error: " + e.getMessage());
        }

        return results;
    }

    // ── Scan Items ────────────────────────────────────────────────

    /**
     * Scan for dropped items within a radius of the center position.
     *
     * <p>Dropped items are instances of {@link ItemEntity} (items
     * lying on the ground).
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param radius the scan radius in blocks
     * @return list of entity info for dropped items
     */
    public static List<EntityInfo> scanItems(ServerLevel level, BlockPos center, int radius) {
        List<EntityInfo> results = new ArrayList<>();
        if (level == null || center == null || radius <= 0) return results;

        try {
            AABB box = new AABB(
                    center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                    center.getX() + radius + 1, center.getY() + radius + 1, center.getZ() + radius + 1
            );

            for (Entity entity : level.getEntities((Entity) null, box)) {
                if (!(entity instanceof ItemEntity itemEntity)) continue;
                if (!itemEntity.isAlive()) continue;

                results.add(toItemEntityInfo(itemEntity));
                if (results.size() >= MAX_RESULTS) break;
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] EntityScanner.scanItems error: " + e.getMessage());
        }

        return results;
    }

    // ── Scan All Entities ─────────────────────────────────────────

    /**
     * Scan for all entities within a radius of the center position.
     *
     * <p>This includes hostile mobs, friendly mobs, dropped items,
     * and all other entity types (minecarts, arrows, etc.).
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param radius the scan radius in blocks
     * @return list of entity info for all entities
     */
    public static List<EntityInfo> scanAll(ServerLevel level, BlockPos center, int radius) {
        List<EntityInfo> results = new ArrayList<>();
        if (level == null || center == null || radius <= 0) return results;

        try {
            AABB box = new AABB(
                    center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                    center.getX() + radius + 1, center.getY() + radius + 1, center.getZ() + radius + 1
            );

            for (Entity entity : level.getEntities((Entity) null, box)) {
                if (!entity.isAlive()) continue;

                boolean hostile = entity instanceof Monster;
                results.add(toEntityInfoGeneral(entity, hostile));
                if (results.size() >= MAX_RESULTS) break;
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] EntityScanner.scanAll error: " + e.getMessage());
        }

        return results;
    }

    // ── Records ───────────────────────────────────────────────────

    /**
     * Compact information about an entity, suitable for LLM context
     * without including full Entity references.
     *
     * @param uuid      the entity's unique ID
     * @param typeId    the entity type identifier (e.g., "minecraft:zombie")
     * @param name      the entity's display name
     * @param pos       the entity's block position
     * @param health    the entity's current health (0 for non-living)
     * @param maxHealth the entity's maximum health (0 for non-living)
     * @param hostile   whether the entity is hostile
     */
    public record EntityInfo(
            UUID uuid,
            String typeId,
            String name,
            BlockPos pos,
            float health,
            float maxHealth,
            boolean hostile
    ) {}

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Convert a living entity to EntityInfo.
     */
    private static EntityInfo toEntityInfo(LivingEntity entity, boolean hostile) {
        return new EntityInfo(
                entity.getUUID(),
                entity.getType().getDescriptionId(),
                entity.getName().getString(),
                entity.blockPosition(),
                entity.getHealth(),
                entity.getMaxHealth(),
                hostile
        );
    }

    /**
     * Convert an item entity to EntityInfo.
     */
    private static EntityInfo toItemEntityInfo(ItemEntity itemEntity) {
        var stack = itemEntity.getItem();
        String name = stack.isEmpty() ? "Unknown Item" : stack.getHoverName().getString();
        return new EntityInfo(
                itemEntity.getUUID(),
                "minecraft:item",
                name + (stack.getCount() > 1 ? " x" + stack.getCount() : ""),
                itemEntity.blockPosition(),
                0,
                0,
                false
        );
    }

    /**
     * Convert any entity to EntityInfo (general purpose).
     */
    private static EntityInfo toEntityInfoGeneral(Entity entity, boolean hostile) {
        float health = 0;
        float maxHealth = 0;
        if (entity instanceof LivingEntity living) {
            health = living.getHealth();
            maxHealth = living.getMaxHealth();
        }
        return new EntityInfo(
                entity.getUUID(),
                entity.getType().getDescriptionId(),
                entity.getName().getString(),
                entity.blockPosition(),
                health,
                maxHealth,
                hostile
        );
    }

    /**
     * Check if a living entity is a friendly/peaceful mob.
     */
    private static boolean isFriendlyMob(LivingEntity entity) {
        return entity instanceof Animal
                || entity instanceof AbstractVillager
                || entity instanceof net.minecraft.world.entity.animal.IronGolem
                || entity instanceof net.minecraft.world.entity.animal.SnowGolem;
    }
}
