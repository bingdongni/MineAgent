package com.mineagent.tools.perception;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Returns a bounded, structured observation grounded in current server state. */
public final class LookAroundTool implements Tool {
    private static final Gson GSON = new Gson();
    private static final int MAX_ENTITY_DETAILS = 12;
    private static final int MAX_NOTABLE_BLOCKS = 20;

    @Override public String name() { return "look_around"; }

    @Override
    public String description() {
        return """
                Observe the current server world as structured JSON. Returns
                self state, immediate threats, nearby living entities, a
                vertical profile, notable 3-D blocks, and a compact local map.
                Use this before acting when the environment is uncertain.
                """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("radius", "Observation radius in blocks (4-16, default 8)", 4, 16)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer agent,
                             Consumer<String> reply) {
        Integer radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 8;
        if (radius == null || radius < 4 || radius > 16) {
            reply.accept(ToolArgs.errorJson("'radius' must be an integer from 4 to 16."));
            return;
        }
        reply.accept(generatePerception(agent, radius));
    }

    private String generatePerception(AgentPlayer agent, int radius) {
        var player = ((CompanionEntity) agent).serverPlayer();
        Level level = player.level();
        BlockPos origin = player.blockPosition();
        long tick = level.getGameTime();

        JsonObject root = new JsonObject();
        root.addProperty("observation_id", level.dimension().location() + "@" + tick);
        root.addProperty("radius", radius);
        root.add("self", selfJson(player));
        root.add("vertical_profile", verticalJson(level, origin));

        AABB scanArea = new AABB(
                origin.getX() - radius, origin.getY() - 4, origin.getZ() - radius,
                origin.getX() + radius + 1, origin.getY() + 6,
                origin.getZ() + radius + 1);
        List<net.minecraft.world.entity.Entity> nearby = level
                .getEntitiesOfClass(net.minecraft.world.entity.Entity.class, scanArea,
                        entity -> entity != player && entity instanceof LivingEntity)
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).toList();

        JsonArray entities = new JsonArray();
        JsonArray threats = new JsonArray();
        int omittedEntities = 0;
        for (var entity : nearby) {
            double distance = Math.sqrt(entity.distanceToSqr(player));
            if (distance > radius + 2.0) continue;
            if (entities.size() >= MAX_ENTITY_DETAILS) {
                omittedEntities++;
                continue;
            }
            JsonObject detail = entityJson(player, entity, distance);
            entities.add(detail);
            if (detail.get("immediate_threat").getAsBoolean()) {
                JsonObject threat = new JsonObject();
                threat.addProperty("uuid", entity.getUUID().toString());
                threat.addProperty("type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                threat.addProperty("distance", round(distance));
                threat.addProperty("line_of_sight", true);
                if (entity instanceof Mob mob && mob.getTarget() != null) {
                    threat.addProperty("target_uuid", mob.getTarget().getUUID().toString());
                }
                threats.add(threat);
            }
        }
        // Threats precede general entities so safety survives any later
        // provider-side context reduction.
        root.add("immediate_threats", threats);
        root.add("entities", entities);
        root.addProperty("omitted_entity_count", omittedEntities);

        List<NotableBlock> notable = scanNotableBlocks(level, origin, radius);
        JsonArray notableJson = new JsonArray();
        int limit = Math.min(MAX_NOTABLE_BLOCKS, notable.size());
        for (int i = 0; i < limit; i++) notableJson.add(notable.get(i).toJson());
        root.add("notable_blocks", notableJson);
        root.addProperty("omitted_notable_block_count", Math.max(0, notable.size() - limit));
        root.add("local_map", localMapJson(level, origin, Math.min(radius, 8)));

        var loop = TaskContext.agentLoop(agent);
        if (loop != null) {
            loop.beliefState().observeFact("self", "last_observed_position",
                    level.dimension().location() + ":" + origin.toShortString(),
                    1.0, "look_around", tick);
            loop.beliefState().observeFact("local_area", "immediate_threats",
                    Integer.toString(threats.size()), 0.95, "look_around", tick);
            loop.beliefState().observeFact("local_area", "notable_blocks",
                    Integer.toString(notable.size()), 0.9, "look_around", tick);
        }
        return GSON.toJson(root);
    }

