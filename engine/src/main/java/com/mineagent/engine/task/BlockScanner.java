package com.mineagent.engine.task;

import com.mineagent.engine.util.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans for blocks of a given type within a radius around a center position.
 * Used by mining and building tasks to find target blocks.
 */
public final class BlockScanner {

    private BlockScanner() {}

    /**
     * Find all block positions matching the given block type within a radius.
     *
     * @param level     the server level
     * @param centerX   center X
     * @param centerY   center Y
     * @param centerZ   center Z
     * @param radius    search radius in blocks
     * @param blockType the block ID string (e.g. "minecraft:iron_ore")
     * @return list of matching positions, sorted by distance (nearest first)
     */
    public static List<BlockPos> findBlocks(ServerLevel level, int centerX, int centerY,
                                             int centerZ, int radius, String blockType) {
        ScanSession session = begin(level, centerX, centerY, centerZ,
                radius, blockType);
        while (!session.isComplete()) session.scan(16_384);
        return session.results();
    }

    /** Start a cursor that can safely spread a large scan across ticks. */
    public static ScanSession begin(ServerLevel level, int centerX, int centerY,
                                    int centerZ, int radius, String blockType) {
        return new ScanSession(level, centerX, centerY, centerZ, radius, blockType);
    }

    public static final class ScanSession {
        private final ServerLevel level;
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final int radius;
        private final int radiusSq;
        private final int minY;
        private final int maxY;
        private final String blockType;
        private final List<BlockPos> matches = new ArrayList<>();
        private int x;
        private int y;
        private int z;
        private boolean complete;

        private ScanSession(ServerLevel level, int centerX, int centerY,
                            int centerZ, int radius, String blockType) {
            this.level = java.util.Objects.requireNonNull(level, "level");
            this.blockType = java.util.Objects.requireNonNull(blockType, "blockType");
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radius = Math.max(0, radius);
            this.radiusSq = this.radius * this.radius;
            this.minY = Math.max(level.getMinBuildHeight(), centerY - this.radius);
            this.maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + this.radius);
            this.x = centerX - this.radius;
            this.z = centerZ - this.radius;
            this.y = minY;
        }

        /** Inspect at most {@code budget} coordinates without loading chunks. */
        public void scan(int budget) {
            if (complete || budget <= 0) return;
            while (budget-- > 0 && !complete) {
                int dx = x - centerX;
                int dy = y - centerY;
                int dz = z - centerZ;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isLoaded(pos)) {
                        BlockState state = level.getBlockState(pos);
                        if (McCompat.isBlock(state, blockType)) matches.add(pos);
                    }
                }
                advance();
            }
        }

        private void advance() {
            if (++y <= maxY) return;
            y = minY;
            if (++z <= centerZ + radius) return;
            z = centerZ - radius;
            if (++x <= centerX + radius) return;
            complete = true;
            matches.sort((a, b) -> Double.compare(
                    distSq(a, centerX, centerY, centerZ),
                    distSq(b, centerX, centerY, centerZ)));
        }

        public boolean isComplete() { return complete; }

        public List<BlockPos> results() {
            if (!complete) throw new IllegalStateException("scan is still running");
            return List.copyOf(matches);
        }
    }

    private static double distSq(BlockPos pos, int cx, int cy, int cz) {
        double dx = pos.getX() - cx;
        double dy = pos.getY() - cy;
        double dz = pos.getZ() - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
