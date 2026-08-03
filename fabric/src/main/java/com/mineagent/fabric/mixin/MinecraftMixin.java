package com.mineagent.fabric.mixin;

import com.mineagent.engine.MineAgentEngine;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into the {@link Minecraft} client instance.
 * <p>
 * Hooks into the client tick loop to drive client-side MineAgent logic
 * such as path debug rendering updates and HUD overlay refreshing.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /**
     * Inject at the end of the client tick method.
     * Drives client-side periodic tasks for MineAgent.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void mineagent$onClientTick(CallbackInfo ci) {
        MineAgentEngine.onClientTick();
    }
}
