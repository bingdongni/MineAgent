package com.mineagent.tools.perception;

import com.google.gson.JsonArray;
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
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Performs a loaded-chunk-only, tick-sliced block search. */
public class ScanBlocksTool implements Tool {
    private static final int MAX_RESULTS = 50;
    private static final int BLOCKS_PER_TICK = 4_096;

    @Override public String name() { return "scan_blocks"; }
    @Override public String description() {
        return "Scan loaded nearby blocks by exact ID or block tag and return nearest positions.";
    }
    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_type", "Block ID or #block_tag")
                .optionalInteger("radius", "Scan radius in blocks (1-32, default 16)", 1, 32)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String requestedType = ToolArgs.getString(args, "block_type");
        if (requestedType == null || requestedType.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'block_type'."));
            return;
        }
        Integer radius = ToolArgs.has(args, "radius")
                ? ToolArgs.getIntOrNull(args, "radius") : 16;
        if (radius == null || radius < 1 || radius > 32) {
            reply.accept(ToolArgs.errorJson("'radius' must be an integer from 1 to 32."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        TagKey<Block> tag = null;
        ResourceLocation id = ResourceLocation.tryParse(
                requestedType.startsWith("#") ? requestedType.substring(1) : requestedType);
        if (id == null) {
            reply.accept(ToolArgs.errorJson("Invalid block or tag ID: " + requestedType));
            return;
        }
        if (requestedType.startsWith("#")) {
            tag = TagKey.create(Registries.BLOCK, id);
            if (sp.level().registryAccess().lookupOrThrow(Registries.BLOCK).get(tag).isEmpty()) {
                reply.accept(ToolArgs.errorJson("Unknown block tag: #" + id));
                return;
            }
        } else if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            reply.accept(ToolArgs.errorJson("Unknown block: " + id));
            return;
        }

        CompanionTickDispatcher.submitWork(player,
                new ScanWork(sp.serverLevel(), sp.blockPosition(), radius,
                        id, tag, reply));
    }

    private static final class ScanWork implements CompanionTickDispatcher.TickWork {
        private final net.minecraft.server.level.ServerLevel level;
        private final BlockPos center;
        private final int radius;
        private final ResourceLocation exactId;
        private final TagKey<Block> tag;
        private final Consumer<String> reply;
        private final JsonArray results = new JsonArray();
        private final AtomicBoolean replied = new AtomicBoolean();
        private int shell;
        private int dx;
        private int dy;
        private int dz;
        private boolean complete;

        private ScanWork(net.minecraft.server.level.ServerLevel level, BlockPos center,
                         int radius, ResourceLocation exactId, TagKey<Block> tag,
                         Consumer<String> reply) {
            this.level = level;
            this.center = center.immutable();
            this.radius = radius;
            this.exactId = exactId;
            this.tag = tag;
            this.reply = reply;
        }

        @Override
        public boolean tick() {
            int budget = BLOCKS_PER_TICK;
            while (budget-- > 0 && !complete) {
                int y = center.getY() + dy;
                if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) == shell
                        && y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.isLoaded(pos)) {
                        var state = level.getBlockState(pos);
                        boolean matches = tag != null ? state.is(tag)
                                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(exactId);
                        if (matches) {
                            JsonObject match = new JsonObject();
                            match.addProperty("x", pos.getX());
                            match.addProperty("y", pos.getY());
                            match.addProperty("z", pos.getZ());
                            results.add(match);
                            if (results.size() >= MAX_RESULTS) complete = true;
                        }
                    }
                }
                if (!complete) advance();
            }
            if (!complete) return false;
            JsonObject response = new JsonObject();
            response.add("found", results);
            response.addProperty("count", results.size());
            response.addProperty("truncated", results.size() >= MAX_RESULTS);
            respondOnce(response.toString());
            return true;
        }

        private void advance() {
            if (++dz <= shell) return;
            dz = -shell;
            if (++dy <= shell) return;
            dy = -shell;
            if (++dx <= shell) return;
            shell++;
            if (shell > radius) {
                complete = true;
                return;
            }
            dx = dy = dz = -shell;
        }

        @Override
        public void onFailure(Throwable failure) {
            respondOnce(ToolArgs.errorJson("Block scan failed: " + failure.getMessage()));
        }

        @Override
        public void onDiscarded() {
            respondOnce(ToolArgs.errorJson("Block scan cancelled because the companion was removed."));
        }

        private void respondOnce(String value) {
            if (replied.compareAndSet(false, true)) reply.accept(value);
        }
    }
}
