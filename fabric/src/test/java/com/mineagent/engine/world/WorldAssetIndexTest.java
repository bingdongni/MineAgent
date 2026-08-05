package com.mineagent.engine.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldAssetIndexTest {
    private static final WorldAssetIndex.Position HOME =
            new WorldAssetIndex.Position("minecraft:overworld", 0, 64, 0);

    @Test
    void inventorySnapshotsReplaceRemovedItems() {
        WorldAssetIndex index = new WorldAssetIndex();
        index.observeInventory(HOME, List.of(item(0, "minecraft:oak_log", 8)), 10L);
        assertTrue(index.resolve("item", "minecraft:oak_log", 8, HOME, 10L)
                .satisfiedNow());

        index.observeInventory(HOME, List.of(), 11L);
        var resolution = index.resolve("item", "minecraft:oak_log", 1, HOME, 11L);
        assertFalse(resolution.satisfiedNow());
        assertTrue(resolution.candidates().isEmpty());
    }

    @Test
    void emptyContainerObservationInvalidatesRememberedContents() {
        WorldAssetIndex index = new WorldAssetIndex();
        index.observeContainer("minecraft:overworld:2,64,2",
                new WorldAssetIndex.Position("minecraft:overworld", 2, 64, 2),
                List.of(item(3, "minecraft:diamond_axe", 1,
                        Set.of("tool:axe"), 1_500.0)), true, 20L);
        assertEquals("verify_and_retrieve_known_storage",
                index.resolve("capability", "tool:axe", 1, HOME, 20L)
                        .recommendedAction());

        index.observeContainer("minecraft:overworld:2,64,2",
                new WorldAssetIndex.Position("minecraft:overworld", 2, 64, 2),
                List.of(), true, 21L);
        assertTrue(index.resolve("capability", "tool:axe", 1, HOME, 21L)
                .candidates().isEmpty());
    }

    @Test
    void strongerStoredToolCanSubstituteForPlannedReplacement() {
        WorldAssetIndex index = new WorldAssetIndex();
        index.observeInventory(HOME, List.of(), 30L);
        index.observeContainer("chest", HOME,
                List.of(item(0, "minecraft:diamond_axe", 1,
                        Set.of("tool:axe", "weapon:melee"), 1_500.0)), true, 30L);

        var assessment = index.assessAcquisition("minecraft:wooden_axe", 1,
                Set.of("tool:axe"), 50.0, HOME, 30L);
        assertEquals(0, assessment.carriedExactCount());
        assertEquals(1, assessment.storedSubstitutes().size());
        assertEquals("minecraft:diamond_axe",
                assessment.storedSubstitutes().get(0).asset().resourceId());
    }

    @Test
    void persistenceExportsOnlyDurableWorldKnowledge() {
        WorldAssetIndex index = new WorldAssetIndex();
        index.observeInventory(HOME, List.of(item(0, "minecraft:bread", 3)), 40L);
        index.observeContainer("durable", HOME,
                List.of(item(0, "minecraft:iron_ingot", 4)), true, 40L);
        index.observeContainer("open", HOME,
                List.of(item(0, "minecraft:coal", 2)), false, 40L);

        var exported = index.exportState();
        assertEquals(1, exported.assets().size());
        assertEquals(WorldAssetIndex.Scope.KNOWN_CONTAINER,
                exported.assets().get(0).scope());
    }

    private static WorldAssetIndex.ItemObservation item(
            int slot, String id, int count) {
        return item(slot, id, count, Set.of("item"), 0.0);
    }

    private static WorldAssetIndex.ItemObservation item(
            int slot, String id, int count, Set<String> capabilities,
            double quality) {
        return new WorldAssetIndex.ItemObservation(slot, id, count,
                0, 0, capabilities, quality);
    }
}
