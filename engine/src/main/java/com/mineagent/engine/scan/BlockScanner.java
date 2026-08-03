package com.mineagent.engine.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Scan for blocks in an area — provides efficient world scanning for
 * finding ores, water, lava, and other blocks of interest. Used by
 * tools (ScanBlocksTool, LookAroundTool) and survival chains.
 *
 * <p>Uses chunk section iteration for efficiency (instead of
 * BlockPos.betweenClosed which creates excessive BlockPos objects).
 * Results are limited to prevent overwhelming the LLM context.
 *
 * <p>All methods are static; this is a pure utility class with no state.
 */
public final class BlockScanner {

    /** Maximum number of results to return from any scan. */
    private static final int MAX_RESULTS = 256;

    /** Maximum number of ore veins to report. */
    private static final int MAX_VEINS = 32;

    /** Minimum Y level (world minimum). */
    private static final int MIN_Y = -64;

    /** Maximum Y level (world height). */
    private static final int MAX_Y = 320;

    private BlockScanner() {}

    // ── Scan by Block ID ──────────────────────────────────────────

    /**
     * Scan for blocks matching the given block ID within a radius
     * of the center position.
     *
     * <p>The block ID is in Minecraft resource location format
     * (e.g., "minecraft:diamond_ore", "minecraft:iron_ore").
     *
     * @param level   the server level
     * @param center  the center position for the scan
     * @param blockId the block ID to scan for (e.g., "minecraft:diamond_ore")
     * @param radius  the scan radius in blocks
     * @return list of positions where the block was found (limited to {@value #MAX_RESULTS})
     */
    public static List<BlockPos> scanByType(ServerLevel level, BlockPos center,
                                             String blockId, int radius) {
        List<BlockPos> results = new ArrayList<>();
        if (level == null || center == null || blockId == null || radius <= 0) return results;

        try {
            // Resolve the block ID to a Block
            ResourceLocation loc = ResourceLocation.parse(blockId);
            Block targetBlock = BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
            if (targetBlock == null || targetBlock == Blocks.AIR) return results;

            int cx = center.getX(), cy = center.getY(), cz = center.getZ();
            int rSq = radius * radius;

            // Iterate chunk sections for efficiency
            ChunkCache cache = new ChunkCache(level);
            try {
                int minChunkX = (cx - radius) >> 4;
                int maxChunkX = (cx + radius) >> 4;
                int minChunkZ = (cz - radius) >> 4;
                int maxChunkZ = (cz + radius) >> 4;

                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        if (!level.hasChunk(chunkX, chunkZ)) continue;

                        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                        LevelChunkSection[] sections = chunk.getSections();

                        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                            LevelChunkSection section = sections[secIdx];
                            if (section.hasOnlyAir()) continue;

                            int secY = chunk.getSectionYFromSectionIndex(secIdx);
                            int baseY = secY << 4;

                            // Iterate blocks in the section
                            for (int x = 0; x < 16; x++) {
                                for (int y = 0; y < 16; y++) {
                                    for (int z = 0; z < 16; z++) {
                                        BlockState state = section.getBlockState(x, y, z);
                                        if (state.getBlock() != targetBlock) continue;

                                        int worldX = (chunkX << 4) + x;
                                        int worldY = baseY + y;
                                        int worldZ = (chunkZ << 4) + z;

                                        // Check distance
                                        int dx = worldX - cx;
                                        int dy = worldY - cy;
                                        int dz = worldZ - cz;
                                        if (dx * dx + dy * dy + dz * dz > rSq) continue;

                                        results.add(new BlockPos(worldX, worldY, worldZ));
                                        if (results.size() >= MAX_RESULTS) {
                                            return results;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                cache.invalidate();
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockScanner.scanByType error: " + e.getMessage());
        }

        return results;
    }

    // ── Scan by Tag ───────────────────────────────────────────────

    /**
     * Scan for blocks matching the given tag within a radius of the
     * center position.
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param tag    the block tag to scan for
     * @param radius the scan radius in blocks
     * @return list of positions where matching blocks were found
     */
    public static List<BlockPos> scanByTag(ServerLevel level, BlockPos center,
                                            TagKey<Block> tag, int radius) {
        List<BlockPos> results = new ArrayList<>();
        if (level == null || center == null || tag == null || radius <= 0) return results;

        try {
            int cx = center.getX(), cy = center.getY(), cz = center.getZ();
            int rSq = radius * radius;

            // Resolve the tag to a set of blocks
            var taggedBlocks = level.registryAccess()
                    .registryOrThrow(BuiltInRegistries.BLOCK.key())
                    .getTag(tag);
            if (taggedBlocks == null) return results;

            int minChunkX = (cx - radius) >> 4;
            int maxChunkX = (cx + radius) >> 4;
            int minChunkZ = (cz - radius) >> 4;
            int maxChunkZ = (cz + radius) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!level.hasChunk(chunkX, chunkZ)) continue;

                    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    LevelChunkSection[] sections = chunk.getSections();

                    for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                        LevelChunkSection section = sections[secIdx];
                        if (section.hasOnlyAir()) continue;

                        int baseY = chunk.getSectionYFromSectionIndex(secIdx) << 4;

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    if (!state.is(tag)) continue;

                                    int worldX = (chunkX << 4) + x;
                                    int worldY = baseY + y;
                                    int worldZ = (chunkZ << 4) + z;

                                    int dx = worldX - cx;
                                    int dy = worldY - cy;
                                    int dz = worldZ - cz;
                                    if (dx * dx + dy * dy + dz * dz > rSq) continue;

                                    results.add(new BlockPos(worldX, worldY, worldZ));
                                    if (results.size() >= MAX_RESULTS) return results;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockScanner.scanByTag error: " + e.getMessage());
        }

        return results;
    }

    // ── Scan for Ores ─────────────────────────────────────────────

    /**
     * Scan for any ore blocks within a radius of the center position.
     *
     * <p>Detects all vanilla ore types (coal, iron, copper, gold,
     * redstone, lapis, diamond, emerald, nether quartz, nether gold,
     * ancient debris) and their deepslate variants.
     *
     * @param level  the server level
     * @param center the center position for the scan
     * @param radius the scan radius in blocks
     * @return list of ore block positions
     */
    public static List<BlockPos> scanOres(ServerLevel level, BlockPos center, int radius) {
        List<BlockPos> results = new ArrayList<>();
        if (level == null || center == null || radius <= 0) return results;

        try {
            int cx = center.getX(), cy = center.getY(), cz = center.getZ();
            int rSq = radius * radius;

            int minChunkX = (cx - radius) >> 4;
            int maxChunkX = (cx + radius) >> 4;
            int minChunkZ = (cz - radius) >> 4;
            int maxChunkZ = (cz + radius) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!level.hasChunk(chunkX, chunkZ)) continue;

                    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    LevelChunkSection[] sections = chunk.getSections();

                    for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                        LevelChunkSection section = sections[secIdx];
                        if (section.hasOnlyAir()) continue;

                        int baseY = chunk.getSectionYFromSectionIndex(secIdx) << 4;

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    if (!isOreBlock(state.getBlock())) continue;

                                    int worldX = (chunkX << 4) + x;
                                    int worldY = baseY + y;
                                    int worldZ = (chunkZ << 4) + z;

                                    int dx = worldX - cx;
                                    int dy = worldY - cy;
                                    int dz = worldZ - cz;
                                    if (dx * dx + dy * dy + dz * dz > rSq) continue;

                                    results.add(new BlockPos(worldX, worldY, worldZ));
                                    if (results.size() >= MAX_RESULTS) return results;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockScanner.scanOres error: " + e.getMessage());
        }

        return results;
    }

    // ── Full Area Scan ────────────────────────────────────────────

    /**
     * Perform a full area scan around the center position, identifying
     * ores, water, lava, and other danger blocks.
     *
     * @param level  the server level
     * @param center the center position
     * @param radius the scan radius
     * @return the scan result containing all found features
     */
    public static BlockScanResult scanArea(ServerLevel level, BlockPos center, int radius) {
        if (level == null || center == null || radius <= 0) {
            return new BlockScanResult(List.of(), List.of(), List.of(), List.of());
        }

        try {
            List<OreVein> ores = new ArrayList<>();
            List<BlockPos> water = new ArrayList<>();
            List<BlockPos> lava = new ArrayList<>();
            List<BlockPos> danger = new ArrayList<>();

            int cx = center.getX(), cy = center.getY(), cz = center.getZ();
            int rSq = radius * radius;

            int minChunkX = (cx - radius) >> 4;
            int maxChunkX = (cx + radius) >> 4;
            int minChunkZ = (cz - radius) >> 4;
            int maxChunkZ = (cz + radius) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!level.hasChunk(chunkX, chunkZ)) continue;

                    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    LevelChunkSection[] sections = chunk.getSections();

                    for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                        LevelChunkSection section = sections[secIdx];
                        if (section.hasOnlyAir()) continue;

                        int baseY = chunk.getSectionYFromSectionIndex(secIdx) << 4;

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    if (state.isAir()) continue;

                                    int worldX = (chunkX << 4) + x;
                                    int worldY = baseY + y;
                                    int worldZ = (chunkZ << 4) + z;

                                    int dx = worldX - cx;
                                    int dy = worldY - cy;
                                    int dz = worldZ - cz;
                                    if (dx * dx + dy * dy + dz * dz > rSq) continue;

                                    BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                                    Block block = state.getBlock();

                                    // Categorize the block
                                    if (isOreBlock(block)) {
                                        String oreType = blockIdStr(block);
                                        ores.add(new OreVein(pos, oreType, 1));
                                        if (ores.size() >= MAX_VEINS) break;
                                    } else if (block == Blocks.WATER) {
                                        water.add(pos);
                                        if (water.size() >= MAX_RESULTS) break;
                                    } else if (block == Blocks.LAVA) {
                                        lava.add(pos);
                                        danger.add(pos);
                                        if (lava.size() >= MAX_RESULTS) break;
                                    } else if (isDangerBlock(block)) {
                                        danger.add(pos);
                                        if (danger.size() >= MAX_RESULTS) break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Merge adjacent ore positions into veins
            List<OreVein> mergedVeins = mergeOreVeins(ores);

            return new BlockScanResult(mergedVeins, water, lava, danger);
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockScanner.scanArea error: " + e.getMessage());
            return new BlockScanResult(List.of(), List.of(), List.of(), List.of());
        }
    }

    // ── Nearby Blocks ─────────────────────────────────────────────

    /**
     * Get information about blocks around a position. Returns a
     * summary of what blocks exist in the vicinity, suitable for
     * providing to the LLM as context.
     *
     * @param level  the server level
     * @param pos    the center position
     * @param radius the radius to scan
     * @return the nearby blocks result
     */
    @SuppressWarnings("deprecation")
    public static NearbyBlocksResult getNearbyBlocks(ServerLevel level, BlockPos pos, int radius) {
        if (level == null || pos == null || radius <= 0) {
            return new NearbyBlocksResult(pos, List.of(), List.of(), List.of());
        }

        try {
            List<String> solidBlocks = new ArrayList<>();
            List<String> fluidBlocks = new ArrayList<>();
            List<String> specialBlocks = new ArrayList<>();

            ChunkCache cache = new ChunkCache(level);
            try {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            BlockPos checkPos = pos.offset(dx, dy, dz);
                            BlockState state = cache.getBlockState(checkPos);
                            if (state.isAir()) continue;

                            Block block = state.getBlock();
                            String id = blockIdStr(block);
                            String entry = id + "@" + dx + "," + dy + "," + dz;

                            if (!state.getFluidState().isEmpty()) {
                                fluidBlocks.add(entry);
                            } else if (state.isSolid()) {
                                solidBlocks.add(entry);
                            } else {
                                specialBlocks.add(entry);
                            }

                            // Limit per category
                            if (solidBlocks.size() >= 50 && fluidBlocks.size() >= 20
                                    && specialBlocks.size() >= 30) {
                                return new NearbyBlocksResult(pos, solidBlocks, fluidBlocks, specialBlocks);
                            }
                        }
                    }
                }
            } finally {
                cache.invalidate();
            }

            return new NearbyBlocksResult(pos, solidBlocks, fluidBlocks, specialBlocks);
        } catch (Exception e) {
            System.err.println("[MineAgent] BlockScanner.getNearbyBlocks error: " + e.getMessage());
            return new NearbyBlocksResult(pos, List.of(), List.of(), List.of());
        }
    }

