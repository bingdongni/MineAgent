package com.mineagent.engine.entity;

import com.mineagent.engine.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Finds a physically valid nearby destination before moving a companion. */
public final class SafeTeleport {

    private static final int[] Y_OFFSETS = {0, 1, -1, 2, -2, 3, -3};
    private static final int MAX_RADIUS = 4;

    private SafeTeleport() {}

    /** Teleport beside an entity, excluding its occupied origin cell. */
    public static boolean beside(ServerPlayer companion, ServerPlayer anchor) {
        if (companion == null || anchor == null || !anchor.isAlive()) return false;
        return near(companion, anchor.serverLevel(), anchor.blockPosition(),
                anchor.getYRot(), anchor.getXRot(), false);
    }

    /** Teleport near a block position; the origin itself may be selected. */
    public static boolean near(ServerPlayer companion, ServerLevel level,
                               BlockPos origin, float yaw, float pitch) {
        return near(companion, level, origin, yaw, pitch, true);
    }

    private static boolean near(ServerPlayer companion, ServerLevel level,
                                BlockPos origin, float yaw, float pitch,
                                boolean includeOrigin) {
        if (companion == null || level == null || origin == null) return false;

        for (int dy : Y_OFFSETS) {
            if (includeOrigin && tryTeleport(companion, level,
                    origin.offset(0, dy, 0), yaw, pitch)) {
                return true;
            }
            for (int radius = 1; radius <= MAX_RADIUS; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Visit only this ring. This produces a deterministic
                        // nearest-first search without retesting inner cells.
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        if (tryTeleport(companion, level,
                                origin.offset(dx, dy, dz), yaw, pitch)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean tryTeleport(ServerPlayer companion, ServerLevel level,
                                       BlockPos feet, float yaw, float pitch) {
        if (feet.getY() < level.getMinBuildHeight()
                || feet.getY() + 1 >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(feet)
                || level.getChunkSource().getChunkNow(
                        feet.getX() >> 4, feet.getZ() >> 4) == null) {
            return false;
        }

        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(feet.above());
        var supportState = level.getBlockState(feet.below());
        if (!BlockHelper.isPassable(feetState)
                || !BlockHelper.isPassable(headState)
                || !feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || !BlockHelper.canStandOn(supportState)) {
            return false;
        }

        double x = feet.getX() + 0.5;
        double y = feet.getY();
        double z = feet.getZ() + 0.5;
        var destinationBox = companion.getBoundingBox().move(
                x - companion.getX(), y - companion.getY(), z - companion.getZ());
        if (!level.noCollision(companion, destinationBox)) return false;

        companion.teleportTo(level, x, y, z, yaw, pitch);
        // Cross-dimension/fall recovery must not retain momentum from the old
        // location and immediately walk or fall out of the validated cell.
        companion.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        return true;
    }
}
