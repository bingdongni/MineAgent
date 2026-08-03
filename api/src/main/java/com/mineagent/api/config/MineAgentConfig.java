package com.mineagent.api.config;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

public record MineAgentConfig(
    LLMConfig llm,
    CompanionConfig companion,
    SurvivalConfig survival,
    PathfindingConfig pathfinding
) {
    public record LLMConfig(
        String provider,
        String apiKey,
        String model,
        String baseUrl,
        double temperature,
        int maxTokens
    ) {
        public static LLMConfig DEFAULTS = new LLMConfig(
            "deepseek", "", "deepseek-chat", "", 0.7, 4096
        );
    }

    public record CompanionConfig(
        String name,
        String gameMode,
        String skinName,
        boolean instantBreak,
        boolean creativeReach
    ) {
        public static CompanionConfig DEFAULTS = new CompanionConfig(
            "MineAgent", "survival", "", false, false
        );
    }

    public record SurvivalConfig(
        int foodCritical,
        int foodLow,
        double healthFlee,
        int stuckTimeTicks,
        boolean autoEat,
        boolean fightBack,
        boolean pickupItems,
        boolean avoidCreeper
    ) {
        public static SurvivalConfig DEFAULTS = new SurvivalConfig(
            6, 12, 6.0, 60, true, true, true, true
        );
    }

    public record PathfindingConfig(
        int maxSearchNodes,
        boolean allowDigThrough,
        boolean allowBridge,
        boolean allowParkour
    ) {
        public static PathfindingConfig DEFAULTS = new PathfindingConfig(
            5000, true, true, true
        );
    }

    public static MineAgentConfig DEFAULTS = new MineAgentConfig(
        LLMConfig.DEFAULTS, CompanionConfig.DEFAULTS,
        SurvivalConfig.DEFAULTS, PathfindingConfig.DEFAULTS
    );

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting().serializeNulls().create();

    public static MineAgentConfig load(Path configDir) {
        Path configFile = configDir.resolve("mineagent.json");
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                // Gson accepts a top-level null and missing nested records.
                // Normalize before engine initialization so a partially edited
                // config cannot turn into a startup NPE or non-finite request.
                return normalize(GSON.fromJson(reader, MineAgentConfig.class));
            } catch (Exception e) {
                System.err.println("[MineAgent] Failed to load config: " + e.getMessage());
                System.err.println("[MineAgent] Using defaults");
            }
        }
        // Save defaults
        saveDefaults(configFile);
        return DEFAULTS;
    }

    private static MineAgentConfig normalize(MineAgentConfig loaded) {
        if (loaded == null) return DEFAULTS;

        LLMConfig rawLlm = loaded.llm() != null ? loaded.llm() : LLMConfig.DEFAULTS;
        double temperature = Double.isFinite(rawLlm.temperature())
                && rawLlm.temperature() >= 0.0 && rawLlm.temperature() <= 2.0
                ? rawLlm.temperature() : LLMConfig.DEFAULTS.temperature();
        int maxTokens = rawLlm.maxTokens() > 0 && rawLlm.maxTokens() <= 1_000_000
                ? rawLlm.maxTokens() : LLMConfig.DEFAULTS.maxTokens();
        LLMConfig llm = new LLMConfig(
                nonBlank(rawLlm.provider(), LLMConfig.DEFAULTS.provider()),
                rawLlm.apiKey() != null ? rawLlm.apiKey() : "",
                nonBlank(rawLlm.model(), LLMConfig.DEFAULTS.model()),
                rawLlm.baseUrl() != null ? rawLlm.baseUrl() : "",
                temperature, maxTokens);

        CompanionConfig rawCompanion = loaded.companion() != null
                ? loaded.companion() : CompanionConfig.DEFAULTS;
        CompanionConfig companion = new CompanionConfig(
                nonBlank(rawCompanion.name(), CompanionConfig.DEFAULTS.name()),
                nonBlank(rawCompanion.gameMode(), CompanionConfig.DEFAULTS.gameMode()),
                rawCompanion.skinName() != null ? rawCompanion.skinName() : "",
                rawCompanion.instantBreak(), rawCompanion.creativeReach());

        SurvivalConfig rawSurvival = loaded.survival() != null
                ? loaded.survival() : SurvivalConfig.DEFAULTS;
        int foodLow = bounded(rawSurvival.foodLow(), 0, 20,
                SurvivalConfig.DEFAULTS.foodLow());
        int foodCritical = bounded(rawSurvival.foodCritical(), 0, 20,
                SurvivalConfig.DEFAULTS.foodCritical());
        // Independently valid values can still form an invalid pair. Preserve
        // critical as the lower, more urgent threshold before engine startup.
        foodCritical = Math.min(foodCritical, foodLow);
        SurvivalConfig survival = new SurvivalConfig(
                foodCritical,
                foodLow,
                Double.isFinite(rawSurvival.healthFlee())
                        && rawSurvival.healthFlee() >= 0.0
                        ? rawSurvival.healthFlee() : SurvivalConfig.DEFAULTS.healthFlee(),
                bounded(rawSurvival.stuckTimeTicks(), 1, 72_000,
                        SurvivalConfig.DEFAULTS.stuckTimeTicks()),
                rawSurvival.autoEat(), rawSurvival.fightBack(),
                rawSurvival.pickupItems(), rawSurvival.avoidCreeper());

        PathfindingConfig rawPath = loaded.pathfinding() != null
                ? loaded.pathfinding() : PathfindingConfig.DEFAULTS;
        PathfindingConfig pathfinding = new PathfindingConfig(
                bounded(rawPath.maxSearchNodes(), 100, 1_000_000,
                        PathfindingConfig.DEFAULTS.maxSearchNodes()),
                rawPath.allowDigThrough(), rawPath.allowBridge(), rawPath.allowParkour());
        return new MineAgentConfig(llm, companion, survival, pathfinding);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    private static void saveDefaults(Path configFile) {
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(DEFAULTS, writer);
            }
            System.out.println("[MineAgent] Created default config at " + configFile);
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to save default config: " + e.getMessage());
        }
    }

    /**
     * Save this configuration to the {@code mineagent.json} file in the given
     * config directory. Used by the {@code /mineagent setconfig} command to
     * persist in-game config changes.
     *
     * @param configDir the platform config directory
     */
    public void save(Path configDir) {
        Path configFile = configDir.resolve("mineagent.json");
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(this, writer);
            }
            System.out.println("[MineAgent] Config saved to " + configFile);
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to save config: " + e.getMessage());
        }
    }
}