    // ── Records ───────────────────────────────────────────────────

    /**
     * Full area scan result container.
     *
     * @param ores   list of ore veins found
     * @param water  list of water block positions
     * @param lava   list of lava block positions
     * @param danger list of danger block positions (lava + other hazards)
     */
    public record BlockScanResult(
            List<OreVein> ores,
            List<BlockPos> water,
            List<BlockPos> lava,
            List<BlockPos> danger
    ) {}

    /**
     * Information about an ore vein.
     *
     * @param center  the center position of the vein
     * @param oreType the ore type identifier (e.g., "minecraft:diamond_ore")
     * @param count   the number of ore blocks in this vein
     */
    public record OreVein(
            BlockPos center,
            String oreType,
            int count
    ) {}

    /**
     * Result for nearby blocks query.
     *
     * @param center         the center position
     * @param solidBlocks    solid block descriptions (id@dx,dy,dz)
     * @param fluidBlocks    fluid block descriptions
     * @param specialBlocks  non-solid, non-fluid block descriptions
     */
    public record NearbyBlocksResult(
            BlockPos center,
            List<String> solidBlocks,
            List<String> fluidBlocks,
            List<String> specialBlocks
    ) {}

    // ── Internal Helpers ──────────────────────────────────────────

    /**
     * Check if a block is an ore block.
     */
    private static boolean isOreBlock(Block block) {
        return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.NETHER_QUARTZ_ORE || block == Blocks.NETHER_GOLD_ORE
                || block == Blocks.ANCIENT_DEBRIS;
    }

