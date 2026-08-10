package com.mineagent.api.network.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionSetupPayloadTest {

    @Test
    void acceptsArbitraryFutureModelAndCompatibleEndpoint() {
        CompanionSetupPayload payload = new CompanionSetupPayload(
                "Builder", "openai-compatible", "secret", false,
                "vendor/future-model-2030-preview",
                "https://relay.example.test/custom/v1", 0.7, "high", "creative");

        assertEquals("vendor/future-model-2030-preview", payload.model());
        assertEquals("https://relay.example.test/custom/v1", payload.baseUrl());
        assertEquals("creative", payload.gameMode());
    }

    @Test
    void blankKeyCanReuseServerStoredCredential() {
        CompanionSetupPayload payload = new CompanionSetupPayload(
                "", "anthropic-compatible", "", true, "custom-claude-id",
                "https://relay.example.test", 1.0, "");

        assertEquals("", payload.apiKey());
    }

    @Test
    void rejectsHeaderInjectionAndOversizedFields() {
        assertThrows(IllegalArgumentException.class, () ->
                new CompanionSetupPayload("Agent", "openai-compatible",
                        "key\r\nInjected: yes", false, "model", "https://example.test",
                        0.7, ""));
        assertThrows(IllegalArgumentException.class, () ->
                new CompanionSetupPayload("Agent", "x".repeat(65), "key", false,
                        "model", "https://example.test", 0.7, ""));
        assertThrows(IllegalArgumentException.class, () ->
                new CompanionSetupPayload("Agent", "openai-compatible", "key", false,
                        "model", "https://example.test", 0.7, "", "spectator"));
    }

    @Test
    void omittedOrBlankModeDefaultsToSurvival() {
        CompanionSetupPayload legacy = new CompanionSetupPayload(
                "Agent", "openai-compatible", "key", false,
                "model", "https://example.test", 0.7, "");
        CompanionSetupPayload blank = new CompanionSetupPayload(
                "Agent", "openai-compatible", "key", false,
                "model", "https://example.test", 0.7, "", "  ");

        assertEquals("survival", legacy.gameMode());
        assertEquals("survival", blank.gameMode());
    }
}
