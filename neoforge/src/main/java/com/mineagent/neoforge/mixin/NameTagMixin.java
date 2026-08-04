package com.mineagent.neoforge.mixin;

import com.mineagent.engine.MineAgentEngine;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enables owner-only vanilla name-tag renaming for fake-player companions. */
@Mixin(NameTagItem.class)
public abstract class NameTagMixin {

    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void mineagent$onNameTagUsed(ItemStack itemStack, Player player,
                                         LivingEntity entity, InteractionHand hand,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        if (!(entity instanceof ServerPlayer target)
                || !MineAgentEngine.isCompanionPlayer(target.getUUID())) {
            return;
        }
        if (!MineAgentEngine.isCompanionOwnedBy(target.getUUID(), player.getUUID())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[MineAgent] Only the companion's owner can rename it."));
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        // Match vanilla's explicit custom-name check; getHoverName() also
        // returns the default item translation for an untouched name tag.
        var customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;

        String newName = customName.getString();
        if (MineAgentEngine.renameCompanion(target.getUUID(), newName)) {
            if (!player.getAbilities().instabuild) itemStack.shrink(1);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[MineAgent] Companion renamed to '" + newName + "'!"));
            cir.setReturnValue(InteractionResult.SUCCESS);
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[MineAgent] That companion name is invalid or already in use."));
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
