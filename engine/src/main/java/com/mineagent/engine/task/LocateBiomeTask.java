package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.tools.LocateBiomeTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Executes a biome location task with a bounded number of noise-biome samples
 * per server tick, so a large radius cannot monopolize the tick thread.
 */
public class LocateBiomeTask extends CompanionTask<LocateBiomeTool.LocateBiomeTaskRecord> {

    private enum Phase { SEARCHING, DONE }

    private Phase phase;
    private BlockPos foundPos;
    private String failReason;

    private int searchTicks;
    private BlockPos searchOrigin;
    private ResourceKey<Biome> targetBiome;
    private net.minecraft.world.level.biome.BiomeSource biomeSource;
    private net.minecraft.world.level.biome.Climate.Sampler climateSampler;
    private int ringRadius;
    private int ringIndex;
    private BlockPos bestInRing;
    private double bestDistanceSq;
    private boolean columnActive;
    private int columnX;
    private int columnZ;
    private int sampleY;
    private int minSampleY;
    private int maxSampleY;

    private static final int GRID_STEP = 32;
    private static final int MAX_RADIUS_BLOCKS = 6400;
    private static final int MAX_RING = MAX_RADIUS_BLOCKS / GRID_STEP;
    private static final int VERTICAL_STEP = 4;
    private static final int SAMPLES_PER_TICK = 4096;

    public LocateBiomeTask(AgentPlayer player, LocateBiomeTool.LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.SEARCHING;
        searchTicks = 0;
        foundPos = null;
        failReason = null;
        searchOrigin = null;
        targetBiome = null;
        biomeSource = null;
        climateSampler = null;
        ringRadius = 0;
        ringIndex = 0;
        bestInRing = null;
        bestDistanceSq = Double.POSITIVE_INFINITY;
        columnActive = false;
    }

    @Override
    protected TaskState onTick() {
        // Timeout check
        long gameTime = TaskContext.serverPlayer(player).level().getGameTime();
        if (gameTime >= record.deadline()) {
            return TaskState.FAILED;
        }

        switch (phase) {
            case SEARCHING -> tickSearch();
            case DONE -> {}
        }

        if (phase == Phase.DONE && foundPos != null) return TaskState.SUCCESS;
        if (phase == Phase.DONE && failReason != null) return TaskState.FAILED;
        return TaskState.RUNNING;
    }

    private void tickSearch() {
        searchTicks++;

        var sp = TaskContext.serverPlayer(player);
        ServerLevel level = sp.serverLevel();
        if (searchOrigin == null && !initializeSearch(level, sp.blockPosition())) return;

        int sampled = 0;
        while (sampled < SAMPLES_PER_TICK && phase == Phase.SEARCHING) {
            if (!columnActive) {
                int pointsInRing = ringRadius == 0 ? 1 : 8 * ringRadius;
                if (ringIndex >= pointsInRing) {
                    if (bestInRing != null) {
                        foundPos = bestInRing;
                        phase = Phase.DONE;
                        return;
                    }
                    if (++ringRadius > MAX_RING) {
                        failReason = "Biome '" + record.biomeType + "' not found within "
                                + MAX_RADIUS_BLOCKS + " blocks";
                        phase = Phase.DONE;
                        return;
                    }
                    ringIndex = 0;
                    bestInRing = null;
                    bestDistanceSq = Double.POSITIVE_INFINITY;
                    continue;
                }

                int[] offset = ringOffset(ringRadius, ringIndex++);
                columnX = searchOrigin.getX() + offset[0] * GRID_STEP;
                columnZ = searchOrigin.getZ() + offset[1] * GRID_STEP;
                sampleY = minSampleY;
                columnActive = true;
            }

            // Biomes are defined in quart coordinates in all three axes.
            // Sampling only the player's Y systematically missed lush caves,
            // dripstone caves and deep dark. Scan one vertical column across
            // multiple ticks at native quart resolution without loading chunks.
            var holder = biomeSource.getNoiseBiome(
                    QuartPos.fromBlock(columnX), QuartPos.fromBlock(sampleY),
                    QuartPos.fromBlock(columnZ), climateSampler);
            if (holder.is(targetBiome)) {
                double dx = columnX - searchOrigin.getX();
                double dy = sampleY - searchOrigin.getY();
                double dz = columnZ - searchOrigin.getZ();
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    bestInRing = new BlockPos(columnX, sampleY, columnZ);
                }
            }
            sampleY += VERTICAL_STEP;
            if (sampleY > maxSampleY) columnActive = false;
            sampled++;
        }
    }

    private boolean initializeSearch(ServerLevel level, BlockPos origin) {
        ResourceLocation biomeLoc = ResourceLocation.tryParse(record.biomeType);
        if (biomeLoc == null) {
            failReason = "Invalid biome type: " + record.biomeType;
            phase = Phase.DONE;
            return false;
        }
        targetBiome = ResourceKey.create(
                net.minecraft.core.registries.Registries.BIOME, biomeLoc);
        var registry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        if (!registry.containsKey(targetBiome)) {
            failReason = "Biome type '" + record.biomeType + "' not found in this world";
            phase = Phase.DONE;
            return false;
        }
        searchOrigin = origin.immutable();
        biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        climateSampler = level.getChunkSource().randomState().sampler();
        minSampleY = level.getMinBuildHeight();
        maxSampleY = level.getMaxBuildHeight() - 1;
        return true;
    }

    /** Return the unique clockwise point at index on a square radius ring. */
    private static int[] ringOffset(int radius, int index) {
        if (radius == 0) return new int[]{0, 0};
        int side = 2 * radius;
        if (index < side) return new int[]{-radius + index, -radius};
        if (index < side * 2) return new int[]{radius, -radius + index - side};
        if (index < side * 3) return new int[]{radius - (index - side * 2), radius};
        return new int[]{-radius, radius - (index - side * 3)};
    }

    @Override
    public void onInterrupt() {
        // Release references to world-generation state promptly when a task
        // is cancelled or the companion despawns mid-search.
        biomeSource = null;
        climateSampler = null;
        searchOrigin = null;
        columnActive = false;
    }

    @Override
    protected String successMessage() {
        if (foundPos != null) {
            var companionPos = TaskContext.serverPlayer(player).blockPosition();
            double dx = companionPos.getX() - foundPos.getX();
            double dz = companionPos.getZ() - foundPos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            return "Found biome at (" + foundPos.getX() + ", " + foundPos.getY()
                    + ", " + foundPos.getZ() + ") distance=" + String.format("%.1f", dist);
        }
        return "Biome found";
    }

    @Override
    protected String timeoutMessage() {
        return "Biome search timed out for '" + record.biomeType + "'";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Biome search failed for '" + record.biomeType + "'";
    }
}
