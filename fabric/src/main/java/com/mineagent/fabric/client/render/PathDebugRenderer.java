package com.mineagent.fabric.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders A* pathfinding paths as colored lines in the world.
 *
 * <p>Path colors:
 * <ul>
 *   <li><b>Green</b> — upcoming path (waypoints not yet reached)</li>
 *   <li><b>Red</b> — failed path (pathfinding could not reach target)</li>
 *   <li><b>Blue</b> — completed path segments (waypoints already traversed)</li>
 * </ul>
 *
 * <p>Toggle rendering with the {@code P} key binding.
 *
 * <p>Rendering uses Minecraft's {@link RenderType#lines()} via the
 * LevelRenderer world render event (Fabric's {@code WorldRenderEvents}).
 */
public final class PathDebugRenderer {

    /** Whether path debug rendering is enabled. */
    private static boolean enabled = false;

    /** Y offset above block center for the line (so it sits on top of blocks). */
    private static final double Y_OFFSET = 0.02;

    // VertexConsumer#setColor(int) decodes ARGB through FastColor.ARGB32.
    private static final int COLOR_GREEN  = 0xFF00FF00;
    private static final int COLOR_RED    = 0xFFFF0000;
    private static final int COLOR_BLUE   = 0xFF0000FF;

    // --- Current path data ---

    /** Independent renderer state for every announced companion. */
    private static final Map<UUID, PathState> PATHS = new LinkedHashMap<>();

    private record PathState(List<BlockPos> upcoming, List<BlockPos> completed,
                             List<BlockPos> failed, long failedTime) {}

    /** How long (in ticks) to display failed paths before auto-clearing. */
    private static final long FAILED_PATH_DURATION = 200; // ~10 seconds

    private PathDebugRenderer() {
        // utility class — no instances
    }

    /**
     * Toggle path debug rendering on/off.
     *
     * @return the new enabled state
     */
    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    /**
     * Check if path debug rendering is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Set a failed path to render in red.
     * Failed paths auto-expire after {@link #FAILED_PATH_DURATION} ticks.
     *
     * @param path the list of block positions in the failed path
     * @param gameTime the current game time
     */
    public static void setFailedPath(UUID companionId, List<BlockPos> path, long gameTime) {
        if (companionId == null) return;
        // A failed terminal update replaces the active path for this companion
        // only; retaining green segments rendered contradictory outcomes.
        PATHS.put(companionId, new PathState(Collections.emptyList(),
                Collections.emptyList(), List.copyOf(path), gameTime));
    }

    /** Clear one companion without erasing sibling paths. */
    public static void clearCompanion(UUID companionId) {
        if (companionId != null) PATHS.remove(companionId);
    }

    /**
     * Clear all path data.
     */
    public static void clearAll() {
        PATHS.clear();
    }

    /**
     * Render all path lines in the world.
     * Called from the Fabric {@code WorldRenderEvents.LAST} callback.
     *
     * @param poseStack       the PoseStack from the render event
     * @param bufferSource    the MultiBufferSource for vertex consumers
     * @param camera          the camera position for relative rendering
     * @param currentGameTime the current game time (for failed path expiry)
     */
    public static void render(PoseStack poseStack,
                              MultiBufferSource bufferSource,
                              Vec3 camera,
                              long currentGameTime) {
        if (!enabled) return;

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Iterator<Map.Entry<UUID, PathState>> iterator = PATHS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PathState> entry = iterator.next();
            PathState state = entry.getValue();
            renderPathLine(poseStack, consumer, camera, state.completed(), COLOR_BLUE);
            renderPathLine(poseStack, consumer, camera, state.upcoming(), COLOR_GREEN);

            if (!state.failed().isEmpty()) {
                long elapsed = currentGameTime - state.failedTime();
                if (elapsed > FAILED_PATH_DURATION) {
                    // Failed states contain no active segments, so expiration
                    // can remove the complete per-companion entry.
                    iterator.remove();
                    continue;
                }
                float alpha = 1.0f - (float) elapsed / FAILED_PATH_DURATION;
                int fadedRed = applyAlpha(COLOR_RED,
                        Math.max(0.0f, Math.min(1.0f, alpha)));
                renderPathLine(poseStack, consumer, camera, state.failed(), fadedRed);
            }
        }
    }

    /**
     * Render a path as connected line segments between waypoints.
     *
     * @param poseStack the PoseStack
     * @param consumer  the VertexConsumer for line rendering
     * @param camera    the camera position (paths are rendered relative to camera)
     * @param path      the list of block positions
     * @param color     the line color in ABGR format
     */
    private static void renderPathLine(PoseStack poseStack,
                                       VertexConsumer consumer,
                                       Vec3 camera,
                                       List<BlockPos> path,
                                       int color) {
        if (path.size() < 2) return;

        PoseStack.Pose pose = poseStack.last();

        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos from = path.get(i);
            BlockPos to = path.get(i + 1);

            // Convert block positions to camera-relative world coordinates
            // Center of the block + small Y offset to sit above surface
            double x1 = from.getX() + 0.5 - camera.x;
            double y1 = from.getY() + Y_OFFSET - camera.y;
            double z1 = from.getZ() + 0.5 - camera.z;

            double x2 = to.getX() + 0.5 - camera.x;
            double y2 = to.getY() + Y_OFFSET - camera.y;
            double z2 = to.getZ() + 0.5 - camera.z;

            // Normal vector (unit direction of the line segment)
            double dx = x2 - x1;
            double dy = y2 - y1;
            double dz = z2 - z1;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float nx = len > 0 ? (float) (dx / len) : 0.0f;
            float ny = len > 0 ? (float) (dy / len) : 1.0f;
            float nz = len > 0 ? (float) (dz / len) : 0.0f;

            // Emit two vertices for a line segment
            consumer.addVertex(pose.pose(), (float) x1, (float) y1, (float) z1)
                    .setColor(color)
                    .setNormal(nx, ny, nz);

            consumer.addVertex(pose.pose(), (float) x2, (float) y2, (float) z2)
                    .setColor(color)
                    .setNormal(nx, ny, nz);
        }
    }

    /**
     * Apply an alpha multiplier to an ABGR color.
     *
     * @param argb  the color in ARGB format
     * @param alpha the alpha multiplier (0.0 - 1.0)
     * @return the color with adjusted alpha
     */
    private static int applyAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * alpha);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /**
     * Convenience: set both upcoming and completed paths from a full path
     * and a current index.
     *
     * @param fullPath     the entire computed path
     * @param currentIndex the index of the companion's current position in the path
     */
    public static void setPathWithProgress(UUID companionId, List<BlockPos> fullPath,
                                           int currentIndex) {
        if (companionId == null) return;
        List<BlockPos> completed;
        List<BlockPos> upcoming;
        if (currentIndex <= 0) {
            completed = Collections.emptyList();
            upcoming = List.copyOf(fullPath);
        } else if (currentIndex >= fullPath.size()) {
            completed = List.copyOf(fullPath);
            upcoming = Collections.emptyList();
        } else {
            // Keep the current waypoint in both lists. Without this overlap,
            // the segment just traversed and the next segment both vanished
            // at every progress update because each list had a disconnected
            // endpoint.
            completed = List.copyOf(fullPath.subList(0, currentIndex + 1));
            upcoming = List.copyOf(fullPath.subList(currentIndex, fullPath.size()));
        }
        PATHS.put(companionId, new PathState(upcoming, completed,
                Collections.emptyList(), 0L));
    }
}
