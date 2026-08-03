package com.mineagent.engine.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;

import java.util.concurrent.CompletableFuture;

/**
 * Data generator entry point for MineAgent.
 * <p>
 * Creates all data-driven assets (block tags, item tags, etc.)
 * used by the pathfinding and survival systems.
 * <p>
 * Platform modules should call {@link #create(PackOutput, CompletableFuture)}
 * from their data-gen run configuration and pass the resulting
 * provider to the platform's data-gen pipeline.
 */
public final class MineAgentDataGen {

    private MineAgentDataGen() {}

    /**
     * Create a MineAgentBlockTags provider for data generation.
     *
     * @param output         the pack output directory
     * @param lookupProvider future for the registry access
     * @return a MineAgentBlockTags data provider
     */
    public static MineAgentBlockTags createBlockTagsProvider(PackOutput output,
                                       CompletableFuture<HolderLookup.Provider> lookupProvider) {
        return new MineAgentBlockTags(output, lookupProvider);
    }
}
