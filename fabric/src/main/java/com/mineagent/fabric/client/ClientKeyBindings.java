package com.mineagent.fabric.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Registers all MineAgent key bindings via the Fabric KeyBinding API.
 *
 * <p>Bindings:
 * <ul>
 *   <li><b>M</b> - Open MineAgent control panel (main menu)</li>
 *   <li><b>C</b> - Open companion chat screen</li>
 *   <li><b>H</b> - Toggle companion status HUD overlay</li>
 *   <li><b>P</b> - Toggle path debug rendering</li>
 * </ul>
 *
 * <p>Call {@link #register()} once during client-side initialization
 * (typically from {@code MineAgentClient#onInitializeClient}).
 */
public final class ClientKeyBindings {

    /** Key category shown in Minecraft's Controls options screen. */
    private static final String CATEGORY = "key.categories.mineagent";

    /** Open the MineAgent control panel (main menu). Default: M. */
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.mineagent.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY
    );

    /** Open the companion chat screen. Default: C. */
    public static final KeyMapping OPEN_CHAT = new KeyMapping(
            "key.mineagent.open_chat",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY
    );

    /** Toggle the companion status HUD overlay. Default: H. */
    public static final KeyMapping TOGGLE_STATUS_HUD = new KeyMapping(
            "key.mineagent.toggle_status_hud",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY
    );

    /** Toggle the path debug renderer. Default: P. */
    public static final KeyMapping TOGGLE_PATH_DEBUG = new KeyMapping(
            "key.mineagent.toggle_path_debug",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            CATEGORY
    );

    /** Toggle the companion vision renderer (LOS ray, target highlight, vision cone). Default: V. */
    public static final KeyMapping TOGGLE_VISION = new KeyMapping(
            "key.mineagent.toggle_vision",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY
    );

    /** Toggle the companion floating label (name + task + health). Default: N. */
    public static final KeyMapping TOGGLE_LABEL = new KeyMapping(
            "key.mineagent.toggle_label",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY
    );

    private ClientKeyBindings() {
        // utility class - no instances
    }

    /**
     * Register all MineAgent key bindings with Fabric.
     * Must be called during client mod initialization.
     */
    public static void register() {
        KeyBindingHelper.registerKeyBinding(OPEN_MENU);
        KeyBindingHelper.registerKeyBinding(OPEN_CHAT);
        KeyBindingHelper.registerKeyBinding(TOGGLE_STATUS_HUD);
        KeyBindingHelper.registerKeyBinding(TOGGLE_PATH_DEBUG);
        KeyBindingHelper.registerKeyBinding(TOGGLE_VISION);
        KeyBindingHelper.registerKeyBinding(TOGGLE_LABEL);
    }

    /**
     * Check if a key binding was just pressed this frame.
     * Call this inside a client tick or render event.
     *
     * @param mapping the key binding to check
     * @return true if the key was consumed this frame
     */
    public static boolean wasPressed(KeyMapping mapping) {
        return mapping.consumeClick();
    }
}
