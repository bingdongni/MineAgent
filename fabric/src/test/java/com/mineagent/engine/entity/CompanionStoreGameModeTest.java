package com.mineagent.engine.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionStoreGameModeTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyRecordWithoutModeRestoresAsSurvival() throws Exception {
        String json = """
                {"version":1,"companions":[{
                  "ownerUuid":"%s","ownerName":"Owner","companionName":"Agent",
                  "providerId":"openai-compatible","apiKey":"key","model":"model",
                  "baseUrl":"https://example.test","temperature":0.7,
                  "reasoningEffort":"","skinName":"","skinValue":"",
                  "skinSignature":"","bodyData":""
                }]}
                """.formatted(UUID.randomUUID());
        Files.writeString(tempDir.resolve("mineagent_companions.json"), json);

        var restored = CompanionStore.loadAll(tempDir);

        assertEquals(1, restored.size());
        assertEquals("survival", restored.getFirst().gameMode());
    }
}
