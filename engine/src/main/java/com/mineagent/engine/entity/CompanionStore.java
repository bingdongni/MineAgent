package com.mineagent.engine.entity;

import com.google.gson.*;
import com.mojang.authlib.properties.Property;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
            String gameMode,
            String skinName,
            String skinValue,
            String skinSignature,
            String bodyData
    ) {}

    /**
     * Save all companions' state to the world data file.
     *
     * @param worldDataDir the world's data directory (e.g. world/data/)
     * @param companions   the companions to save
     */
    public static synchronized void saveAll(
            Path worldDataDir, Collection<SavedCompanion> companions) {
        try {
            Files.createDirectories(worldDataDir);
            Path file = worldDataDir.resolve(FILE_NAME);

            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
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
                obj.addProperty("gameMode", c.gameMode() != null ? c.gameMode() : "survival");
                obj.addProperty("skinName", c.skinName() != null ? c.skinName() : "");
                obj.addProperty("skinValue", c.skinValue() != null ? c.skinValue() : "");
                obj.addProperty("skinSignature", c.skinSignature() != null ? c.skinSignature() : "");
                obj.addProperty("bodyData", c.bodyData() != null ? c.bodyData() : "");
                arr.add(obj);
            }
            root.add("companions", arr);

            writeAtomic(file, GSON.toJson(root));
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

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("companions") || !root.get("companions").isJsonArray()) {
                throw new JsonParseException("missing companions array");
            }

            Map<String, SavedCompanion> valid = new LinkedHashMap<>();
            int skipped = 0;
            for (var elem : root.getAsJsonArray("companions")) {
                try {
                    SavedCompanion companion = parseEntry(elem);
                    String key = companion.ownerUuid().toLowerCase(Locale.ROOT) + "\n"
                            + companion.companionName().toLowerCase(Locale.ROOT);
                    valid.put(key, companion);
                } catch (RuntimeException invalidEntry) {
                    skipped++;
                }
            }
            List<SavedCompanion> result = new ArrayList<>(valid.values());
            if (skipped > 0) {
                System.err.println("[MineAgent] Skipped " + skipped
                        + " invalid saved companion record(s)");
            }
            System.out.println("[MineAgent] Loaded " + result.size() + " saved companion(s)");
            return result;
        } catch (Exception e) {
            System.err.println("[MineAgent] Failed to load companions: " + e.getMessage());
            quarantine(file);
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
    public static synchronized void remove(
            Path worldDataDir, String ownerUuid, String companionName) {
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

    private static SavedCompanion parseEntry(JsonElement element) {
        if (!element.isJsonObject()) throw new JsonParseException("entry is not an object");
        JsonObject obj = element.getAsJsonObject();
        String ownerUuid = requiredString(obj, "ownerUuid");
        UUID.fromString(ownerUuid);
        String companionName = requiredString(obj, "companionName");
        String providerId = requiredString(obj, "providerId");
        String apiKey = requiredString(obj, "apiKey");
        String model = requiredString(obj, "model");
        double temperature = obj.has("temperature")
                ? obj.get("temperature").getAsDouble() : 0.7;
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new JsonParseException("invalid temperature");
        }
        return new SavedCompanion(
                ownerUuid,
                optionalString(obj, "ownerName", ""),
                companionName,
                providerId,
                apiKey,
                model,
                optionalString(obj, "baseUrl", ""),
                temperature,
                emptyToNull(optionalString(obj, "reasoningEffort", null)),
                com.mineagent.api.entity.CompanionGameMode.orDefault(
                        optionalString(obj, "gameMode", "survival")).wireName(),
                optionalString(obj, "skinName", ""),
                emptyToNull(optionalString(obj, "skinValue", null)),
                emptyToNull(optionalString(obj, "skinSignature", null)),
                emptyToNull(optionalString(obj, "bodyData", null)));
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name, null);
        if (value == null || value.isBlank()) {
            throw new JsonParseException("missing " + name);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name, String fallback) {
        if (!object.has(name) || object.get(name).isJsonNull()) return fallback;
        return object.get(name).getAsString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void writeAtomic(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(),
                target.getFileName() + ".", ".tmp");
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void quarantine(Path file) {
        if (!Files.exists(file)) return;
        Path backup = file.resolveSibling(file.getFileName()
                + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[MineAgent] Preserved invalid companion store as " + backup);
        } catch (IOException moveFailure) {
            System.err.println("[MineAgent] Could not preserve invalid companion store: "
                    + moveFailure.getMessage());
        }
    }
}
