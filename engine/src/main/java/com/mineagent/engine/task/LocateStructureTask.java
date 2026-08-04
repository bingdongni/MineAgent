package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.tools.LocateStructureTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Performs one bounded vanilla structure lookup for an ID or explicit tag. */
public final class LocateStructureTask
        extends CompanionTask<LocateStructureTool.LocateStructureTaskRecord> {

    private static final int SEARCH_RADIUS_CHUNKS = 100;
    private boolean searched;
    private BlockPos foundPos;
    private String failReason;

    public LocateStructureTask(AgentPlayer player,
                               LocateStructureTool.LocateStructureTaskRecord record) {
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

        String requested = record.structureType == null
                ? "" : record.structureType.trim();
        boolean tagRequest = requested.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(
                tagRequest ? requested.substring(1) : requested);
        if (id == null) {
            failReason = "Invalid structure ID: " + requested;
            return TaskState.FAILED;
        }

        var level = sp.serverLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> candidates;
        if (tagRequest) {
            candidates = registry.getTag(TagKey.create(Registries.STRUCTURE, id))
                    .map(set -> (HolderSet<Structure>) set).orElse(null);
        } else {
            candidates = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id))
                    .map(HolderSet::direct).orElse(null);
        }
        if (candidates == null || candidates.size() == 0) {
            failReason = "Structure " + requested + " is not registered in this world";
            return TaskState.FAILED;
        }

        // The old task created a TagKey from every structure ID, so direct
        // IDs never matched. ChunkGenerator accepts an explicit HolderSet and
        // preserves vanilla placement/seed semantics for both forms.
        var match = level.getChunkSource().getGenerator().findNearestMapStructure(
                level, candidates, sp.blockPosition(), SEARCH_RADIUS_CHUNKS, false);
        if (match == null) {
            failReason = "Structure " + requested + " was not found within "
                    + SEARCH_RADIUS_CHUNKS + " chunks";
            return TaskState.FAILED;
        }
        foundPos = match.getFirst();
        return TaskState.SUCCESS;
    }

    @Override
    protected void onInterrupt() {
        // The bounded vanilla lookup completes inside one server-thread call.
    }

    @Override
    protected String successMessage() {
        if (foundPos == null) return "Structure found";
        BlockPos from = TaskContext.serverPlayer(player).blockPosition();
        double distance = Math.sqrt(from.distSqr(foundPos));
        return "Found structure at (" + foundPos.getX() + ", " + foundPos.getY()
                + ", " + foundPos.getZ() + ") distance="
                + String.format(java.util.Locale.ROOT, "%.1f", distance);
    }

    @Override
    protected String timeoutMessage() {
        return "Structure search timed out for '" + record.structureType + "'";
    }

    @Override
    protected String failureMessage() {
        return failReason != null ? failReason
                : "Structure search failed for '" + record.structureType + "'";
    }
}
