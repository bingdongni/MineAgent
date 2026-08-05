package com.mineagent.engine.act;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a block interaction to a surface point that is actually visible
 * from the player's eyes.
 *
 * <p>Aiming only at {@link Vec3#atCenterOf(BlockPos)} is not equivalent to a
 * real client ray. In a two-block-high tunnel, for example, the ray from eye
 * height to the centre of a foot-level ore enters the block above it first.
 * The ore is still breakable by aiming at the lower part of its exposed face,
 * but a centre-only test incorrectly reports it as occluded. Sampling the
 * real outline faces fixes mining, use and placement through one shared rule.
 */
public final class BlockTargeting {
    private static final double FACE_INSET = 1.0 / 1024.0;
    private static final double[] FACE_SAMPLES = {0.15, 0.5, 0.85};

    private BlockTargeting() {}

    /** Find any visible outline face within the supplied interaction reach. */
    public static Optional<BlockHitResult> findVisibleHit(ServerPlayer player,
                                                           BlockPos pos,
                                                           double reach) {
        return findVisibleHit(player, pos, null, reach);
    }

    /**
     * Find a visible point on one required face. Placement uses this overload
     * because clicking a different support face would place in a different
     * world cell.
     */
    public static Optional<BlockHitResult> findVisibleFace(ServerPlayer player,
                                                            BlockPos pos,
                                                            Direction face,
                                                            double reach) {
        if (face == null) return Optional.empty();
        return findVisibleHit(player, pos, face, reach);
    }

    private static Optional<BlockHitResult> findVisibleHit(ServerPlayer player,
                                                            BlockPos pos,
                                                            Direction requiredFace,
                                                            double reach) {
        if (player == null || pos == null || !Double.isFinite(reach) || reach <= 0.0) {
            return Optional.empty();
        }
        var level = player.level();
        var state = level.getBlockState(pos);
        if (state.isAir()) return Optional.empty();

        List<AABB> boxes = state.getShape(level, pos, CollisionContext.of(player)).toAabbs();
        if (boxes.isEmpty()) {
            // Some interactable blocks expose no ordinary outline shape. A
            // unit cube fallback still enforces occlusion and reach instead of
            // silently allowing interaction through a wall.
            boxes = List.of(new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        }

        Vec3 eye = player.getEyePosition();
        double reachSq = reach * reach;
        List<Candidate> candidates = new ArrayList<>(boxes.size() * 54);
        for (AABB box : boxes) {
            if (requiredFace != null) {
                addFaceCandidates(candidates, pos, box, requiredFace, eye);
            } else {
                for (Direction face : Direction.values()) {
                    addFaceCandidates(candidates, pos, box, face, eye);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));

        for (Candidate candidate : candidates) {
            if (candidate.distanceSquared() > reachSq) continue;
            BlockHitResult sight = level.clip(new net.minecraft.world.level.ClipContext(
                    eye, candidate.rayEnd(),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (sight.getType() != HitResult.Type.BLOCK
                    || !sight.getBlockPos().equals(pos)) continue;
            if (requiredFace != null && sight.getDirection() != requiredFace) continue;
            return Optional.of(new BlockHitResult(
                    candidate.clickPoint(), sight.getDirection(), pos,
                    sight.isInside()));
        }
        return Optional.empty();
    }

    private static void addFaceCandidates(List<Candidate> out, BlockPos pos,
                                          AABB box, Direction face, Vec3 eye) {
        for (double u : FACE_SAMPLES) {
            for (double v : FACE_SAMPLES) {
                Vec3 click = facePoint(pos, box, face, u, v, 0.0);
                // The click stays exactly on the face for vanilla useItemOn,
                // while the ray ends just inside the outline so floating-point
                // boundary exclusion cannot turn a valid hit into MISS.
                Vec3 rayEnd = facePoint(pos, box, face, u, v, FACE_INSET);
                out.add(new Candidate(click, rayEnd, eye.distanceToSqr(click)));
            }
        }
    }

    private static Vec3 facePoint(BlockPos pos, AABB box, Direction face,
                                  double u, double v, double inset) {
        double x;
        double y;
        double z;
        switch (face) {
            case DOWN -> {
                x = lerp(box.minX, box.maxX, u);
                y = box.minY + inset;
                z = lerp(box.minZ, box.maxZ, v);
            }
            case UP -> {
                x = lerp(box.minX, box.maxX, u);
                y = box.maxY - inset;
                z = lerp(box.minZ, box.maxZ, v);
            }
            case NORTH -> {
                x = lerp(box.minX, box.maxX, u);
                y = lerp(box.minY, box.maxY, v);
                z = box.minZ + inset;
            }
            case SOUTH -> {
                x = lerp(box.minX, box.maxX, u);
                y = lerp(box.minY, box.maxY, v);
                z = box.maxZ - inset;
            }
            case WEST -> {
                x = box.minX + inset;
                y = lerp(box.minY, box.maxY, v);
                z = lerp(box.minZ, box.maxZ, u);
            }
            case EAST -> {
                x = box.maxX - inset;
                y = lerp(box.minY, box.maxY, v);
                z = lerp(box.minZ, box.maxZ, u);
            }
            default -> throw new IllegalStateException("Unexpected face " + face);
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    private static double lerp(double min, double max, double t) {
        return min + (max - min) * t;
    }

    /** Return the first blocking block on a centre ray for diagnostics only. */
    public static BlockPos centreRayBlocker(ServerPlayer player, BlockPos target) {
        if (player == null || target == null) return null;
        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(
                player.getEyePosition(), Vec3.atCenterOf(target),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK
                ? ((BlockHitResult) hit).getBlockPos() : null;
    }

    /** Use the fake player's configured reach when present. */
    public static double interactionReach(ServerPlayer player) {
        if (player != null && player.gameMode instanceof
                com.mineagent.engine.entity.fakeplayer.FakePlayerGameMode fakeMode) {
            return fakeMode.getReachDistance();
        }
        return player != null ? player.blockInteractionRange() : 0.0;
    }

    private record Candidate(Vec3 clickPoint, Vec3 rayEnd, double distanceSquared) {}
}
