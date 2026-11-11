package com.mineagent.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class MineAgentConfigTest {
    @TempDir Path directory;

    @Test void firstLaunchCreatesReloadableDefaults() {
        assertEquals(MineAgentConfig.DEFAULTS, MineAgentConfig.load(directory));
        assertTrue(Files.isRegularFile(directory.resolve("mineagent.json")));
        assertEquals(MineAgentConfig.DEFAULTS, MineAgentConfig.load(directory));
    }

    @Test void partialConnectionSettingsKeepSafetyDefaultsAndTheOriginalFile() throws Exception {
        String input = """
                {"llm":{"provider":"ollama","model":"local-tools","baseUrl":"http://127.0.0.1:11434"}}
                """;
        Files.writeString(directory.resolve("mineagent.json"), input);
        var config = MineAgentConfig.load(directory);
        assertEquals("ollama", config.llm().provider());
        assertEquals("", config.llm().apiKey());
        assertEquals("local-tools", config.llm().model());
        assertEquals(MineAgentConfig.SurvivalConfig.DEFAULTS, config.survival());
        assertEquals(MineAgentConfig.CompanionConfig.DEFAULTS, config.companion());
        assertEquals(input, Files.readString(directory.resolve("mineagent.json")));
    }

    @Test void legacyNullSectionsAndFieldsDoNotDisableDefaults() throws Exception {
        Files.writeString(directory.resolve("mineagent.json"),
                "{\"llm\":{\"apiKey\":null},\"survival\":null,\"companion\":{\"gameMode\":null}}");
        assertEquals(MineAgentConfig.DEFAULTS, MineAgentConfig.load(directory));
    }

    @Test void invalidLimitsAndGameModeFallBackWithoutLosingCredentials() throws Exception {
        Files.writeString(directory.resolve("mineagent.json"), """
                {"llm":{"apiKey":"test-placeholder","temperature":-1,"maxTokens":0},
                 "companion":{"gameMode":"invalid"}}
                """);
        var config = MineAgentConfig.load(directory);
        assertEquals("test-placeholder", config.llm().apiKey());
        assertEquals(MineAgentConfig.LLMConfig.DEFAULTS.temperature(), config.llm().temperature());
        assertEquals(MineAgentConfig.LLMConfig.DEFAULTS.maxTokens(), config.llm().maxTokens());
        assertEquals("survival", config.companion().gameMode());
    }

    @Test void malformedJsonRemainsAvailableForRepair() throws Exception {
        String damaged = "{\"llm\":{\"apiKey\":\"test-placeholder\"},\"companion\":";
        Files.writeString(directory.resolve("mineagent.json"), damaged);
        assertEquals(MineAgentConfig.DEFAULTS, MineAgentConfig.load(directory));
        assertEquals(damaged, Files.readString(directory.resolve("mineagent.json")),
                "Loading a typo must not overwrite the user's endpoint and credentials");
    }

    @Test void nonObjectRootRemainsAvailableForRepair() throws Exception {
        Files.writeString(directory.resolve("mineagent.json"), "[]");
        assertEquals(MineAgentConfig.DEFAULTS, MineAgentConfig.load(directory));
        assertEquals("[]", Files.readString(directory.resolve("mineagent.json")));
    }
}
