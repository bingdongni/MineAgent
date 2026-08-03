package com.mineagent.fabric.mixin;

import com.mineagent.engine.MineAgentEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import net.minecraft.core.component.DataComponents;
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
                                InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        // Check if the target entity is a companion (fake ServerPlayer)
        if (!(entity instanceof ServerPlayer target)) return;

        // Check if this ServerPlayer is a companion
        if (!MineAgentEngine.isCompanionPlayer(target.getUUID())) return;

        // Vanilla only treats an anvil-renamed tag as a rename operation.
        // getHoverName() also returns the translatable default "Name Tag", so
        // checking it alone allowed an ordinary tag to rename the companion.
        if (!itemStack.has(DataComponents.CUSTOM_NAME)) return;

        // A companion is a player entity; unlike tame mobs, vanilla has no
        // ownership gate here. Enforce MineAgent ownership explicitly.
        if (!(player instanceof ServerPlayer serverPlayer)
                || !MineAgentEngine.isCompanionOwnedBy(
                        target.getUUID(), serverPlayer.getUUID())) {
            // Returning without setting the callback lets NameTagItem's
            // original method run, which would rename the player anyway and
            // bypass both ownership and MineAgent persistence.
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        var hoverName = itemStack.getHoverName();
        String newName = hoverName != null ? hoverName.getString() : null;
        if (newName == null || newName.isEmpty()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        // Rename the companion — skin and config stay unchanged
        boolean success = MineAgentEngine.renameCompanion(target.getUUID(), newName);
        if (success) {
            // Consume the name tag
            if (!player.getAbilities().instabuild) {
                itemStack.shrink(1);
                // The mixin cancels vanilla NameTagItem handling, so vanilla
                // never marks the inventory/menu dirty for this manual shrink.
                com.mineagent.engine.task.TaskContext.syncInventory(serverPlayer);
            }
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[MineAgent] Companion renamed to '" + newName + "'!"));
            cir.setReturnValue(InteractionResult.SUCCESS);
        } else {
            // Duplicate/invalid display names must not fall through to vanilla,
            // which changes only the entity name and desynchronizes memory/disk keys.
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[MineAgent] That companion name is invalid or already in use."));
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
