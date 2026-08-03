package com.mineagent.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

/**
 * Renders the AI companion's vision in the world:
 *
 * <p><b>1. Line of Sight (LOS) Ray</b> — A bright cyan ray from the companion's
 * eyes to whatever block/entity they're looking at. Shows exactly where the
 * AI's attention is focused.
 *
 * <p><b>2. Target Block Highlight</b> — A colored bounding box around the block
 * the AI is currently looking at or interacting with. Color changes by context:
 * <ul>
 *   <li>Yellow — looking at a block (can interact)</li>
 *   <li>Orange — actively mining this block</li>
 *   <li>Green — a resource (ore, wood, food source)</li>
 *   <li>Red — a danger (lava, hostile mob, cliff edge)</li>
 * </ul>
 *
 * <p><b>3. Vision Cone</b> — A semi-transparent cone emanating from the
 * companion showing their field of view (~90° horizontal, ~60° vertical).
 * Helps the human player understand what the AI can and cannot see.
 *
 * <p><b>4. Target Entity Highlight</b> — A colored outline around the entity
 * the AI is targeting (enemy to attack, animal to hunt, player to follow).
 *
 * <p>Toggle with the {@code V} key.
 */
public final class CompanionVisionRenderer {

    private static boolean enabled = false;

    /** Maximum raycast distance for LOS (blocks). */
    private static final double LOS_DISTANCE = 16.0;

    /** Vision cone half-angle (degrees). 45° → 90° total FOV. */
    private static final float CONE_HALF_ANGLE = 45.0f;

    /** Number of segments to draw the vision cone outline. */
    private static final int CONE_SEGMENTS = 24;

    /** Cone radius at maximum distance. */
    private static final double CONE_RADIUS = 8.0;

    // VertexConsumer#setColor(int) uses FastColor.ARGB32 in 1.21.1.
    private static final int COLOR_LOS_RAY     = 0xFF00FFFF;  // Cyan
    private static final int COLOR_LOOK_TARGET = 0xFFFFFF00;  // Yellow
    private static final int COLOR_RESOURCE    = 0xFF00FF00;  // Green
    private static final int COLOR_DANGER      = 0xFFFF0000;  // Red
    private static final int COLOR_CONE        = 0x3000FFFF;  // Semi-transparent cyan

    private CompanionVisionRenderer() {}

    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Render the companion's vision.
     *
     * @param poseStack    the render PoseStack
     * @param bufferSource the buffer source
     * @param camera       the camera position
     * @param companion    the companion player entity (or null if not found)
     */
    public static void render(PoseStack poseStack,
                               MultiBufferSource bufferSource,
                               Vec3 camera,
                               Player companion) {
        if (!enabled || companion == null) return;

        Vec3 eyePos = companion.getEyePosition(0f);
        Vec3 lookDir = companion.getLookAngle();

        // ── 1. Line of Sight Ray ──
        renderLOSRay(poseStack, bufferSource, camera, eyePos, lookDir);

        // ── 2. Target Block Highlight ──
        renderTargetBlock(poseStack, bufferSource, camera, eyePos, lookDir, companion);

        // ── 3. Vision Cone ──
        renderVisionCone(poseStack, bufferSource, camera, eyePos, lookDir);

        // ── 4. Nearby Entity Highlights ──
        renderNearbyEntities(poseStack, bufferSource, camera, companion);
    }

    // ── LOS Ray ──────────────────────────────────────────────────

    private static void renderLOSRay(PoseStack poseStack, MultiBufferSource bufferSource,
                                      Vec3 camera, Vec3 eyePos, Vec3 lookDir) {
        Vec3 endPoint = eyePos.add(lookDir.scale(LOS_DISTANCE));
        drawLine(poseStack, bufferSource, camera, eyePos, endPoint, COLOR_LOS_RAY);
    }

    // ── Target Block ─────────────────────────────────────────────

