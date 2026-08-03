package com.mineagent.tools.perception;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTickDispatcher;
import com.mineagent.engine.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Scan blocks around the companion by type. Returns positions of matching
 * blocks within a radius, ordered nearest-first.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class ScanBlocksTool implements Tool {

    private static final int MAX_RESULTS = 50;
    private static final int POSITIONS_PER_TICK = 4096;
    private static final Set<UUID> ACTIVE_SCANS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String name() { return "scan_blocks"; }

    @Override
    public String description() {
        return """
            Scan for blocks of a specific type around you. Returns a list of
            (x, y, z) positions of matching blocks within the specified radius,
            nearest first. Use block_type namespaced IDs like "minecraft:iron_ore"
            or block tags like "#minecraft:ores" / "#minecraft:logs".
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_type", "Block ID (e.g. 'minecraft:iron_ore') or tag (e.g. '#minecraft:ores')")
                .optionalInteger("radius", "Scan radius in blocks (1-32, default 16)", 1, 32)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String blockType = ToolArgs.getString(args, "block_type");
        if (blockType == null) {
            reply.accept("{\"error\":\"Missing required parameter 'block_type'.\"}");
            return;
        }
        Integer parsedRadius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 16;
        if (parsedRadius == null || parsedRadius < 1 || parsedRadius > 32) {
            reply.accept("{\"error\":\"radius must be an integer between 1 and 32.\"}");
            return;
        }
        int radius = parsedRadius;

        var sp = ((CompanionEntity) player).serverPlayer();
        var pos = sp.blockPosition();
        ServerLevel level = sp.serverLevel();

        // ── Resolve the matcher: real tag query or exact block ID ──
        TagKey<Block> tagKey = null;
        Block exactBlock = null;
        if (blockType.startsWith("#")) {
            ResourceLocation tagLoc = ResourceLocation.tryParse(blockType.substring(1));
            if (tagLoc == null) {
                reply.accept(ToolArgs.errorJson("Invalid tag syntax: '" + blockType + "'"));
                return;
            }
            tagKey = TagKey.create(Registries.BLOCK, tagLoc);
            // Verify the tag actually exists so a typo doesn't silently
            // return an empty scan.
            if (level.registryAccess().lookupOrThrow(Registries.BLOCK).get(tagKey).isEmpty()) {
                reply.accept(ToolArgs.errorJson("Unknown block tag: '" + blockType + "'"));
                return;
            }
        } else {
            ResourceLocation blockLoc = ResourceLocation.tryParse(blockType);
            if (blockLoc == null || !BuiltInRegistries.BLOCK.containsKey(blockLoc)) {
                reply.accept(ToolArgs.errorJson("Unknown block: '" + blockType + "'"));
                return;
            }
            // ResourceLocation accepts shorthand ("stone" ->
            // "minecraft:stone"). Compare with the same canonical form.
            blockType = blockLoc.toString();
            exactBlock = BuiltInRegistries.BLOCK.get(blockLoc);
        }

        final TagKey<Block> tag = tagKey;
        final Block exact = exactBlock;
        Predicate<BlockState> matcher = tag != null
                ? state -> state.is(tag)
                : state -> state.getBlock() == exact;

        UUID companionId = player.companionId();
        if (!ACTIVE_SCANS.add(companionId)) {
            reply.accept(ToolArgs.errorJson("A block scan is already running."));
            return;
        }
        try {
            CompanionTickDispatcher.submitWork(player,
                    new ScanWork(companionId, level, pos.immutable(), radius,
                            matcher, reply));
        } catch (RuntimeException submissionFailure) {
            ACTIVE_SCANS.remove(companionId);
            throw submissionFailure;
        }
    }

    /** Incremental nearest-result scan that never loads an absent chunk. */
    private static final class ScanWork implements CompanionTickDispatcher.TickWork {
        private static final Comparator<Match> NEAREST = Comparator
                .comparingLong(Match::distanceSq)
                .thenComparingInt(match -> match.pos().getX())
                .thenComparingInt(match -> match.pos().getY())
                .thenComparingInt(match -> match.pos().getZ());

        private final UUID companionId;
        private final ServerLevel level;
        private final BlockPos center;
        private final int radius;
        private final int minY;
        private final int maxY;
        private final Predicate<BlockState> matcher;
        private final Consumer<String> reply;
        private final PriorityQueue<Match> nearest =
                new PriorityQueue<>(MAX_RESULTS, NEAREST.reversed());
        private int dx;
        private int dy;
        private int dz;
        private boolean complete;
        private boolean terminal;

        private ScanWork(UUID companionId, ServerLevel level, BlockPos center,
                         int radius, Predicate<BlockState> matcher,
                         Consumer<String> reply) {
            this.companionId = companionId;
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
            this.matcher = matcher;
            this.reply = reply;
            this.dx = -radius;
            this.dy = -radius;
            this.dz = -radius;
        }

        @Override
        public boolean tick() {
            int budget = POSITIONS_PER_TICK;
            while (budget-- > 0 && !complete) {
                int y = center.getY() + dy;
                if (y >= minY && y < maxY) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (level.isLoaded(candidate)
                            && matcher.test(level.getBlockState(candidate))) {
                        long distanceSq = (long) dx * dx + (long) dy * dy
                                + (long) dz * dz;
                        Match match = new Match(candidate.immutable(), distanceSq);
                        if (nearest.size() < MAX_RESULTS) {
                            nearest.add(match);
                        } else if (NEAREST.compare(match, nearest.peek()) < 0) {
                            nearest.poll();
                            nearest.add(match);
                        }
                    }
                }
                advance();
            }
            if (!complete) return false;
            finishSuccess();
            return true;
        }

        private void advance() {
            if (++dz <= radius) return;
            dz = -radius;
            if (++dy <= radius) return;
            dy = -radius;
            if (++dx <= radius) return;
            complete = true;
        }

        private void finishSuccess() {
            if (terminal) return;
            terminal = true;
            ACTIVE_SCANS.remove(companionId);
            List<Match> matches = new ArrayList<>(nearest);
            matches.sort(NEAREST);
            com.google.gson.JsonArray found = new com.google.gson.JsonArray();
            for (Match match : matches) {
                JsonObject entry = new JsonObject();
                entry.addProperty("x", match.pos().getX());
                entry.addProperty("y", match.pos().getY());
                entry.addProperty("z", match.pos().getZ());
                found.add(entry);
            }
            JsonObject result = new JsonObject();
            result.add("found", found);
            result.addProperty("count", matches.size());
            reply.accept(result.toString());
        }

        @Override
        public void onFailure(Throwable failure) {
            finishError("Block scan failed: " + failure.getClass().getSimpleName());
        }

        @Override
        public void onDiscarded() {
            finishError("Companion is no longer active.");
        }

        private void finishError(String message) {
            if (terminal) return;
            terminal = true;
            ACTIVE_SCANS.remove(companionId);
            reply.accept(ToolArgs.errorJson(message));
        }

        private record Match(BlockPos pos, long distanceSq) {}
    }
}
