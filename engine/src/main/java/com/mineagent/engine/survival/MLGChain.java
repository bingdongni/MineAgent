package com.mineagent.engine.survival;

import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.entity.InputDriver;
import com.mineagent.api.task.BodyLog;
import com.mineagent.api.task.TaskChain;
import com.mineagent.engine.act.Interaction;
import com.mineagent.engine.entity.CompanionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * MLG water bucket save — the highest-priority instinct chain.
 * Detects when the companion is falling more than 3 blocks with ground below,
 * and places a water bucket at their feet to break the fall, then picks it up.
 *
 * <p>Priority: 10 (highest — supersedes everything)
 */
public final class MLGChain implements TaskChain {

    private static final float PRIORITY = 10.0f;
    private static final double FALL_SPEED_THRESHOLD = -0.5; // Y velocity threshold (falling)
    private static final double MIN_FALL_DISTANCE = 3.0;     // blocks of air below before MLG

    private final CompanionBodyLog bodyLog;
    private final AgentPlayer companion;

    private enum Phase { IDLE, PLACING_WATER, WAITING_FOR_LAND, PICKING_UP }
    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int waterSlot = -1;
    private BlockPos waterSourcePos;

    public MLGChain(AgentPlayer companion) {
        this.companion = companion;
        this.bodyLog = SurvivalBuiltin.bodyLog(companion);
    }

    @Override
    public String name() {
        return "mlg";
    }