    private static void renderTargetBlock(PoseStack poseStack, MultiBufferSource bufferSource,
                                            Vec3 camera, Vec3 eyePos, Vec3 lookDir,
                                            Player companion) {
        // Raycast to find what block the AI is looking at
        var level = companion.level();
        ClipContext ctx = new ClipContext(eyePos, eyePos.add(lookDir.scale(LOS_DISTANCE)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, companion);
        var hitResult = level.clip(ctx);

        if (hitResult == null || hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return;
        }

        BlockPos targetPos = hitResult.getBlockPos();
        var state = level.getBlockState(targetPos);

        // Determine color based on block type
        int color = COLOR_LOOK_TARGET; // default yellow

        String blockId = state.getBlock().getDescriptionId();
        // Check if resource (ore, wood)
        if (blockId.contains("ore") || blockId.contains("log") || blockId.contains("coal")) {
            color = COLOR_RESOURCE;
        }
        // Check if danger (lava, fire, magma)
        else if (blockId.contains("lava") || blockId.contains("fire") || blockId.contains("magma")) {
            color = COLOR_DANGER;
        }

        // Draw bounding box around the target block
        drawBlockBox(poseStack, bufferSource, camera, targetPos, color);
    }

    // ── Vision Cone ──────────────────────────────────────────────

    private static void renderVisionCone(PoseStack poseStack, MultiBufferSource bufferSource,
                                          Vec3 camera, Vec3 eyePos, Vec3 lookDir) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        // Draw a circle outline at CONE_RADIUS distance from eye, centered on look direction
        // Calculate the "right" and "up" vectors relative to look direction
        Vec3 forward = lookDir.normalize();
        // A vertical look vector is parallel to world-up; their cross product
        // is zero and collapses the complete cone. Choose a stable alternate
        // basis for the near-vertical case.
        Vec3 up = Math.abs(forward.y) > 0.99
                ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(up).normalize();
        Vec3 realUp = right.cross(forward).normalize();

        Vec3 coneCenter = eyePos.add(forward.scale(CONE_RADIUS));

        // Draw the circle
        double prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i <= CONE_SEGMENTS; i++) {
            float angle = (float) (2 * Math.PI * i / CONE_SEGMENTS);
            double r = CONE_RADIUS * Math.tan(Math.toRadians(CONE_HALF_ANGLE));
            Vec3 point = coneCenter
                    .add(right.scale(r * Math.cos(angle)))
                    .add(realUp.scale(r * Math.sin(angle)));

            double px = point.x - camera.x;
            double py = point.y - camera.y;
            double pz = point.z - camera.z;

            if (i > 0) {
                // Draw line from prev to current
                consumer.addVertex(pose.pose(), (float) prevX, (float) prevY, (float) prevZ)
                        .setColor(COLOR_CONE).setNormal(0, 1, 0);
                consumer.addVertex(pose.pose(), (float) px, (float) py, (float) pz)
                        .setColor(COLOR_CONE).setNormal(0, 1, 0);
            }
            prevX = px;
            prevY = py;
            prevZ = pz;
        }

