package com.mineagent.engine.entity.fakeplayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Asynchronously loads player skins from the Mojang API.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Online mode</b>: Fetches the skin texture from Mojang's session server
 *       by player name. The skin includes a cryptographic signature, so it works
 *       on servers with signature verification enabled.</li>
 *   <li><b>Offline mode</b>: If the API call fails or the player doesn't exist,
 *       the companion falls back to the default Steve/Alex skin.</li>
 * </ul>
 *
 * <p>Skin loading is asynchronous — the companion spawns immediately with the
 * default skin, then the skin is applied when the API response arrives.
 */
public final class SkinLoader {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService SKIN_EXECUTOR = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "MineAgent-SkinLoader");
        t.setDaemon(true);
        return t;
    });

    /** In-memory cache: companion UUID → last loaded skin. */
    private static final Map<java.util.UUID, SkinResult> SKIN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private SkinLoader() {}

    /**
     * Cache a skin result for a companion (called when skin is applied).
     */
    public static void cacheSkin(java.util.UUID companionId, SkinResult skin) {
        if (skin != null && skin.isValid()) {
            SKIN_CACHE.put(companionId, skin);
        }
    }

    /**
     * Get cached skin for a companion.
     */
    public static Optional<SkinResult> getCachedSkin(java.util.UUID companionId) {
        return Optional.ofNullable(SKIN_CACHE.get(companionId));
    }

    /**
     * Forget a despawned companion's cached texture. The cache is keyed by
     * profile UUID, so retaining entries would leak one texture per profile.
     */
    public static void evictCachedSkin(java.util.UUID companionId) {
        if (companionId != null) SKIN_CACHE.remove(companionId);
    }

    /**
     * Result of a skin lookup.
     */
    public record SkinResult(String value, String signature) {
        /**
         * Convert to a Mojang Property for GameProfile.
         */
        public Property toProperty() {
            return signature != null
                    ? new Property("textures", value, signature)
                    : new Property("textures", value);
        }

        public boolean isValid() {
            return value != null && !value.isEmpty();
        }
    }

    /**
     * Asynchronously fetch a player's skin from the Mojang API.
     *
     * @param playerName the Minecraft player name to look up
     * @param callback  called on the skin worker thread when loading finishes;
     *                  callers that touch Minecraft state must reschedule
     */
    public static void loadSkinAsync(String playerName,
                                       java.util.function.Consumer<Optional<SkinResult>> callback) {
        Objects.requireNonNull(callback, "callback");
        SKIN_EXECUTOR.submit(() -> {
            Optional<SkinResult> result = loadSkin(playerName);
            try {
                callback.accept(result);
            } catch (RuntimeException callbackError) {
                System.err.println("[MineAgent] Skin callback failed: "
                        + callbackError.getMessage());
            }
        });
    }

    /**
     * Synchronously fetch a player's skin from the Mojang API.
     *
     * @param playerName the Minecraft player name to look up
     * @return the skin result, or empty if not found
     */
    public static Optional<SkinResult> loadSkin(String playerName) {
        // Validate before interpolating the value into a Mojang API URI.
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }
        try {
            // Step 1: Get UUID from player name
            String uuid = fetchUuid(playerName);
            if (uuid == null) {
                System.out.println("[MineAgent] Skin lookup: player '" + playerName + "' not found");
                return Optional.empty();
            }

            // Step 2: Get texture properties from UUID
            Optional<SkinResult> skin = fetchTextureProperty(uuid);
            if (skin.isPresent()) {
                System.out.println("[MineAgent] Skin loaded for player '" + playerName + "'");
            } else {
                System.out.println("[MineAgent] No skin texture found for player '" + playerName + "'");
            }
            return skin;
        } catch (Exception e) {
            System.err.println("[MineAgent] Skin loading failed for '" + playerName + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Step 1: Fetch the UUID for a player name from Mojang API.
     */
    private static String fetchUuid(String playerName) throws Exception {
        String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return null; // Player not found
        }
        if (response.statusCode() != 200) {
            System.err.println("[MineAgent] Mojang API error " + response.statusCode()
                    + ": " + response.body());
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("id") || !json.get("id").isJsonPrimitive()) return null;
        // Mojang returns UUID without dashes, we need to format it
        String rawUuid = json.get("id").getAsString();
        if (!rawUuid.matches("[0-9a-fA-F]{32}")) return null;
        return formatUuid(rawUuid);
    }

    /**
     * Step 2: Fetch the texture property from Mojang's session server.
     */
    private static Optional<SkinResult> fetchTextureProperty(String uuid) throws Exception {
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("[MineAgent] Session server error " + response.statusCode());
            return Optional.empty();
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

        if (!json.has("properties") || !json.get("properties").isJsonArray()) {
            return Optional.empty();
        }

        // Find the "textures" property
        for (var propElem : json.getAsJsonArray("properties")) {
            if (!propElem.isJsonObject()) continue;
            JsonObject prop = propElem.getAsJsonObject();
            if (prop.has("name") && prop.get("name").isJsonPrimitive()
                    && "textures".equals(prop.get("name").getAsString())
                    && prop.has("value") && prop.get("value").isJsonPrimitive()) {
                String value = prop.get("value").getAsString();
                if (value.isBlank()) continue;
                String signature = prop.has("signature")
                        && prop.get("signature").isJsonPrimitive()
                        ? prop.get("signature").getAsString() : null;
                return Optional.of(new SkinResult(value, signature));
            }
        }

        return Optional.empty();
    }

    /**
     * Format a UUID string without dashes to the standard format.
     * e.g. "069a79f444e94726a5befca90e38aaf5" → "069a79f4-44e9-4726-a5be-fca90e38aaf5"
     */
    private static String formatUuid(String raw) {
        if (raw.length() != 32) return raw;
        return raw.substring(0, 8) + "-"
                + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-"
                + raw.substring(16, 20) + "-"
                + raw.substring(20);
    }

    /**
     * Decode the base64-encoded texture value to inspect the skin URL.
     * Useful for debugging.
     */
    public static String decodeTextureValue(String base64Value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Value);
            return new String(decoded);
        } catch (Exception e) {
            return "{}";
        }
    }
}