    @Override
    public float getPriority(AgentPlayer companion) {
        try {
            if (phase != Phase.IDLE) {
                return PRIORITY;
            }

            // Only activate if we're falling
            ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
            Vec3 delta = sp.getDeltaMovement();
            if (delta.y >= FALL_SPEED_THRESHOLD) {
                return Float.NEGATIVE_INFINITY;
            }

            // Must not already be in water
            if (companion.isInWater()) {
                return Float.NEGATIVE_INFINITY;
            }

            // Water evaporates immediately in ultra-warm dimensions. Using a
            // bucket there consumes it without creating a landing cushion.
            if (sp.level().dimensionType().ultraWarm()) {
                return Float.NEGATIVE_INFINITY;
            }

            // Must be on solid ground below (i.e., ground exists within a dangerous distance)
            double groundDist = distanceToGround(sp);
            if (Double.isFinite(groundDist) && groundDist > MIN_FALL_DISTANCE) {
                // We're falling and ground is far enough — check for water bucket
                waterSlot = findWaterBucket(sp);
                if (waterSlot >= 0) {
                    return PRIORITY;
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] MLG getPriority error: " + e.getMessage());
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    public void tick(AgentPlayer companion) {
        try {
            InputDriver input = inputDriver(companion);

            switch (phase) {
                case IDLE -> {
                    // A usable bucket may be in slots 9-35. Move it with a
                    // real swap into the selected hotbar slot before use.
                    ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
                    waterSlot = prepareWaterBucket(sp, waterSlot);
                    if (waterSlot < 0) {
                        reset();
                        break;
                    }
                    phase = Phase.PLACING_WATER;
                    phaseTicks = 0;
                    // Falling can cover more than half a block per tick. Do
                    // the first reach/placement attempt immediately instead
                    // of losing one full server tick to a state transition.
                    tick(companion);
                    return;
                }
                case PLACING_WATER -> {
                    ServerPlayer sp2 = ((CompanionEntity) companion).serverPlayer();
                    double groundDistance = distanceToGround(sp2);
                    sp2.setXRot(90.0f);
                    phaseTicks++;
                    if (!Double.isFinite(groundDistance)) {
                        bodyLog.report("no ground below - cannot MLG");
                        reset();
                        break;
                    }
                    // A bucket use outside vanilla reach simply misses. Wait
                    // until the falling player is close enough to the ground.
                    if (groundDistance > 4.5) {
                        if (phaseTicks > 40) reset();
                        break;
                    }
                    // Hold the water bucket and right-click to place.
                    // Re-validate waterSlot — another chain may have
                    // switched the held slot between getPriority and tick.
                    // If the bucket is gone (consumed by another action, or
                    // we forgot the slot), abort MLG gracefully instead of
                    // right-clicking with a non-water item (which would place
                    // a block, eat food, shoot a bow, etc. — all bad mid-fall).
                    if (waterSlot < 0) {
                        bodyLog.report("lost water bucket slot — aborting MLG");
                        reset();
                        break;
                    }
                    // Re-verify the slot still has a water bucket
                    var heldItem = sp2.getInventory().getItem(waterSlot);
                    if (!heldItem.is(Items.WATER_BUCKET)) {
                        // Slot changed — re-find
                        waterSlot = prepareWaterBucket(sp2, -1);
                        if (waterSlot < 0) {
                            bodyLog.report("no water bucket available — aborting MLG");
                            reset();
                            break;
                        }
                    }
                    companion.holdInHand(waterSlot);
                    input.rightClick();
                    var afterUse = sp2.getInventory().getItem(waterSlot);
                    BlockPos placedSource = findNearestWaterSource(sp2, 6);
                    if ((afterUse.is(Items.BUCKET) || companion.isInWater())
                            && placedSource != null) {
                        // Remember the exact source created by this action.
                        // Looking straight down after horizontal drift can hit
                        // a neighboring block and leave the bucket unrecovered.
                        waterSourcePos = placedSource.immutable();
                        bodyLog.report("placed water bucket to break my fall");
                        phase = Phase.WAITING_FOR_LAND;
                        phaseTicks = 0;
                    } else if (phaseTicks > 8) {
                        bodyLog.report("water placement failed");
                        reset();
                    }
                }
                case WAITING_FOR_LAND -> {
                    phaseTicks++;
                    // Merely entering the water is not a landing. Picking the
                    // source up while still descending removes the cushion and
                    // restores the original fall damage. Wait for real ground;
                    // on timeout leave the water in place rather than risk it.
                    ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
                    if (sp.onGround()) {
                        if (waterSlot >= 0
                                && sp.getInventory().getItem(waterSlot).is(Items.BUCKET)) {
                            phase = Phase.PICKING_UP;
                            phaseTicks = 0;
                        } else {
                            reset();
                        }
                    } else if (phaseTicks > 60) {
                        bodyLog.report("landed in deep water; leaving the source for safety");
                        reset();
                    }
                }
                case PICKING_UP -> {
                    ServerPlayer sp = ((CompanionEntity) companion).serverPlayer();
                    if (waterSlot < 0
                            || !sp.getInventory().getItem(waterSlot).is(Items.BUCKET)) {
                        reset();
                        break;
                    }
                    companion.holdInHand(waterSlot);
                    if (waterSourcePos == null
                            || !sp.level().getFluidState(waterSourcePos).isSource()) {
                        waterSourcePos = findNearestWaterSource(sp, 3);
                    }
                    if (waterSourcePos != null) {
                        // BucketItem collects fluid through Item#use, whose
                        // own ray trace includes source fluids. Aim at the
                        // recorded source and invoke that vanilla path rather
                        // than useItemOn on the opaque block beneath it.
                        lookAt(sp, Vec3.atCenterOf(waterSourcePos));
                        Interaction.useItem(sp, InteractionHand.MAIN_HAND);
                    }
                    phaseTicks++;
                    if (sp.getInventory().getItem(waterSlot).is(Items.WATER_BUCKET)) {
                        bodyLog.report("picked up the water bucket after landing");
                        reset();
                    } else if (phaseTicks > 20) {
                        bodyLog.report("could not recover water bucket");
                        reset();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MineAgent] MLG tick error: " + e.getMessage());
            reset();
        }
    }

    @Override
    public void onInterrupt(AgentPlayer companion) {
        reset();
    }

    private void reset() {
        try {
            inputDriver(companion).clear();
        } catch (Exception ignored) {
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        waterSlot = -1;
        waterSourcePos = null;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static InputDriver inputDriver(AgentPlayer companion) {
        if (companion instanceof CompanionEntity ce) {
            return ce.inputDriver();
        }
        throw new IllegalStateException("Companion is not a CompanionEntity");
    }

    /** Find a water bucket anywhere in the usable inventory (slots 0-35). */
    private static int findWaterBucket(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.WATER_BUCKET)) {
                return i;
            }
        }
        // Also check the rest of inventory — if found, we can't directly use it
        return -1;
    }

    /** Ensure the bucket occupies a hotbar slot, preserving both stacks. */
    private static int prepareWaterBucket(ServerPlayer player, int candidateSlot) {
        var inv = player.getInventory();
        int source = candidateSlot >= 0 && candidateSlot < inv.getContainerSize()
                && inv.getItem(candidateSlot).is(Items.WATER_BUCKET)
                ? candidateSlot : findWaterBucket(player);
        if (source < 0) return -1;
        if (source < 9) return source;

        int hotbar = inv.selected >= 0 && inv.selected < 9 ? inv.selected : 0;
        ItemStack bucket = inv.getItem(source);
        ItemStack displaced = inv.getItem(hotbar);
        inv.setItem(hotbar, bucket);
        inv.setItem(source, displaced);
        com.mineagent.engine.task.TaskContext.syncInventory(player);
        return hotbar;
    }

    /** Empty-bucket state alone is not proof that a recoverable source exists. */
    private static BlockPos findNearestWaterSource(ServerPlayer player, int maxDepth) {
        BlockPos origin = player.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int dy = 0; dy <= maxDepth; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = origin.offset(dx, -dy, dz);
                    var fluid = player.level().getFluidState(candidate);
                    if (fluid.is(net.minecraft.tags.FluidTags.WATER)
                            && fluid.isSource()) {
                        double distance = player.distanceToSqr(Vec3.atCenterOf(candidate));
                        if (distance < nearestDistance) {
                            nearest = candidate;
                            nearestDistance = distance;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /** Rotate the real player view so BucketItem's vanilla ray trace hits water. */
    private static void lookAt(ServerPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = delta.horizontalDistance();
        player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    /** Calculate the distance to the nearest ground block below the player. */
    private static double distanceToGround(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        var level = player.level();
        for (int y = pos.getY() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
            // Tall grass, torches and other non-air non-fluid blocks are not
            // landing ground. Using real standability prevents an early bucket
            // click against decoration while the solid floor is still distant.
            if (com.mineagent.engine.pathing.util.BlockHelper.canStandOn(state)) {
                return pos.getY() - y;
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