    private static JsonObject selfJson(net.minecraft.server.level.ServerPlayer player) {
        JsonObject self = new JsonObject();
        self.add("position", positionJson(player.blockPosition()));
        self.addProperty("health", round(player.getHealth()));
        self.addProperty("max_health", round(player.getMaxHealth()));
        self.addProperty("food", player.getFoodData().getFoodLevel());
        self.addProperty("air", player.getAirSupply());
        self.addProperty("on_ground", player.onGround());
        self.addProperty("in_water", player.isInWater());
        self.addProperty("facing", facing(player));
        self.addProperty("main_hand", player.getMainHandItem().isEmpty()
                ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
        return self;
    }

    private static JsonObject verticalJson(Level level, BlockPos feet) {
        JsonObject vertical = new JsonObject();
        vertical.addProperty("feet_block", blockId(level.getBlockState(feet)));
        vertical.addProperty("head_block", blockId(level.getBlockState(feet.above())));
        vertical.addProperty("support_block", blockId(level.getBlockState(feet.below())));
        vertical.addProperty("clearance_blocks", clearanceAbove(level, feet));
        vertical.addProperty("drop_depth_below", dropDepth(level, feet, 12));
        return vertical;
    }

    private static JsonObject entityJson(net.minecraft.server.level.ServerPlayer observer,
                                         net.minecraft.world.entity.Entity entity,
                                         double distance) {
        JsonObject detail = new JsonObject();
        detail.addProperty("uuid", entity.getUUID().toString());
        detail.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        detail.add("position", positionJson(entity.blockPosition()));
        detail.addProperty("distance", round(distance));
        detail.addProperty("direction", direction(entity.getX() - observer.getX(),
                entity.getZ() - observer.getZ()));
        detail.addProperty("line_of_sight", observer.hasLineOfSight(entity));
        detail.addProperty("activity", activity(entity));
        if (entity instanceof LivingEntity living) {
            detail.addProperty("health", round(living.getHealth()));
            detail.addProperty("max_health", round(living.getMaxHealth()));
        }
        if (entity instanceof Mob mob && mob.getTarget() != null) {
            detail.addProperty("target_uuid", mob.getTarget().getUUID().toString());
            detail.addProperty("target_name", mob.getTarget() instanceof Player targetPlayer
                    ? targetPlayer.getName().getString()
                    : BuiltInRegistries.ENTITY_TYPE.getKey(mob.getTarget().getType()).toString());
        }
        detail.addProperty("immediate_threat", entity instanceof Monster
                && observer.hasLineOfSight(entity) && distance <= 10.0);
        return detail;
    }

    private record NotableBlock(BlockPos position, String kind,
                                String block, double distance) {
        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("kind", kind);
            result.addProperty("block", block);
            result.add("position", positionJson(position));
            result.addProperty("distance", round(distance));
            return result;
        }
    }

