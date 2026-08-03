package com.mineagent.engine.entity;

import com.mineagent.api.entity.InputDriver;
import com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Concrete InputDriver — sets the vanilla movement fields on the
 * companion's ServerPlayer to simulate human input.
 *
 * <p>Uses the access widener (mineagent.accesswidener) to access
 * Player.xxa and Player.zza fields, ensuring cross-mapping compatibility.
 */
public class CompanionInputDriver implements InputDriver {

    private final ServerPlayer player;
    private BlockPos activeBreakTarget;

    public CompanionInputDriver(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public void setForward(float value) {
        player.zza = value;
    }

    @Override
    public void setStrafe(float value) {
        player.xxa = value;
    }

    @Override
    public void setJumping(boolean jumping) {
        player.setJumping(jumping);
    }

    @Override
    public void setSneaking(boolean sneaking) {
        player.setShiftKeyDown(sneaking);
    }

    @Override
    public void setSprinting(boolean sprinting) {
        player.setSprinting(sprinting);
    }

    @Override
    public void leftClick() {
        // Entity.pick() is a block clip and cannot reliably produce an
        // EntityHitResult. ProjectileUtil performs the vanilla combined
        // block/entity view-vector query used for aimed interactions.
        HitResult hit = pickTarget();
        if (hit.getType() == HitResult.Type.ENTITY) {
            // Attack entity
            var entityHit = (EntityHitResult) hit;
            player.attack(entityHit.getEntity());
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            // Break block — single-click mode: send START then immediately
            // STOP. Why both packets:
            //
            //  - FakePlayerGameMode with instantBreak=true (default): START
            //    already destroys the block instantly via destroyBlock();
            //    the subsequent STOP targets now-air and is a harmless
            //    no-op, so this is safe.
            //  - FakePlayerGameMode with instantBreak=false, or a vanilla
            //    ServerPlayerGameMode: sending START alone leaves the
            //    destroy state machine stuck in "destroyingBlock=true"
            //    with no per-tick progress and no termination — the block
            //    is never broken AND the player can't begin breaking
            //    another block (the state machine is seized). STOP_DESTROY_BLOCK
            //    lets the server finalize the break for low-hardness blocks
            //    (dirt, sand, grass — single-tick breaks) and, for harder
            //    blocks, at least cleanly resets the destroy state so the
            //    companion isn't wedged. This is the "single click = one
            //    break attempt" contract for this driver.
            //
            // We intentionally do NOT branch on instanceof FakePlayerGameMode
            // to keep this driver decoupled from the FakePlayerGameMode
            // type; the START+STOP pair is correct for both modes.
            var blockHit = (BlockHitResult) hit;
            var pos = blockHit.getBlockPos();
            var dir = blockHit.getDirection();
            // Keep the vanilla destroy state active across ticks. Sending STOP
            // immediately after START finalizes before survival mining has
            // accumulated enough progress, so held input could never mine a
            // hard block. A target change aborts the old state; clear()
            // releases the active mining input when body ownership changes.
            if (!pos.equals(activeBreakTarget)) {
                abortActiveBreak();
                player.gameMode.handleBlockBreakAction(pos,
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        dir, player.serverLevel().getMaxBuildHeight(), 0);
                activeBreakTarget = player.level().getBlockState(pos).isAir()
                        ? null : pos.immutable();
            } else if (player.level().getBlockState(pos).isAir()) {
                activeBreakTarget = null;
            }
        }
        // CRITICAL: trigger arm swing animation so other players see the
        // companion actually swinging its arm — without this, mining and
        // attacking look like the companion is just standing still while
        // blocks magically disappear.
        player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public void rightClick() {
        // Pick what the player is looking at (5.0 block reach, no fluids).
        // This mirrors the vanilla client right-click pipeline: the client
        // always performs a pick before dispatching useItemOn / interactOn /
        // useItem, so the server receives a fully-formed HitResult.
        HitResult hit = pickTarget();
        var mainHandItem = player.getMainHandItem();
        var level = player.level();
        InteractionResult result;

        // Dispatch based on hit type — each branch corresponds to a
        // distinct vanilla interaction path. The previous implementation
        // ONLY called useItem(), which skips useItemOn (block placement,
        // door/chest opening, lever activation) and interactOn (entity
        // trading/riding/naming), making most right-click actions no-ops.
        if (hit.getType() == HitResult.Type.ENTITY) {
            // Entity interaction (villager trading, riding horses, naming
            // with name tag, etc.). We use Player.interactOn() — this is
            // the vanilla entry point and internally dispatches to the
            // appropriate game-mode/entity interactions.
            //
            // NOTE: the task spec suggested gameMode.interactAt(), but
            // ServerPlayerGameMode in 1.21.1 (Mojang mappings) has no
            // public interactAt() method — only useItem/useItemOn/
            // handleBlockBreakAction. Player.interactOn(Entity, Hand) is
            // the correct server-side entry point and is already used by
            // com.mineagent.engine.act.Interaction.interactEntity().
            var entityHit = (EntityHitResult) hit;
            result = player.interactOn(entityHit.getEntity(), InteractionHand.MAIN_HAND);
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            // Block interaction (place block, open door, activate lever,
            // fill bucket, till dirt, etc.). This is the 5-arg vanilla
            // ServerPlayerGameMode.useItemOn signature. If the item doesn't
            // interact with the block, vanilla internally falls back to
            // useItem(), so we don't need a manual fallback here.
            var blockHit = (BlockHitResult) hit;
            result = player.gameMode.useItemOn(player, level, mainHandItem,
                    InteractionHand.MAIN_HAND, blockHit);
            if (!result.consumesAction()) {
                // Vanilla's client interaction pipeline follows a PASS from
                // block interaction with the item's air-use action. Buckets,
                // food and several modded items implement Item#use rather
                // than Item#useOn; omitting this fallback made a downward
                // water-bucket click (including MLG) a permanent no-op.
                result = player.gameMode.useItem(player, level, player.getMainHandItem(),
                        InteractionHand.MAIN_HAND);
            }
        } else {
            // Miss (looking at sky) or entity-miss: just use the item in
            // air — throws ender pearls, eats food, drinks potions,
            // throws splash bottles, fires bows, etc.
            result = player.gameMode.useItem(player, level, mainHandItem,
                    InteractionHand.MAIN_HAND);
        }

        // Mirror the client rule instead of animating every attempted click.
        // Failed clicks, eating and charged item use deliberately do not use a
        // placement swing; successful doors/buttons/block placement do.
        if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
    }

    private HitResult pickTarget() {
        // blockInteractionRange() is attribute based and does not know about
        // FakePlayerGameMode's optional creative-like reach. Use the custom
        // game mode's configured value so creativeReach=false is observable.
        double reach = player.gameMode instanceof FakePlayerGameMode fakeMode
                ? fakeMode.getReachDistance()
                : player.blockInteractionRange();
        return ProjectileUtil.getHitResultOnViewVector(
                player,
                entity -> !entity.isSpectator() && entity.isPickable(),
                reach);
    }

    @Override
    public void clear() {
        // Fake players have no client release packet. Explicitly aborting here
        // prevents a cancelled task from continuing to mine a stale target.
        abortActiveBreak();
        player.xxa = 0;
        player.zza = 0;
        player.setJumping(false);
        player.setShiftKeyDown(false);
        player.setSprinting(false);
    }

    private void abortActiveBreak() {
        if (activeBreakTarget == null) return;
        player.gameMode.handleBlockBreakAction(activeBreakTarget,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                net.minecraft.core.Direction.UP,
                player.serverLevel().getMaxBuildHeight(), 0);
        activeBreakTarget = null;
    }
}
