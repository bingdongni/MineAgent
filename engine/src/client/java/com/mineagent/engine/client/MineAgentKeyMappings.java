package com.mineagent.engine.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Loader-neutral definitions for every MineAgent key mapping.
 *
 * <p>Fabric and NeoForge must register the same {@link KeyMapping} instances
 * with their own event APIs. Keeping only the registration call in each
 * platform module prevents the bindings, defaults, and UI behavior from
 * drifting apart.
 */
public final class MineAgentKeyMappings {

    private static final String CATEGORY = "key.categories.mineagent";

    public static final KeyMapping OPEN_MENU = mapping(
            "key.mineagent.open_menu", GLFW.GLFW_KEY_M);
    public static final KeyMapping OPEN_CHAT = mapping(
            "key.mineagent.open_chat", GLFW.GLFW_KEY_C);
    public static final KeyMapping TOGGLE_STATUS_HUD = mapping(
            "key.mineagent.toggle_status_hud", GLFW.GLFW_KEY_H);
    public static final KeyMapping TOGGLE_PATH_DEBUG = mapping(
            "key.mineagent.toggle_path_debug", GLFW.GLFW_KEY_P);
    public static final KeyMapping TOGGLE_VISION = mapping(
            "key.mineagent.toggle_vision", GLFW.GLFW_KEY_V);
    public static final KeyMapping TOGGLE_LABEL = mapping(
            "key.mineagent.toggle_label", GLFW.GLFW_KEY_N);

    private static final List<KeyMapping> ALL = List.of(
            OPEN_MENU,
            OPEN_CHAT,
            TOGGLE_STATUS_HUD,
            TOGGLE_PATH_DEBUG,
            TOGGLE_VISION,
            TOGGLE_LABEL
    );

    private MineAgentKeyMappings() {}

    /** Return the immutable set that each loader registers exactly once. */
    public static List<KeyMapping> all() {
        return ALL;
    }

    private static KeyMapping mapping(String translationKey, int keyCode) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM,
                keyCode, CATEGORY);
    }
}