        // Draw 4 rays from eye to the cone edge (N, S, E, W of the circle)
        for (int i = 0; i < 4; i++) {
            float angle = (float) (2 * Math.PI * i / 4);
            double r = CONE_RADIUS * Math.tan(Math.toRadians(CONE_HALF_ANGLE));
            Vec3 edge = coneCenter
                    .add(right.scale(r * Math.cos(angle)))
                    .add(realUp.scale(r * Math.sin(angle)));

            double ex = edge.x - camera.x;
            double ey = edge.y - camera.y;
            double ez = edge.z - camera.z;

            double sx = eyePos.x - camera.x;
            double sy = eyePos.y - camera.y;
            double sz = eyePos.z - camera.z;

            consumer.addVertex(pose.pose(), (float) sx, (float) sy, (float) sz)
                    .setColor(COLOR_CONE).setNormal(0, 1, 0);
            consumer.addVertex(pose.pose(), (float) ex, (float) ey, (float) ez)
                    .setColor(COLOR_CONE).setNormal(0, 1, 0);
        }
    }

    // ── Nearby Entities ───────────────────────────────────────────

    private static void renderNearbyEntities(PoseStack poseStack, MultiBufferSource bufferSource,
                                              Vec3 camera, Player companion) {
        var level = companion.level();
        var box = companion.getBoundingBox().inflate(16.0);

        for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
            if (entity == companion) continue;
            if (entity instanceof Player && entity.getUUID().equals(companion.getUUID())) continue;

            // Skip non-living entities (items, xp orbs)
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity)) continue;

            // Determine entity category
            int color;
            String name = entity.getType().getDescriptionId();

            // MobCategory.MONSTER covers zombies, skeletons, creepers, etc.
            // (isFriendly() was inverted before — hostile mobs were skipped
            //  and passive animals were painted red. Compare against the
            //  MONSTER enum directly to avoid relying on isFriendly().)
            var category = entity.getType().getCategory();
            if (category == net.minecraft.world.entity.MobCategory.MONSTER) {
                // Hostile mob
                color = COLOR_DANGER;
            } else if (name.contains("cow") || name.contains("pig") || name.contains("sheep")
                    || name.contains("chicken") || name.contains("rabbit")) {
                // Passive animal (food source)
                color = COLOR_RESOURCE;
            } else {
                // Other (NPCs, etc.)
                continue;
            }

            // Draw bounding box around the entity
            AABB entityBox = entity.getBoundingBox();
            drawAABB(poseStack, bufferSource, camera, entityBox, color);
        }
    }

    // ── Drawing helpers ───────────────────────────────────────────

    private static void drawLine(PoseStack poseStack, MultiBufferSource bufferSource,
                                  Vec3 camera, Vec3 from, Vec3 to, int color) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = len > 0 ? (float) (dx / len) : 0;
        float ny = len > 0 ? (float) (dy / len) : 1;
        float nz = len > 0 ? (float) (dz / len) : 0;

        consumer.addVertex(pose.pose(),
                (float) (from.x - camera.x), (float) (from.y - camera.y), (float) (from.z - camera.z))
                .setColor(color).setNormal(nx, ny, nz);
        consumer.addVertex(pose.pose(),
                (float) (to.x - camera.x), (float) (to.y - camera.y), (float) (to.z - camera.z))
                .setColor(color).setNormal(nx, ny, nz);
    }

    private static void drawBlockBox(PoseStack poseStack, MultiBufferSource bufferSource,
                                      Vec3 camera, BlockPos pos, int color) {
        AABB box = new AABB(pos);
        drawAABB(poseStack, bufferSource, camera, box, color);
    }

    private static void drawAABB(PoseStack poseStack, MultiBufferSource bufferSource,
                                 Vec3 camera, AABB box, int color) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        double minX = box.minX - camera.x;
        double minY = box.minY - camera.y;
        double minZ = box.minZ - camera.z;
        double maxX = box.maxX - camera.x;
        double maxY = box.maxY - camera.y;
        double maxZ = box.maxZ - camera.z;

        // Bottom 4 edges
        line(consumer, pose, minX, minY, minZ, maxX, minY, minZ, color);
        line(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, color);
        line(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, color);
        line(consumer, pose, minX, minY, maxZ, minX, minY, minZ, color);

        // Top 4 edges
        line(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, color);
        line(consumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        line(consumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color);
        line(consumer, pose, minX, maxY, maxZ, minX, maxY, minZ, color);

        // Vertical 4 edges
        line(consumer, pose, minX, minY, minZ, minX, maxY, minZ, color);
        line(consumer, pose, maxX, minY, minZ, maxX, maxY, minZ, color);
        line(consumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color);
        line(consumer, pose, minX, minY, maxZ, minX, maxY, maxZ, color);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose,
                              double x1, double y1, double z1,
                              double x2, double y2, double z2, int color) {
        consumer.addVertex(pose.pose(), (float) x1, (float) y1, (float) z1)
                .setColor(color).setNormal(0, 1, 0);
        consumer.addVertex(pose.pose(), (float) x2, (float) y2, (float) z2)
                .setColor(color).setNormal(0, 1, 0);
    }
}