    private static List<NotableBlock> scanNotableBlocks(Level level, BlockPos origin, int radius) {
        List<NotableBlock> result = new ArrayList<>();
        for (int dy = -4; dy <= 6; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    String kind = null;
                    if (isDanger(state)) kind = "hazard";
                    else if (isOre(state)) kind = "ore";
                    else if (state.is(BlockTags.LOGS)) kind = "log";
                    else if (state.is(Blocks.WATER) && Math.abs(dy) <= 1) kind = "water";
                    if (kind != null) {
                        result.add(new NotableBlock(pos.immutable(), kind, blockId(state),
                                Math.sqrt(pos.distSqr(origin))));
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(NotableBlock::distance));
        return result;
    }

    private static JsonObject localMapJson(Level level, BlockPos origin, int radius) {
        JsonArray rows = new JsonArray();
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder(radius * 2 + 1);
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dz == 0) {
                    row.append('@');
                    continue;
                }
                BlockPos cell = origin.offset(dx, 0, dz);
                row.append(mapGlyph(level, cell, level.getBlockState(cell),
                        level.getBlockState(cell.above()), level.getBlockState(cell.below())));
            }
            rows.add(row.toString());
        }
        JsonObject map = new JsonObject();
        map.addProperty("orientation", "north(-Z) to south(+Z)");
        map.addProperty("radius", radius);
        map.add("rows", rows);
        map.addProperty("legend",
                "@ self, . open, ^ raised, , one-down, v drop, # blocked, ~ water, L lava, ! hazard, T log, I/C/D/G/R/E/B/Q/N ores");
        return map;
    }

    private static char mapGlyph(Level level, BlockPos pos, BlockState feet,
                                 BlockState head, BlockState support) {
        if (isDanger(feet)) return '!';
        if (feet.is(Blocks.WATER)) return '~';
        if (feet.is(Blocks.LAVA)) return 'L';
        if (feet.is(BlockTags.LOGS)) return 'T';
        if (isOre(feet)) return oreGlyph(feet);
        if (!feet.getCollisionShape(level, pos).isEmpty()
                || !head.getCollisionShape(level, pos.above()).isEmpty()) return '#';
        if (support.getCollisionShape(level, pos.below()).isEmpty()) {
            return dropDepth(level, pos, 4) >= 2 ? 'v' : ',';
        }
        return '.';
    }

    private static int clearanceAbove(Level level, BlockPos feet) {
        int clear = 0;
        for (int offset = 0; offset < 6; offset++) {
            BlockPos pos = feet.above(offset);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) break;
            clear++;
        }
        return clear;
    }

    private static int dropDepth(Level level, BlockPos feet, int limit) {
        int depth = 0;
        for (int offset = 1; offset <= limit; offset++) {
            BlockPos pos = feet.below(offset);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) break;
            depth++;
        }
        return depth;
    }

    private static String activity(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof Player player) {
            if (player.isSleeping()) return "sleeping";
            if (player.isSprinting()) return "sprinting";
            if (player.isCrouching()) return "sneaking";
            if (player.isSwimming()) return "swimming";
            if (player.hurtTime > 0) return "hurt";
            return "active";
        }
        if (entity instanceof Mob mob) {
            if (mob.getTarget() != null) return "attacking";
            if (mob.isAggressive()) return "aggressive";
            if (mob.hurtTime > 0) return "hurt";
            return "idle";
        }
        return "unknown";
    }

    private static String direction(double dx, double dz) {
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

    private static String facing(net.minecraft.server.level.ServerPlayer player) {
        float yaw = ((player.getYRot() % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return "south(+Z)";
        if (yaw < 135) return "west(-X)";
        if (yaw < 225) return "north(-Z)";
        return "east(+X)";
    }

    private static JsonObject positionJson(BlockPos pos) {
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        return result;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static boolean isDanger(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.EMERALD_ORES)
                || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES)
                || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE)
                || state.is(Blocks.ANCIENT_DEBRIS);
    }

    private static char oreGlyph(BlockState state) {
        if (state.is(BlockTags.IRON_ORES)) return 'I';
        if (state.is(BlockTags.COAL_ORES)) return 'C';
        if (state.is(BlockTags.DIAMOND_ORES)) return 'D';
        if (state.is(BlockTags.GOLD_ORES) || state.is(Blocks.NETHER_GOLD_ORE)) return 'G';
        if (state.is(BlockTags.REDSTONE_ORES)) return 'R';
        if (state.is(BlockTags.EMERALD_ORES)) return 'E';
        if (state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.COPPER_ORES)) return 'B';
        if (state.is(Blocks.NETHER_QUARTZ_ORE)) return 'Q';
        if (state.is(Blocks.ANCIENT_DEBRIS)) return 'N';
        return 'O';
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
