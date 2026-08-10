package com.mineagent.api.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionGameModeTest {

    @Test
    void acceptsExactlyTheFourHumanFacingModes() {
        assertEquals(List.of("survival", "creative", "adventure", "hardcore"),
                List.of(CompanionGameMode.values()).stream()
                        .map(CompanionGameMode::wireName).toList());
        assertTrue(CompanionGameMode.parse("HARDCORE").orElseThrow().isHardcore());
    }

    @Test
    void oldOrEmptyValuesUseSurvival() {
        assertEquals(CompanionGameMode.SURVIVAL, CompanionGameMode.orDefault(null));
        assertEquals(CompanionGameMode.SURVIVAL, CompanionGameMode.orDefault(""));
    }
}
