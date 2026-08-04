package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.pathing.util.BlockHelper;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Emergency water-bucket landing with verified placement and recovery. */
public final class MLGChain implements TaskChain {

    private static final float PRIORITY = 10.0f;
    private static final double FALL_SPEED_THRESHOLD = -0.5;
    private static final double MIN_DANGEROUS_DROP = 3.0;
    private static final double MAX_PLACEMENT_DISTANCE = 4.25;
    private static final int MAX_RECOVERY_TICKS = 30;

    private final AgentPlayer companion;
    private final CompanionBodyLog bodyLog;

    private enum Phase { IDLE, WAITING_FOR_RANGE, WAITING_FOR_LAND, RECOVERING_WATER }
    private Phase phase = Phase.IDLE;
    private BlockPos landingBlock;
    private BlockPos waterPos;
    private int phaseTicks;
    private int previousSelected = -1;
    private int originalBucketSlot = -1;
    private boolean movedBucketToHotbar;
    private boolean waterPlaced;

    public MLGChain(AgentPlayer companion) {
        this.companion = companion;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() { return "mlg"; }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            if (phase != Phase.IDLE) return PRIORITY;
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            if (!sp.isAlive() || sp.isInWater()
                    || sp.getDeltaMovement().y >= FALL_SPEED_THRESHOLD) {
                return Float.NEGATIVE_INFINITY;
            }
            Landing landing = findLanding(sp);
            if (landing == null || landing.distance() <= MIN_DANGEROUS_DROP
                    || findWaterBucket(sp) < 0) {
                return Float.NEGATIVE_INFINITY;
            }
            return PRIORITY;
        } catch (Exception error) {
            System.err.println("[MineAgent] MLG priority error: " + error.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    public void tick(AgentPlayer companion) {
        ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
        try {
            // MLG owns the body exclusively and must not inherit movement that
            // changes the predicted landing column after the auction preempts.
            ((CompanionEntity) companion).inputDriver().clear();
            switch (phase) {
                case IDLE -> prepare(sp);
                case WAITING_FOR_RANGE -> placeWhenReachable(sp);
                case WAITING_FOR_LAND -> waitForLanding(sp);
                case RECOVERING_WATER -> recoverWater(sp);
            }
        } catch (Exception error) {
            System.err.println("[MineAgent] MLG tick error: " + error.getMessage());
            cleanup(sp);
        }
    }

    private void prepare(ServerPlayer sp) {
        Landing landing = findLanding(sp);
        int bucketSlot = findWaterBucket(sp);
        if (landing == null || bucketSlot < 0) {
            cleanup(sp);
            return;
        }

        previousSelected = sp.getInventory().selected;
        originalBucketSlot = bucketSlot;
        movedBucketToHotbar = bucketSlot >= 9;
        if (movedBucketToHotbar) {
            ItemStack displaced = sp.getInventory().getItem(previousSelected);
            sp.getInventory().setItem(previousSelected,
                    sp.getInventory().getItem(bucketSlot));
            sp.getInventory().setItem(bucketSlot, displaced);
        } else {
            sp.getInventory().selected = bucketSlot;
        }
        TaskContext.syncInventory(sp);
        landingBlock = landing.block();
        waterPos = landingBlock.above();
        phaseTicks = 0;
        phase = Phase.WAITING_FOR_RANGE;
    }

    private void placeWhenReachable(ServerPlayer sp) {
        phaseTicks++;
        if (!sp.getMainHandItem().is(Items.WATER_BUCKET)) {
            bodyLog.report("lost the water bucket before the landing save");
            cleanup(sp);
            return;
        }

        // Horizontal velocity can change the landing column. Re-evaluate it
        // until placement occurs, while retaining the exact placed position
        // afterward for deterministic pickup.
        Landing landing = findLanding(sp);
        if (landing == null) {
            cleanup(sp);
            return;
        }
        landingBlock = landing.block();
        waterPos = landingBlock.above();

        if (sp.onGround() || landing.distance() < 0.0) {
            bodyLog.report("reached the ground before water could be placed");
            cleanup(sp);
            return;
        }
        if (landing.distance() > MAX_PLACEMENT_DISTANCE) return;

        sp.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(landingBlock));
        Interaction.interactBlock(sp, landingBlock, InteractionHand.MAIN_HAND);
        TaskContext.syncInventory(sp);

        waterPlaced = sp.level().getFluidState(waterPos).is(FluidTags.WATER)
                && sp.getMainHandItem().is(Items.BUCKET);
        if (waterPlaced) {
            bodyLog.report("placed water beneath me to break the fall");
            phase = Phase.WAITING_FOR_LAND;
            phaseTicks = 0;
        } else if (phaseTicks > 20) {
            bodyLog.report("couldn't place water at the predicted landing block");
            cleanup(sp);
        }
    }

    private void waitForLanding(ServerPlayer sp) {
        phaseTicks++;
        boolean reachedWater = waterPos != null
                && sp.getBoundingBox().inflate(0.05).intersects(
                        new net.minecraft.world.phys.AABB(waterPos));
        if (sp.onGround() || sp.isInWater() || reachedWater) {
            phase = Phase.RECOVERING_WATER;
            phaseTicks = 0;
            return;
        }
        if (phaseTicks > 60) {
            bodyLog.report("water was placed but the landing position changed");
            cleanup(sp);
        }
    }

    private void recoverWater(ServerPlayer sp) {
        phaseTicks++;
        if (!waterPlaced || waterPos == null
                || !sp.level().getFluidState(waterPos).is(FluidTags.WATER)) {
            cleanup(sp);
            return;
        }
        if (!sp.getMainHandItem().is(Items.BUCKET)) {
            bodyLog.report("landed safely but no empty bucket was available for recovery");
            cleanup(sp);
            return;
        }

        sp.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(waterPos));
        Interaction.useItem(sp, InteractionHand.MAIN_HAND);
        TaskContext.syncInventory(sp);
        if (sp.getMainHandItem().is(Items.WATER_BUCKET)
                && !sp.level().getFluidState(waterPos).is(FluidTags.WATER)) {
            bodyLog.report("recovered the water bucket after landing");
            cleanup(sp);
        } else if (phaseTicks > MAX_RECOVERY_TICKS) {
            bodyLog.report("landed safely but couldn't recover the water source");
            cleanup(sp);
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        cleanup(((CompanionEntity) companion).serverPlayer());
    }

    private void cleanup(ServerPlayer sp) {
        ((CompanionEntity) companion).inputDriver().clear();
        if (previousSelected >= 0) {
            var inventory = sp.getInventory();
            if (movedBucketToHotbar && originalBucketSlot >= 9
                    && originalBucketSlot < inventory.getContainerSize()) {
                // Undo the exact swap made by prepare. The selected stack may
                // now be an empty/water bucket; both outcomes belong back in
                // the original bucket slot without creating a copy.
                ItemStack selectedStack = inventory.getItem(previousSelected);
                inventory.setItem(previousSelected, inventory.getItem(originalBucketSlot));
                inventory.setItem(originalBucketSlot, selectedStack);
            }
            inventory.selected = previousSelected;
            TaskContext.syncInventory(sp);
        }
        phase = Phase.IDLE;
        landingBlock = null;
        waterPos = null;
        phaseTicks = 0;
        previousSelected = -1;
        originalBucketSlot = -1;
        movedBucketToHotbar = false;
        waterPlaced = false;
    }

    private static int findWaterBucket(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < Math.min(9, inventory.getContainerSize()); i++) {
            if (inventory.getItem(i).is(Items.WATER_BUCKET)) return i;
        }
        for (int i = 9; i < Math.min(36, inventory.getContainerSize()); i++) {
            if (inventory.getItem(i).is(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    private static Landing findLanding(ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        int x = feet.getX();
        int z = feet.getZ();
        var level = player.level();
        for (int y = feet.getY() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos block = new BlockPos(x, y, z);
            var state = level.getBlockState(block);
            if (!state.getFluidState().isEmpty()) {
                if (state.getFluidState().is(FluidTags.WATER)) return null;
                continue;
            }
            if (BlockHelper.canStandOn(state)) {
                return new Landing(block, player.getY() - (y + 1.0));
            }
        }
        return null;
    }

    private record Landing(BlockPos block, double distance) { }
}
