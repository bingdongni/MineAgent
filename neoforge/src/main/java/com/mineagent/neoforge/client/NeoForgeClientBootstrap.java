package com.mineagent.neoforge.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Deterministic client-event registration invoked only on the physical client.
 *
 * <p>Keeping this class behind the distribution guard in the common mod
 * constructor prevents dedicated servers from resolving its client-only event
 * parameter types.
 */
public final class NeoForgeClientBootstrap {

    private NeoForgeClientBootstrap() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(NeoForgeClientModEvents::onRegisterKeyMappings);
        modBus.addListener(NeoForgeClientModEvents::onClientSetup);

        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::onRenderGui);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientEvents::onLoggingOut);
    }
}
