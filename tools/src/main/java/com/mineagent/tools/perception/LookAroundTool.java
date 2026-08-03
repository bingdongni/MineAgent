package com.mineagent.tools.perception;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Look around — provides a RICH, DETAILED perception of the companion's surroundings.
 *
 * <p>Returns TWO layers of information:
 * <ol>
 *   <li><b>Terrain grid</b> — a character grid with height/terrain encoding</li>
 *   <li><b>Detailed entity list</b> — every entity with its exact type, position,
 *       health, and current ACTIVITY (what it's doing right now)</li>
 * </ol>
 *
 * <p>The entity list includes what each mob is targeting — so the AI can see
 * "Zombie is attacking Player1" or "Creeper is targeting Player1" and react
 * intelligently without being told.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class LookAroundTool implements Tool {

    @Override
    public String name() { return "look_around"; }

    @Override
    public String description() {
        return """
            Perceive your surroundings in detail. Returns a terrain grid AND
            a detailed list of all nearby entities with their type, position,
            health, and current activity (what they're doing right now).

            The entity list tells you:
            - WHO is nearby (exact mob/animal/player type)
            - WHERE they are (coordinates + direction from you)
            - WHAT they're doing (idle, walking, attacking, fleeing, sleeping)
            - WHO they're targeting (if a mob is attacking someone)
            - Their health and distance from you

            This is your EYES. Use it constantly to understand what's happening.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("radius", "View radius in blocks (4-16, default 8)", 4, 16)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        Integer parsedRadius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 8;
        if (parsedRadius == null || parsedRadius < 4 || parsedRadius > 16) {
            reply.accept("{\"error\":\"radius must be an integer between 4 and 16.\"}");
            return;
        }
        int radius = parsedRadius;

        String result = generatePerception(player, radius);
        reply.accept(result);
    }

    private String generatePerception(AgentPlayer player, int radius) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var pos = sp.blockPosition();
        var level = sp.level();
        StringBuilder sb = new StringBuilder();

        int selfX = pos.getX();
        int selfY = pos.getY();
        int selfZ = pos.getZ();

        // ── Layer 1: Terrain Grid ──
        sb.append("=== TERRAIN GRID (radius ").append(radius).append(") ===\n");
        sb.append("```\n");
        sb.append("      N (north)\n");

        int size = radius * 2 + 1;
        char[][] grid = new char[size][size];

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int bx = selfX + dx;
                int bz = selfZ + dz;
                int by = selfY;

                int gridX = dx + radius;
                int gridZ = dz + radius;

                if (dx == 0 && dz == 0) {
                    grid[gridZ][gridX] = '@';
                    continue;
                }

                var blockState = level.getBlockState(new BlockPos(bx, by, bz));
                var blockAbove = level.getBlockState(new BlockPos(bx, by + 1, bz));
                var blockBelow = level.getBlockState(new BlockPos(bx, by - 1, bz));

                char c;
                if (isDanger(blockState)) {
                    c = '!';
                } else if (blockState.is(Blocks.WATER)) {
                    c = '~';
                } else if (blockState.is(Blocks.LAVA)) {
                    c = 'L';
                } else if (isTreeTrunk(blockState, level, bx, by, bz)) {
                    c = 'T';
                } else if (isOreBlock(blockState)) {
                    // Distinguish ore types — this is critical for mining
                    // decisions. Knowing "iron to the NW" vs just "ore" lets
                    // the LLM prioritize correctly without a separate scan.
                    c = oreChar(blockState);
                } else if (blockState.isSolidRender(level, new BlockPos(bx, by, bz))) {
                    if (blockAbove.isAir()) {
                        c = '^';
                    } else {
                        c = '#';
                    }
                } else if (blockAbove.isSolidRender(level, new BlockPos(bx, by + 1, bz))) {
                    c = '#';
                } else {
                    if (blockBelow.isAir() || !blockBelow.isSolidRender(level, new BlockPos(bx, by - 1, bz))) {
                        int dropDepth = 0;
                        for (int dy = 1; dy <= 4; dy++) {
                            if (!level.getBlockState(new BlockPos(bx, by - dy, bz))
                                    .isSolidRender(level, new BlockPos(bx, by - dy, bz))) {
                                dropDepth = dy;
                            } else {
                                break;
                            }
                        }
                        if (dropDepth >= 2) {
                            c = 'v';
                        } else if (dropDepth == 1) {
                            c = ',';
                        } else {
                            c = '.';
                        }
                    } else {
                        c = '.';
                    }
                }
                grid[gridZ][gridX] = c;
            }
        }

        // Overlay entities on grid
        AABB scanArea = new AABB(
                selfX - radius, selfY - 3, selfZ - radius,
                selfX + radius, selfY + 3, selfZ + radius);
        for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, scanArea)) {
            if (entity == sp) continue;
            int ex = entity.blockPosition().getX();
            int ez = entity.blockPosition().getZ();
            int dx = ex - selfX;
            int dz = ez - selfZ;
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;

            int gridX = dx + radius;
            int gridZ = dz + radius;
            if (gridX < 0 || gridX >= size || gridZ < 0 || gridZ >= size) continue;
            if (grid[gridZ][gridX] == '@') continue;

            char entityChar;
            if (entity instanceof Player) {
                entityChar = 'P';
            } else if (entity instanceof Mob mob) {
                var category = mob.getType().getCategory();
                if (category == net.minecraft.world.entity.MobCategory.CREATURE ||
                    category == net.minecraft.world.entity.MobCategory.WATER_CREATURE ||
                    category == net.minecraft.world.entity.MobCategory.AMBIENT) {
                    entityChar = 'a';
                } else {
                    entityChar = 'm';
                }
            } else {
                continue;
            }

            char existing = grid[gridZ][gridX];
            if (existing == '.' || existing == '^' || existing == ',' || existing == 'v') {
                grid[gridZ][gridX] = entityChar;
            }
        }

        for (int z = 0; z < size; z++) {
            sb.append("  ");
            for (int x = 0; x < size; x++) {
                sb.append(grid[z][x]);
            }
            sb.append('\n');
        }
        sb.append("```\n");
        sb.append("Legend: @=you .=flat ^=up1 ,=down1 v=drop2+ #=wall ~=water ");
        sb.append("L=lava !=danger T=tree I=iron C=coal D=diamond G=gold R=redstone ");
        sb.append("E=emerald B=lapis/copper Q=quartz N=netherite m=hostile a=animal P=player\n\n");

        // ── Layer 2: Detailed Entity Report ──
        sb.append("=== NEARBY ENTITIES (detailed) ===\n");

        int entityCount = 0;
        for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, scanArea)) {
            if (entity == sp) continue;
            if (!(entity instanceof LivingEntity)) continue;

            double dist = Math.sqrt(entity.distanceToSqr(sp.position()));
            if (dist > radius + 2) continue;

            String typeName = entity.getType().getDescriptionId();
            // Clean up the name (remove "entity.minecraft." prefix)
            String cleanName = typeName.replace("entity.minecraft.", "")
                    .replace("entity.", "");

            // Direction from self
            double dx = entity.getX() - sp.getX();
            double dz = entity.getZ() - sp.getZ();
            String direction = getDirection(dx, dz);

            sb.append(String.format("- %s at (%.0f, %.0f, %.0f) %s, dist=%.1f",
                    cleanName, entity.getX(), entity.getY(), entity.getZ(),
                    direction, dist));

            // Health
            if (entity instanceof LivingEntity living) {
                sb.append(String.format(" HP=%.0f/%.0f", living.getHealth(), living.getMaxHealth()));
            }

            // What is this entity doing?
            String activity = getActivity(entity);
            sb.append(" [").append(activity).append("]");

            // Who is it targeting?
            if (entity instanceof Mob mob) {
                var target = mob.getTarget();
                if (target != null) {
                    String targetName;
                    if (target instanceof Player p) {
                        targetName = p.getName().getString();
                    } else {
                        targetName = target.getType().getDescriptionId()
                                .replace("entity.minecraft.", "").replace("entity.", "");
                    }
                    sb.append(" >> TARGETING: ").append(targetName);
                    if (target instanceof Player p) {
                        sb.append(String.format(" (HP=%.0f/%.0f)", p.getHealth(), p.getMaxHealth()));
                    }
                }
            }

            // Is it burning?
            if (entity.isOnFire()) sb.append(" [BURNING]");
            // Is it in water?
            if (entity.isInWater()) sb.append(" [IN WATER]");
            // Is it a baby?
            if (entity instanceof net.minecraft.world.entity.AgeableMob ageable && ageable.isBaby()) {
                sb.append(" [BABY]");
            }

            sb.append("\n");
            entityCount++;
        }

        if (entityCount == 0) {
            sb.append("  (no living entities nearby)\n");
        }

        // ── Layer 3: Self Status Summary ──
        sb.append("\n=== SELF STATUS ===\n");
        sb.append(String.format("Position: (%d, %d, %d) | Health: %.1f/%.1f | Food: %d | Facing: %s\n",
                selfX, selfY, selfZ,
                sp.getHealth(), sp.getMaxHealth(),
                sp.getFoodData().getFoodLevel(),
                getFacing(sp)));

        // Holding
        var mainHand = sp.getMainHandItem();
        if (!mainHand.isEmpty()) {
            sb.append("Holding: ").append(mainHand.getHoverName().getString()).append("\n");
        } else {
            sb.append("Holding: (empty hands)\n");
        }

        return sb.toString();
    }

    /**
     * Get a human-readable activity description for an entity.
     */
    private String getActivity(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof Player p) {
            if (p.isSleeping()) return "sleeping";
            if (p.isSprinting()) return "sprinting";
            if (p.isCrouching()) return "sneaking";
            if (p.isSwimming()) return "swimming";
            if (p.hurtTime > 0) return "HURT";
            if (p.getHealth() < p.getMaxHealth() * 0.3f) return "CRITICAL HEALTH";
            return "active";
        }
        if (entity instanceof Mob mob) {
            if (mob.getTarget() != null) return "ATTACKING";
            if (mob.isAggressive()) return "aggressive";
            if (mob.isOnFire()) return "burning";
            // Check if fleeing
            var target = mob.getTarget();
            if (target == null) {
                if (mob.hurtTime > 0) return "HURT";
                return "idle";
            }
        }
        return "unknown";
    }

    /**
     * Get cardinal direction from delta x/z.
     */
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

    private boolean isDanger(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) ||
               state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) ||
               state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) ||
               state.is(Blocks.WITHER_ROSE) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private boolean isTreeTrunk(BlockState state, net.minecraft.world.level.Level level, int x, int y, int z) {
        if (!state.is(Blocks.OAK_LOG) && !state.is(Blocks.SPRUCE_LOG) &&
            !state.is(Blocks.BIRCH_LOG) && !state.is(Blocks.JUNGLE_LOG) &&
            !state.is(Blocks.ACACIA_LOG) && !state.is(Blocks.DARK_OAK_LOG) &&
            !state.is(Blocks.MANGROVE_LOG) && !state.is(Blocks.CHERRY_LOG) &&
            !state.is(Blocks.BAMBOO)) {
            return false;
        }
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    var b = level.getBlockState(new BlockPos(x + dx, y + dy, z + dz));
                    if (b.is(Blocks.OAK_LEAVES) || b.is(Blocks.SPRUCE_LEAVES) ||
                        b.is(Blocks.BIRCH_LEAVES) || b.is(Blocks.JUNGLE_LEAVES) ||
                        b.is(Blocks.ACACIA_LEAVES) || b.is(Blocks.DARK_OAK_LEAVES) ||
                        b.is(Blocks.MANGROVE_LEAVES) || b.is(Blocks.CHERRY_LEAVES) ||
                        b.is(Blocks.AZALEA_LEAVES) || b.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.IRON_ORE) ||
               state.is(Blocks.GOLD_ORE) || state.is(Blocks.DIAMOND_ORE) ||
               state.is(Blocks.EMERALD_ORE) || state.is(Blocks.REDSTONE_ORE) ||
               state.is(Blocks.LAPIS_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE) ||
               state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.ANCIENT_DEBRIS) ||
               state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE) ||
               state.is(Blocks.DEEPSLATE_IRON_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE) ||
               state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE) ||
               state.is(Blocks.DEEPSLATE_REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE) ||
               state.is(Blocks.DEEPSLATE_COPPER_ORE);
    }

    /**
     * Map an ore block to a single-character glyph for the terrain grid.
     *
     * <p>Distinguishing ore types in the grid lets the LLM make smarter
     * mining decisions ("iron to my NW, coal to my S") without needing a
     * separate {@code scan_blocks} call — saving a tool round-trip and
     * reducing token usage.
     *
     * <p>Glyph assignment (chosen to be memorable, not collision-free
     * with the terrain legend — ores are visually distinct from terrain):
     * <ul>
     *   <li>{@code I} — iron (I = Iron)</li>
     *   <li>{@code C} — coal (C = Coal)</li>
     *   <li>{@code D} — diamond (D = Diamond)</li>
     *   <li>{@code G} — gold (G = Gold)</li>
     *   <li>{@code R} — redstone (R = Redstone)</li>
     *   <li>{@code E} — emerald (E = Emerald)</li>
     *   <li>{@code B} — lapis or copper (B for the blue/bluish hue)</li>
     *   <li>{@code Q} — quartz (Q = Quartz)</li>
     *   <li>{@code N} — netherite / ancient debris (N = Netherite)</li>
     * </ul>
     */
    private char oreChar(BlockState state) {
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) return 'I';
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) return 'C';
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return 'D';
        if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.NETHER_GOLD_ORE)) return 'G';
        if (state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)) return 'R';
        if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return 'E';
        if (state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)) return 'B';
        if (state.is(Blocks.NETHER_QUARTZ_ORE)) return 'Q';
        if (state.is(Blocks.ANCIENT_DEBRIS)) return 'N';
        return 'O'; // unknown ore fallback
    }

    private String getFacing(net.minecraft.server.level.ServerPlayer sp) {
        float yaw = sp.getYRot();
        yaw = ((yaw % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return "South (+Z)";
        if (yaw >= 45 && yaw < 135) return "West (-X)";
        if (yaw >= 135 && yaw < 225) return "North (-Z)";
        return "East (+X)";
    }
}
