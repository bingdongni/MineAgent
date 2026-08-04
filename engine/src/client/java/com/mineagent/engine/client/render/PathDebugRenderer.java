package com.mineagent.engine.client.render;

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

/** Renders independent active/completed/failed paths for every companion. */
public final class PathDebugRenderer {

    private static boolean enabled;
    private static final double Y_OFFSET = 0.02;

    // VertexConsumer#setColor(int) reads ARGB, not the old ABGR constants.
    private static final int COLOR_GREEN = 0xFF00FF00;
    private static final int COLOR_RED = 0xFFFF0000;
    private static final int COLOR_BLUE = 0xFF0000FF;
    private static final long FAILED_PATH_DURATION = 200;

    private static final Map<UUID, PathState> PATHS = new LinkedHashMap<>();

    private record PathState(List<BlockPos> upcoming, List<BlockPos> completed,
                             List<BlockPos> failed, long failedTime) {}

    private PathDebugRenderer() {}

    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setFailedPath(UUID companionId, List<BlockPos> path, long gameTime) {
        if (companionId == null || path == null) return;
        PATHS.put(companionId, new PathState(Collections.emptyList(),
                Collections.emptyList(), List.copyOf(path), gameTime));
    }

    public static void clearCompanion(UUID companionId) {
        if (companionId != null) PATHS.remove(companionId);
    }

    public static void clearAll() {
        PATHS.clear();
    }

    public static void setPathWithProgress(UUID companionId, List<BlockPos> fullPath,
                                           int currentIndex) {
        if (companionId == null || fullPath == null) return;
        List<BlockPos> stable = List.copyOf(fullPath);
        int index = Math.max(0, Math.min(currentIndex, stable.size()));
        List<BlockPos> completed;
        List<BlockPos> upcoming;
        if (index <= 0) {
            completed = Collections.emptyList();
            upcoming = stable;
        } else if (index >= stable.size()) {
            completed = stable;
            upcoming = Collections.emptyList();
        } else {
            // The current waypoint belongs to both polylines so neither the
            // previous nor next segment disappears at a progress update.
            completed = List.copyOf(stable.subList(0, index + 1));
            upcoming = List.copyOf(stable.subList(index, stable.size()));
        }
        PATHS.put(companionId, new PathState(upcoming, completed,
                Collections.emptyList(), 0L));
    }

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource,
                              Vec3 camera, long currentGameTime) {
        if (!enabled || poseStack == null || bufferSource == null || camera == null) return;
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Iterator<Map.Entry<UUID, PathState>> iterator = PATHS.entrySet().iterator();
        while (iterator.hasNext()) {
            PathState state = iterator.next().getValue();
            renderPathLine(poseStack, consumer, camera, state.completed(), COLOR_BLUE);
            renderPathLine(poseStack, consumer, camera, state.upcoming(), COLOR_GREEN);
            if (!state.failed().isEmpty()) {
                long elapsed = Math.max(0L, currentGameTime - state.failedTime());
                if (elapsed > FAILED_PATH_DURATION) {
                    iterator.remove();
                    continue;
                }
                float alpha = 1.0f - (float) elapsed / FAILED_PATH_DURATION;
                renderPathLine(poseStack, consumer, camera, state.failed(),
                        applyAlpha(COLOR_RED, Math.max(0.0f, Math.min(1.0f, alpha))));
            }
        }
    }

    private static void renderPathLine(PoseStack poseStack, VertexConsumer consumer,
                                       Vec3 camera, List<BlockPos> path, int color) {
        if (path.size() < 2) return;
        PoseStack.Pose pose = poseStack.last();
        for (int index = 0; index < path.size() - 1; index++) {
            BlockPos from = path.get(index);
            BlockPos to = path.get(index + 1);
            double x1 = from.getX() + 0.5 - camera.x;
            double y1 = from.getY() + Y_OFFSET - camera.y;
            double z1 = from.getZ() + 0.5 - camera.z;
            double x2 = to.getX() + 0.5 - camera.x;
            double y2 = to.getY() + Y_OFFSET - camera.y;
            double z2 = to.getZ() + 0.5 - camera.z;

            double dx = x2 - x1;
            double dy = y2 - y1;
            double dz = z2 - z1;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float nx = length > 0.0 ? (float) (dx / length) : 0.0f;
            float ny = length > 0.0 ? (float) (dy / length) : 1.0f;
            float nz = length > 0.0 ? (float) (dz / length) : 0.0f;
            consumer.addVertex(pose.pose(), (float) x1, (float) y1, (float) z1)
                    .setColor(color).setNormal(nx, ny, nz);
            consumer.addVertex(pose.pose(), (float) x2, (float) y2, (float) z2)
                    .setColor(color).setNormal(nx, ny, nz);
        }
    }

    private static int applyAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * alpha);
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
