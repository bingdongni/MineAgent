package com.mineagent.fabric.mixin;

import com.mineagent.engine.MineAgentEngine;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin that intercepts name tag usage on companion entities.
 *
 * <p>When a player right-clicks a companion (fake ServerPlayer) with a
 * renamed name tag, the companion's display name changes but the skin
 * and all other configuration remain unchanged.
 *
 * <p>This makes companions behave like vanilla namable entities —
 * the player just needs a name tag (renamed in an anvil) and right-clicks
 * the companion with it.
 */
@Mixin(NameTagItem.class)
public abstract class NameTagMixin {

    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void onNameTagUsed(ItemStack itemStack, Player player,
                               net.minecraft.world.entity.LivingEntity entity,
                               InteractionHand hand,
                               CallbackInfoReturnable<InteractionResult> cir) {
        // Check if the target entity is a companion (fake ServerPlayer)
        if (!(entity instanceof ServerPlayer target)) return;

        // Check if this ServerPlayer is a companion
        if (!MineAgentEngine.isCompanionPlayer(target.getUUID())) return;

        if (!MineAgentEngine.isCompanionOwnedBy(target.getUUID(), player.getUUID())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[MineAgent] Only the companion's owner can rename it."));
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        // getHoverName() falls back to the translated item name, which made an
        // untouched name tag rename a companion to "Name Tag". Vanilla uses
        // CUSTOM_NAME here, so require the same explicit anvil-authored value.
        var customName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return;
        String newName = customName.getString();

        // Rename the companion — skin and config stay unchanged
        boolean success = MineAgentEngine.renameCompanion(target.getUUID(), newName);
        if (success) {
            // Consume the name tag
            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
            }
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
