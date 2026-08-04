package com.mineagent.fabric.client;

import com.mineagent.engine.client.MineAgentKeyMappings;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

/**
 * Fabric registration adapter for the shared MineAgent key mappings.
 *
 * <p>The mapping definitions live in the shared client source set so Fabric
 * and NeoForge expose exactly the same controls and defaults.
 */
public final class ClientKeyBindings {

    private ClientKeyBindings() {}

    public static void register() {
        MineAgentKeyMappings.all().forEach(KeyBindingHelper::registerKeyBinding);
    }
}
