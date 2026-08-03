package com.mineagent.engine.entity.fakeplayer;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * ServerPlayerGameMode wrapper for fake players.
 * <p>
 * Provides creative-mode-like capabilities for fake players:
 * <ul>
 *   <li>Instant block breaking (no mining progress delay)</li>
 *   <li>Extended reach distance for block interaction</li>
 *   <li>Normal survival durability, drops and hunger semantics</li>
 * </ul>
 * <p>
 * The fake player's game mode is set to SURVIVAL by default, but
 * the overrides here give it creative-like block interaction range
 * and instant break capability.
 */
public class FakePlayerGameMode extends ServerPlayerGameMode {

    /** A normal mining arm animation lasts six ticks. */
    private static final int MINING_SWING_INTERVAL_TICKS = 6;

    /** Extended reach distance for fake players (same as creative mode). */
    private static final double CREATIVE_REACH = 5.0;

    /** Standard survival reach distance. */
    private static final double SURVIVAL_REACH = 4.5;

    /** Whether to use creative-mode reach distance. */
    private boolean creativeReach = true;

    /** Whether to use instant block breaking. */
    private boolean instantBreak = true;

    /**
     * Client-owned mining state that vanilla does not keep for a fake player.
     *
     * <p>{@link ServerPlayerGameMode#tick()} advances and broadcasts crack
     * stages after START, but deliberately never commits an ordinary survival
     * break by itself. A real client sends STOP when its local progress reaches
     * one. Fake players have no client, so retaining this state here is what
     * turns the otherwise endless crack animation into a complete vanilla
     * START/tick/STOP transaction.
     */
    private BlockPos automaticDestroyPos;
    private Direction automaticDestroyDirection = Direction.UP;
    private Block automaticDestroyBlock;
    private int automaticDestroyTicks;
    private boolean automaticStopSent;

    public FakePlayerGameMode(ServerPlayer player) {
        super(player);
    }

    /**
     * Enable or disable creative-mode reach distance.
     */
    public void setCreativeReach(boolean creativeReach) {
        this.creativeReach = creativeReach;
    }

    /**
     * Enable or disable instant block breaking.
     */
    public void setInstantBreak(boolean instantBreak) {
        this.instantBreak = instantBreak;
    }

    /**
     * Advance vanilla mining first, then emulate the small piece of client
     * behavior missing from a fake player: aim, swing, and send STOP once the
     * block has accumulated enough survival-mode progress.
     */
    @Override
    public void tick() {
        super.tick();

        if (automaticDestroyPos == null) return;

        BlockState state = level.getBlockState(automaticDestroyPos);
        if (state.isAir()) {
            clearAutomaticDestroy();
            return;
        }

        // A replacement at the same coordinates is a different target. A real
        // client receives the block update and releases the mouse; continuing
        // would let an old mining action destroy a newly placed block.
        if (state.getBlock() != automaticDestroyBlock
                || !player.isAlive()
                || !player.canInteractWithBlock(automaticDestroyPos, 1.0)) {
            abortAutomaticDestroy();
            return;
        }

        player.lookAt(EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(automaticDestroyPos));
        if (automaticDestroyTicks % MINING_SWING_INTERVAL_TICKS == 0) {
            player.swing(InteractionHand.MAIN_HAND);
        }

        automaticDestroyTicks++;
        if (automaticStopSent) return;

        float progressPerTick = state.getDestroyProgress(
                player, player.level(), automaticDestroyPos);
        if (!Float.isFinite(progressPerTick) || progressPerTick <= 0.0f) {
            // The target became unbreakable (for example because a protection
            // mod changed the state). Do not leave vanilla's private destroy
            // state holding the player forever.
            abortAutomaticDestroy();
            return;
        }

        // START already contributes the first progress sample. Vanilla STOP
        // uses the same formula: progressPerTick * (elapsedTicks + 1). Waiting
        // for 1.0 mirrors the real client's completion point; if speed changes
        // between ticks vanilla can still enter its delayed-destroy state and
        // finish safely without bypassing durability, drops or block hooks.
        if (progressPerTick * (automaticDestroyTicks + 1) >= 1.0f) {
            automaticStopSent = true;
            super.handleBlockBreakAction(automaticDestroyPos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    automaticDestroyDirection,
                    level.getMaxBuildHeight(), 0);
        }
    }

    /**
     * Get the effective reach distance for this fake player.
     * Returns creative-mode reach if enabled, otherwise survival reach.
     */
    public double getReachDistance() {
        return creativeReach ? CREATIVE_REACH : SURVIVAL_REACH;
    }

