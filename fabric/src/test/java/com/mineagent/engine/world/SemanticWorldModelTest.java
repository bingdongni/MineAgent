package com.mineagent.engine.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SemanticWorldModelTest {
    @Test void lateObservationCannotRollbackCurrentProjection() {
        SemanticWorldModel model = new SemanticWorldModel();
        model.observe("machine:a", "state", "ready", null,
                0.9, "menu", "new", 100L, 0L, true);
        model.observe("machine:a", "state", "idle", null,
                1.0, "late_packet", "old", 90L, 0L, true);

        assertEquals("ready", model.find("machine:a", "state", 120L)
                .orElseThrow().value());
        assertEquals(2, model.eventsSince(0L).size());
    }

    @Test void volatileFactsExpireAndAreNotPersisted() {
        SemanticWorldModel model = new SemanticWorldModel();
        model.observe("actor:x", "activity", "moving", null,
                1.0, "frame", null, 10L, 5L, false);
        model.observe("facility:x", "kind", "machine", null,
                0.8, "inspection", null, 10L, 0L, true);

        assertTrue(model.find("actor:x", "activity", 15L).isPresent());
        assertFalse(model.find("actor:x", "activity", 16L).isPresent());
        assertEquals(1, model.exportState().facts().size());
        SemanticWorldModel restored = new SemanticWorldModel();
        restored.importState(model.exportState());
        assertEquals("machine", restored.find("facility:x", "kind", 100L)
                .orElseThrow().value());
        assertFalse(restored.find("actor:x", "activity", 100L).isPresent());
    }

    @Test void inventorySnapshotPublishesRemovalAsZeroCount() {
        SemanticWorldModel model = new SemanticWorldModel();
        var position = new WorldAssetIndex.Position("minecraft:overworld", 1, 2, 3);
        var item = new WorldAssetIndex.ItemObservation(0, "minecraft:oak_log",
                4, 0, 0, Set.of("item"), 0.0);
        model.observeInventory(position, List.of(item), 20L);
        model.observeInventory(position, List.of(), 21L);

        assertEquals("0", model.find("inventory:minecraft:oak_log", "count", 21L)
                .orElseThrow().value());
    }

    @Test void primitiveToolResultFieldsBecomeBoundedSemanticFacts() {
        SemanticWorldModel model = new SemanticWorldModel();
        model.recordOutcome("inspect_gui", true,
                "{\"success\":true,\"menu_type\":\"mod:crusher\",\"slots\":[]}",
                "call", 30L);
        assertEquals("mod:crusher", model.find("tool:inspect_gui",
                "result.menu_type", 31L).orElseThrow().value());
        assertFalse(model.find("tool:inspect_gui", "result.slots", 31L).isPresent());
    }

    @Test void durableRecallKeepsGoalsAndActionsWithoutAssetProjectionPollution() {
        SemanticWorldModel model = new SemanticWorldModel();
        model.recordOwnerIntent("build a safe bridge", 40L);
        model.recordOutcome("build", true,
                "bridge completed and verified", "build-call", 50L, true);
        model.observe("asset:minecraft:iron_ore@4,20,4", "kind", "ore", null,
                1.0, "asset_index", null, 45L, 0L, true);

        var exported = model.exportState();
        assertTrue(exported.events().stream()
                .anyMatch(event -> event.subject().equals("owner_intent")));
        assertTrue(exported.events().stream()
                .anyMatch(event -> event.subject().equals("tool:build")));
        assertFalse(exported.events().stream()
                .anyMatch(event -> event.subject().startsWith("asset:")));
        assertTrue(model.recallForPrompt("safe bridge", null, 60L, 4)
                .contains("bridge"));
    }
}
