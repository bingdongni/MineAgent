package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.api.task.TaskSnapshot;
import com.mineagent.tools.LocateBiomeTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/** Performs one bounded biome-source search without loading terrain chunks. */
public final class LocateBiomeTask
        extends CompanionTask<LocateBiomeTool.LocateBiomeTaskRecord> {

    private static final int SEARCH_RADIUS_BLOCKS = 6_400;
    private static final int SAMPLE_INTERVAL = 32;
    private boolean searched;
    private BlockPos foundPos;
    private String failReason;

    public LocateBiomeTask(AgentPlayer player,
                           LocateBiomeTool.LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        searched = false;
        foundPos = null;
        failReason = null;
    }

    @Override
    protected TaskState onTick() {
        var sp = TaskContext.serverPlayer(player);
        if (sp.level().getGameTime() >= record.deadline()) return TaskState.FAILED;
        if (searched) return foundPos != null ? TaskState.SUCCESS : TaskState.FAILED;
        searched = true;

        ResourceLocation id = ResourceLocation.tryParse(record.biomeType);
        if (id == null) {
            failReason = "Invalid biome ID: " + record.biomeType;
            return TaskState.FAILED;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        var level = sp.serverLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        if (!registry.containsKey(key)) {
            failReason = "Biome " + record.biomeType + " is not registered in this world";
            return TaskState.FAILED;
        }

        BlockPos origin = sp.blockPosition();
        var sampler = level.getChunkSource().randomState().sampler();
        // The previous implementation repeated this synchronous search every
        // tick and eventually passed 409,600 blocks while describing it as
        // 800 chunks. One 6,400-block query matches vanilla's useful bound;
        // a deterministic local RandomSource avoids perturbing world RNG.
        RandomSource random = RandomSource.create(
                origin.asLong() ^ sp.getUUID().getLeastSignificantBits());
        var match = level.getChunkSource().getGenerator().getBiomeSource()
                .findBiomeHorizontal(origin.getX(), origin.getY(), origin.getZ(),
                        SEARCH_RADIUS_BLOCKS, SAMPLE_INTERVAL,
                        holder -> holder.is(key), random, true, sampler);
        if (match == null) {
            failReason = "Biome " + record.biomeType + " was not found within "
                    + SEARCH_RADIUS_BLOCKS + " blocks";
            return TaskState.FAILED;
        }
        foundPos = match.getFirst();
        return TaskState.SUCCESS;
    }

    @Override
    protected void onInterrupt() {
        // The biome-source lookup completes inside one bounded call.
    }

    @Override
    public TaskSnapshot snapshot() {
        return TaskSnapshot.progress(searched ? "complete" : "searching",
                "Locate biome " + record.biomeType,
                searched ? 1 : 0, 1,
                foundPos == null ? null : foundPos.getX(),
                foundPos == null ? null : foundPos.getY(),
                foundPos == null ? null : foundPos.getZ(),
                searched && foundPos == null ? failReason : null,
                foundPos == null ? null : "biome_location=" + foundPos,
                searched ? 1L : 0L);
    }

    @Override
    protected String successMessage() {
        if (foundPos == null) return "Biome found";
        BlockPos from = TaskContext.serverPlayer(player).blockPosition();
        double distance = Math.hypot(from.getX() - foundPos.getX(),
                from.getZ() - foundPos.getZ());
        return "Found biome at (" + foundPos.getX() + ", " + foundPos.getY()
                + ", " + foundPos.getZ() + ") distance="
                + String.format(java.util.Locale.ROOT, "%.1f", distance);
    }

    @Override
    protected String timeoutMessage() {
        return "Biome search timed out for '" + record.biomeType + "'";
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Biome search failed for '" + record.biomeType + "'";
    }
}
