package com.mineagent.engine.entity;

import com.google.gson.*;
import com.mojang.authlib.properties.Property;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent storage for companion state.
 *
 * <p>Saves companion data to a JSON file so that companions survive
 * server restarts and world reloads. When a player exits and re-enters
 * the world, their companion (including skin, model settings, and all
 * configuration) is automatically restored.
 *
 * <p>Storage location: {@code <world>/data/mineagent_companions.json}
 * This is per-world, so different worlds can have different companions.
 *
 * <p>Saved data includes:
 * <ul>
 *   <li>Owner UUID and name</li>
 *   <li>Companion name</li>
 *   <li>LLM provider, model, API key, base URL, temperature</li>
 *   <li>Skin player name and cached skin texture (value + signature)</li>
 * </ul>
 */
public final class CompanionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mineagent_companions.json";

    private CompanionStore() {}

    /**
     * Represents a single companion's saved state.
     */
    public record SavedCompanion(
            String ownerUuid,
            String ownerName,
            String companionName,
            String providerId,
            String apiKey,
            String model,
            String baseUrl,
            double temperature,
            String reasoningEffort,
            String skinName,
            String skinValue,
            String skinSignature
    ) {}

    /**
     * Save all companions' state to the world data file.
     *
     * @param worldDataDir the world's data directory (e.g. world/data/)
     * @param companions   the companions to save
     */
    public static synchronized void saveAll(Path worldDataDir, Collection<SavedCompanion> companions) {
        try {
            Files.createDirectories(worldDataDir);
            Path file = worldDataDir.resolve(FILE_NAME);

            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray arr = new JsonArray();
            for (var c : companions) {
                JsonObject obj = new JsonObject();
                obj.addProperty("ownerUuid", c.ownerUuid());
                obj.addProperty("ownerName", c.ownerName());
                obj.addProperty("companionName", c.companionName());
                obj.addProperty("providerId", c.providerId());
                obj.addProperty("apiKey", c.apiKey());
                obj.addProperty("model", c.model());
                obj.addProperty("baseUrl", c.baseUrl() != null ? c.baseUrl() : "");
                obj.addProperty("temperature", c.temperature());
                obj.addProperty("reasoningEffort", c.reasoningEffort() != null ? c.reasoningEffort() : "");
                obj.addProperty("skinName", c.skinName() != null ? c.skinName() : "");
                obj.addProperty("skinValue", c.skinValue() != null ? c.skinValue() : "");
                obj.addProperty("skinSignature", c.skinSignature() != null ? c.skinSignature() : "");
                arr.add(obj);
            }
            root.add("companions", arr);

            // Write-then-replace prevents a crash during serialization from
            // destroying the last valid companion list.
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temp)) {
                    GSON.toJson(root, writer);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // Serialization or replacement failure must not leave a
                // truncated temporary file indefinitely in world/data.
                Files.deleteIfExists(temp);
            }
            System.out.println("[MineAgent] Saved " + companions.size() + " companion(s) to " + file);
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to save companions: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load all saved companions from the world data file.
     *
     * @param worldDataDir the world's data directory
     * @return list of saved companions (empty if file doesn't exist)
     */
    public static synchronized List<SavedCompanion> loadAll(Path worldDataDir) {
        Path file = worldDataDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("companions") || !root.get("companions").isJsonArray()) {
                return Collections.emptyList();
            }

            List<SavedCompanion> result = new ArrayList<>();
            for (var elem : root.getAsJsonArray("companions")) {
                // One damaged entry should not erase every other companion.
                try {
                    if (!elem.isJsonObject()) continue;
                    JsonObject obj = elem.getAsJsonObject();
                    String ownerUuid = requiredString(obj, "ownerUuid");
                    UUID.fromString(ownerUuid);
                    String companionName = requiredString(obj, "companionName");
                    String providerId = requiredString(obj, "providerId");
                    String apiKey = requiredString(obj, "apiKey");
                    String model = requiredString(obj, "model");
                    double temperature = optionalDouble(obj, "temperature", 0.7);
                    if (!Double.isFinite(temperature)) temperature = 0.7;
                    result.add(new SavedCompanion(
                            ownerUuid,
                            optionalString(obj, "ownerName", ""),
                            companionName, providerId, apiKey, model,
                            optionalString(obj, "baseUrl", ""), temperature,
                            optionalString(obj, "reasoningEffort", null),
                            optionalString(obj, "skinName", ""),
                            optionalString(obj, "skinValue", null),
                            optionalString(obj, "skinSignature", null)
                    ));
                } catch (RuntimeException badEntry) {
                    System.err.println("[MineAgent] Skipping invalid saved companion: "
                            + badEntry.getMessage());
                }
            }
            System.out.println("[MineAgent] Loaded " + result.size() + " saved companion(s)");
            return result;
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to load companions: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Remove a specific companion from the save file by owner UUID and name.
     *
     * @param worldDataDir  the world's data directory
     * @param ownerUuid     the owner's UUID string
     * @param companionName the companion's name (null to remove all of owner's)
     */
    public static synchronized void remove(Path worldDataDir, String ownerUuid, String companionName) {
        List<SavedCompanion> all = loadAll(worldDataDir);
        List<SavedCompanion> filtered = new ArrayList<>();
        for (var c : all) {
            if (c.ownerUuid().equals(ownerUuid)
                    && (companionName == null
                        || companionName.equalsIgnoreCase(c.companionName()))) {
                // skip (remove)
            } else {
                filtered.add(c);
            }
        }
        saveAll(worldDataDir, filtered);
    }

    /**
     * Save or update a single companion (upsert by owner UUID + companion name).
     * Preserves other companions belonging to the same owner.
     *
     * @param worldDataDir the world's data directory
     * @param companion    the companion to save
     */
    public static synchronized void save(Path worldDataDir, SavedCompanion companion) {
        List<SavedCompanion> all = loadAll(worldDataDir);
        List<SavedCompanion> updated = new ArrayList<>();
        boolean found = false;
        for (var c : all) {
            if (c.ownerUuid().equals(companion.ownerUuid())
                    && c.companionName().equalsIgnoreCase(companion.companionName())) {
                updated.add(companion);
                found = true;
            } else {
                updated.add(c);
            }
        }
        if (!found) {
            updated.add(companion);
        }
        saveAll(worldDataDir, updated);
    }

    /**
     * Clear the save file (used when all companions are removed).
     */
    public static synchronized void clear(Path worldDataDir) {
        saveAll(worldDataDir, Collections.emptyList());
    }

    private static String requiredString(JsonObject object, String key) {
        String value = optionalString(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return fallback;
        return object.get(key).getAsString();
    }

    private static double optionalDouble(JsonObject object, String key, double fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            return object.get(key).getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
