package com.mineagent.fabric;

import com.mineagent.fabric.client.MineAgentClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entry point. Implements {@link ClientModInitializer}.
 * <p>
 * Delegates all client-side initialization to {@link MineAgentClient},
 * which registers key bindings, HUD rendering, tick handlers, and
 * packet handlers.
 */
public class MineAgentFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Delegate to the actual client initializer
        MineAgentClient client = new MineAgentClient();
        client.onInitializeClient();
    }
}

