package com.mineagent.engine.memory;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GoalConditionedMemoryTest {
    @Test void spatialMemoryReturnsOnlyGoalRelatedLocations() {
        CognitiveMap map = new CognitiveMap();
        map.recordPoi(new BlockPos(10, 64, 10), "structure:crafting_table",
                "crafting table workstation", 1L);
        map.recordPoi(new BlockPos(80, 20, 80), "resource:diamond_ore",
                "diamond ore", 2L);

        String recalled = map.summarizeForPrompt("use crafting table", 2);
        assertTrue(recalled.contains("crafting table"));
        assertFalse(recalled.contains("diamond ore"));
    }

    @Test void placeEventsAreRetrievedAgainstCurrentNeed() {
        PlaceEventMemory memory = new PlaceEventMemory();
        memory.remember("structure", "furnace", 2, 64, 2,
                "minecraft:overworld", 1L, "furnace workstation");
        memory.remember("resource", "diamond_ore", 30, 12, 30,
                "minecraft:overworld", 2L, "rare ore");

        String recalled = memory.summarizeForPrompt("smelt with furnace", 2);
        assertTrue(recalled.contains("furnace"));
        assertFalse(recalled.contains("diamond_ore"));
    }
}
