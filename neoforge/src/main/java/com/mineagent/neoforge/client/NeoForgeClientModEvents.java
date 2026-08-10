package com.mineagent.neoforge.client;

import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.engine.client.MineAgentKeyMappings;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * NeoForge mod-bus registration for client-only facilities.
 *
 * <p>The physical-client restriction is essential: dedicated servers must
 * never initialize Minecraft client classes merely because the common mod
 * entry point is loaded.
 */
public final class NeoForgeClientModEvents {

    private NeoForgeClientModEvents() {}

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        MineAgentKeyMappings.all().forEach(event::register);
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MineAgentClientController.setUiActionSender(
                    NeoForgeClientPayloadHandler::sendUiAction);
            MineAgentClientController.setCompanionSetupSender(
                    NeoForgeClientPayloadHandler::sendCompanionSetup);
        });
    }
}