    /**
     * Check if a block is a danger block (besides lava).
     */
    private static boolean isDangerBlock(Block block) {
        return block == Blocks.FIRE
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.CACTUS
                || block == Blocks.WITHER_ROSE
                || block == Blocks.POWDER_SNOW;
    }

    /**
     * Get the block ID string for a block.
     */
    private static String blockIdStr(Block block) {
        ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(block);
        return loc.toString();
    }

    /**
     * Merge individual ore positions into veins (groups of adjacent
     * same-type ores).
     */
    private static List<OreVein> mergeOreVeins(List<OreVein> individual) {
        if (individual.isEmpty()) return individual;

        List<OreVein> merged = new ArrayList<>();
        // Simple merging: group by ore type, then find clusters
        // For simplicity, we merge ores of the same type that are
        // within 2 blocks of each other
        List<OreVein> remaining = new ArrayList<>(individual);

        while (!remaining.isEmpty()) {
            OreVein seed = remaining.remove(0);
            List<OreVein> cluster = new ArrayList<>();
            cluster.add(seed);

            // Find all veins of the same type within distance
            var iter = remaining.iterator();
            while (iter.hasNext()) {
                OreVein candidate = iter.next();
                if (!candidate.oreType.equals(seed.oreType)) continue;

                // Check if candidate is adjacent to any member of the cluster
                for (OreVein member : cluster) {
                    if (member.center.distManhattan(candidate.center) <= 3) {
                        cluster.add(candidate);
                        iter.remove();
                        break;
                    }
                }
            }

            // Calculate cluster center and count
            int sumX = 0, sumY = 0, sumZ = 0;
            int count = 0;
            for (OreVein v : cluster) {
                sumX += v.center.getX();
                sumY += v.center.getY();
                sumZ += v.center.getZ();
                count += v.count;
            }
            BlockPos clusterCenter = new BlockPos(
                    sumX / cluster.size(),
                    sumY / cluster.size(),
                    sumZ / cluster.size()
            );
            merged.add(new OreVein(clusterCenter, seed.oreType, count));

            if (merged.size() >= MAX_VEINS) break;
        }

        return merged;
    }
}
