package com.mineagent.engine.task;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTask;
import com.mineagent.api.task.TaskState;
import com.mineagent.tools.LocateStructureTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

/**
 * Executes a structure location task using vanilla's placement-aware bounded
 * query and returns its coordinates.
 */
public class LocateStructureTask extends CompanionTask<LocateStructureTool.LocateStructureTaskRecord> {

    private enum Phase { SEARCHING, DONE }

    private Phase phase;
    private BlockPos foundPos;
    private String failReason;

    private int searchTicks;

    public LocateStructureTask(AgentPlayer player, LocateStructureTool.LocateStructureTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        phase = Phase.SEARCHING;
        searchTicks = 0;
        foundPos = null;
        failReason = null;
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
        if (searchTicks++ > 0) return;

        var sp = TaskContext.serverPlayer(player);
        ServerLevel level = sp.serverLevel();
        var pos = sp.blockPosition();

        // Parse structure type
        ResourceLocation structLoc = ResourceLocation.tryParse(record.structureType);
        if (structLoc == null) {
            failReason = "Invalid structure type: " + record.structureType;
            phase = Phase.DONE;
            return;
        }

        ResourceKey<Structure> structKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.STRUCTURE, structLoc);

        var registry = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> holder = registry.getHolder(structKey);

        if (holder.isEmpty()) {
            failReason = "Structure type '" + record.structureType + "' not found in this world";
            phase = Phase.DONE;
            return;
        }

        // Keep the placement-aware lookup on the server thread. Moving world
        // generation access to a generic worker would race chunk and structure
        // manager state; the radius below bounds the synchronous query.
        var structureHolder = holder.get();

        // A registry ID is not a tag ID. The previous code validated an exact
        // holder and then created a same-named TagKey, which usually resolves
        // to an empty tag. Query the chunk generator with that exact holder.
        int searchRadius = 100;
        var foundResult = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(structureHolder),
                        pos, searchRadius, false);
        BlockPos found = foundResult != null ? foundResult.getFirst() : null;

        if (found != null) {
            foundPos = found;
            phase = Phase.DONE;
            return;
        }

        failReason = "Structure '" + record.structureType
                + "' not found within " + searchRadius + " chunks";
        phase = Phase.DONE;
    }

    @Override
    protected void onInterrupt() {
        // Nothing to cancel — server-side search is read-only
    }

    @Override
    protected String successMessage() {
        if (foundPos != null) {
            var companionPos = TaskContext.serverPlayer(player).blockPosition();
            double dist = companionPos.distSqr(foundPos);
            return "Found structure at (" + foundPos.getX() + ", " + foundPos.getY()
                    + ", " + foundPos.getZ() + ") distance=" + String.format("%.1f", Math.sqrt(dist));
        }
        return "Structure found";
    }

    @Override
    protected String timeoutMessage() {
        return "Structure search timed out for '" + record.structureType + "'";
    }

    @Override
    protected String failureMessage() {
        if (failReason != null) return failReason;
        return "Structure search failed for '" + record.structureType + "'";
    }
}
