package com.mineagent.neoforge.client;

import com.mineagent.engine.client.MineAgentClientController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * NeoForge game-bus adapter for the complete shared visual client.
 */
public final class NeoForgeClientEvents {

    private NeoForgeClientEvents() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        MineAgentClientController.onClientTick(Minecraft.getInstance());
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        MineAgentClientController.renderHud(
                event.getGuiGraphics(), event.getPartialTick());
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var buffers = client.renderBuffers().bufferSource();
        MineAgentClientController.renderWorld(
                event.getPoseStack(),
                buffers,
                event.getCamera().getPosition()
        );
        // All shared world overlays use RenderType.lines(). NeoForge does not
        // automatically flush custom vertices emitted from this stage.
        buffers.endBatch(RenderType.lines());
    }

    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft.getInstance().execute(
                MineAgentClientController::requestCompanionSync);
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MineAgentClientController.clearClientState();
    }
}