    /**
     * Handle block breaking for the fake player.
     *
     * <p>Delegates to the vanilla {@code super.destroyBlock(pos)} which
     * handles:
     * <ul>
     *   <li>Tool durability consumption (mineBlock on the held item)</li>
     *   <li>Correct drops based on tool correctness (isCorrectToolForDrops)</li>
     *   <li>Experience orb spawning</li>
     *   <li>Block break events/statistics</li>
     *   <li>Player exhaustion (hunger cost for mining)</li>
     * </ul>
     *
     * <p>The previous override called {@code level.destroyBlock(pos, true, player)}
     * directly, which bypasses tool durability and statistics — the companion
     * could mine infinite blocks with a wooden pickaxe without it ever breaking.
     */
    @Override
    public boolean destroyBlock(BlockPos pos) {
        return super.destroyBlock(pos);
    }

    /**
     * Get the game type — always reports SURVIVAL for the fake player,
     * but behavior is augmented by the overrides in this class.
     */
    @Override
    public GameType getGameModeForPlayer() {
        return GameType.SURVIVAL;
    }

    /**
     * Check if the fake player is in creative mode.
     *
     * <p><b>CRITICAL FIX</b>: This MUST return {@code false}. The fake
     * player is in SURVIVAL mode and must be able to take damage and die
     * like a normal player. Returning {@code true} here causes Minecraft's
     * {@code ServerPlayer.hurt()} / {@code LivingEntity.actuallyHurt()}
     * to skip all damage application — the companion appears to "lose HP
     * but never die" because all damage is silently cancelled.
     *
     * <p>The {@link #creativeReach} flag only controls the reach distance
     * (via {@link #getReachDistance()}), NOT whether the player is in
     * creative mode. Instant block breaking is handled separately by
     * {@link #handleBlockBreakAction} and {@link #destroyBlock}.
     *
     * @return always {@code false} — survival mode, takes damage, can die
     */
    @Override
    public boolean isCreative() {
        return false;
    }

    /**
     * Prevent the server from rejecting block breaks due to
     * "invalid" mining progress for the fake player.
     * H9 fix: only ack sequence if connection supports it
     */
    @Override
    public void handleBlockBreakAction(BlockPos pos,
                                        ServerboundPlayerActionPacket.Action action,
                                        Direction direction,
                                        int maxUpAngle, int sequence) {
        if (pos == null || action == null) return;

        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if (automaticDestroyPos != null && !automaticDestroyPos.equals(pos)) {
                abortAutomaticDestroy();
            }

            if (instantBreak) {
                // Instant mode remains an explicit configuration feature, but
                // it must not bypass the world, height, reach and adventure-mode
                // checks normally performed before ServerPlayerGameMode destroys
                // a block. task.BlockDigger also validates sight for task calls.
                if (canStartDestroy(pos, maxUpAngle)) {
                    player.lookAt(EntityAnchorArgument.Anchor.EYES,
                            Vec3.atCenterOf(pos));
                    player.swing(InteractionHand.MAIN_HAND);
                    destroyBlock(pos);
                }
                clearAutomaticDestroy();
                return;
            }

            if (!canStartDestroy(pos, maxUpAngle)) {
                clearAutomaticDestroy();
                // Let vanilla send its corrective block update and preserve any
                // protection/mod hooks attached to the standard entry point.
                super.handleBlockBreakAction(pos, action,
                        direction == null ? Direction.UP : direction,
                        maxUpAngle, sequence);
                return;
            }

            super.handleBlockBreakAction(pos, action,
                    direction == null ? Direction.UP : direction,
                    maxUpAngle, sequence);
            if (level.getBlockState(pos).isAir()) {
                clearAutomaticDestroy();
                return;
            }

            automaticDestroyPos = pos.immutable();
            automaticDestroyDirection = direction == null ? Direction.UP : direction;
            automaticDestroyBlock = level.getBlockState(pos).getBlock();
            automaticDestroyTicks = 0;
            automaticStopSent = false;
            player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }

        super.handleBlockBreakAction(pos, action,
                direction == null ? Direction.UP : direction,
                maxUpAngle, sequence);
        if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            clearAutomaticDestroy();
        }
    }

    /** Drop stale mining state before the game mode starts operating in a new dimension. */
    @Override
    public void setLevel(ServerLevel level) {
        if (automaticDestroyPos != null) {
            abortAutomaticDestroy();
        }
        super.setLevel(level);
    }

    private boolean canStartDestroy(BlockPos pos, int maxBuildHeight) {
        return pos.getY() < maxBuildHeight
                && pos.getY() >= level.getMinBuildHeight()
                && level.getWorldBorder().isWithinBounds(pos)
                && player.canInteractWithBlock(pos, 1.0)
                && level.mayInteract(player, pos)
                && !player.blockActionRestricted(
                        level, pos, getGameModeForPlayer())
                && !level.getBlockState(pos).isAir();
    }

    private void abortAutomaticDestroy() {
        BlockPos target = automaticDestroyPos;
        clearAutomaticDestroy();
        if (target != null) {
            super.handleBlockBreakAction(target,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP, level.getMaxBuildHeight(), 0);
        }
    }

    private void clearAutomaticDestroy() {
        automaticDestroyPos = null;
        automaticDestroyDirection = Direction.UP;
        automaticDestroyBlock = null;
        automaticDestroyTicks = 0;
        automaticStopSent = false;
    }
}
