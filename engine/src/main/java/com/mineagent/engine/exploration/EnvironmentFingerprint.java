package com.mineagent.engine.exploration;

import net.minecraft.core.registries.BuiltInRegistries;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stable description of the game rules against which a mechanism was learned.
 *
 * <p>A registry ID can keep the same spelling while the providing mod changes
 * its implementation.  The fingerprint therefore combines the loader's mod
 * id/version list with the observable block, item, entity, menu and recipe-type
 * registries.  Reflection keeps this shared engine class loader-neutral: a
 * missing Fabric or NeoForge API is treated as an unavailable source, not as a
 * startup failure.
 */
public record EnvironmentFingerprint(String minecraftVersion, String loader,
                                     String modDigest, String registryDigest) {
    private static final String UNKNOWN = "unknown";

    public EnvironmentFingerprint {
        minecraftVersion = normalize(minecraftVersion);
        loader = normalize(loader);
        modDigest = normalize(modDigest);
        registryDigest = normalize(registryDigest);
    }

    public static EnvironmentFingerprint unknown() {
        return new EnvironmentFingerprint(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    /** Capture once per companion; all operations are bounded to registry IDs. */
    public static EnvironmentFingerprint capture() {
        String version = "1.21.1";
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            Object current = shared.getMethod("getCurrentVersion").invoke(null);
            version = String.valueOf(current.getClass().getMethod("getName").invoke(current));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The project is version-locked to 1.21.1; this fallback is more
            // useful for invalidation than turning a test bootstrap into fatal IO.
        }

        String loader = detectLoader();
        List<String> mods = new ArrayList<>();
        if ("fabric".equals(loader)) readFabricMods(mods);
        else if ("neoforge".equals(loader)) readNeoForgeMods(mods);

        List<String> registryIds = new ArrayList<>();
        try {
            BuiltInRegistries.BLOCK.keySet().forEach(id -> registryIds.add("block:" + id));
            BuiltInRegistries.ITEM.keySet().forEach(id -> registryIds.add("item:" + id));
            BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> registryIds.add("entity:" + id));
            BuiltInRegistries.MENU.keySet().forEach(id -> registryIds.add("menu:" + id));
            BuiltInRegistries.RECIPE_TYPE.keySet().forEach(id -> registryIds.add("recipe_type:" + id));
        } catch (RuntimeException | LinkageError unavailable) {
            registryIds.add("registry-unavailable");
        }
        return new EnvironmentFingerprint(version, loader,
                digest(mods), digest(registryIds));
    }

    /** Unknown legacy fingerprints never authorize automatic adapter replay. */
    public boolean compatible(EnvironmentFingerprint other) {
        if (other == null || UNKNOWN.equals(modDigest) || UNKNOWN.equals(registryDigest)
                || UNKNOWN.equals(other.modDigest) || UNKNOWN.equals(other.registryDigest)) {
            return false;
        }
        return minecraftVersion.equals(other.minecraftVersion)
                && loader.equals(other.loader)
                && modDigest.equals(other.modDigest)
                && registryDigest.equals(other.registryDigest);
    }

    public String compact() {
        return minecraftVersion + "/" + loader + "/"
                + shortDigest(modDigest) + "/" + shortDigest(registryDigest);
    }

    private static String detectLoader() {
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            return "fabric";
        } catch (ClassNotFoundException | LinkageError ignored) {
            try {
                Class.forName("net.neoforged.fml.ModList");
                return "neoforge";
            } catch (ClassNotFoundException | LinkageError absent) {
                return UNKNOWN;
            }
        }
    }

    private static void readFabricMods(List<String> out) {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Class<?> containerClass = Class.forName("net.fabricmc.loader.api.ModContainer");
            Class<?> metadataClass = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata");
            Class<?> versionClass = Class.forName("net.fabricmc.loader.api.Version");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object values = loaderClass.getMethod("getAllMods").invoke(loader);
            if (!(values instanceof Collection<?> containers)) return;
            for (Object container : containers) {
                // Invoke public API-interface methods rather than methods on
                // Fabric's package-private implementation classes; the latter
                // can fail access checks even when the method itself is public.
                Object metadata = containerClass.getMethod("getMetadata").invoke(container);
                String id = String.valueOf(metadataClass.getMethod("getId").invoke(metadata));
                Object version = metadataClass.getMethod("getVersion").invoke(metadata);
                String text = String.valueOf(versionClass
                        .getMethod("getFriendlyString").invoke(version));
                // MineAgent's own release version changes the learner, not the
                // target mod contract. Memory format migration handles engine
                // compatibility, so self-version must not stale every rule.
                if (!"mineagent".equals(id)) out.add(id + "@" + text);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            out.add("fabric-metadata-unavailable");
        }
    }

    private static void readNeoForgeMods(List<String> out) {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Class<?> modInfoClass = Class.forName(
                    "net.neoforged.neoforgespi.language.IModInfo");
            Object modList = modListClass.getMethod("get").invoke(null);
            Object values = modListClass.getMethod("getMods").invoke(modList);
            if (!(values instanceof Collection<?> mods)) return;
            for (Object mod : mods) {
                Method idMethod = modInfoClass.getMethod("getModId");
                Method versionMethod = modInfoClass.getMethod("getVersion");
                String id = String.valueOf(idMethod.invoke(mod));
                if (!"mineagent".equals(id)) {
                    out.add(id + "@" + versionMethod.invoke(mod));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            out.add("neoforge-metadata-unavailable");
        }
    }

    private static String digest(Collection<String> values) {
        ArrayList<String> ordered = new ArrayList<>(values == null ? List.of() : values);
        ordered.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : ordered) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            return UNKNOWN;
        }
    }

    private static String shortDigest(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
