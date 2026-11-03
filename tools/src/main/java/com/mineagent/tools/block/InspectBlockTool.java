package com.mineagent.tools.block;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Reads one already-loaded block without forcing chunk generation. */
public class InspectBlockTool implements Tool {

    @Override public String name() { return "inspect_block"; }

    @Override
    public String description() {
        return "Inspect a visible loaded block's public state; optionally read bounded automation-port item/fluid/energy fields within interaction reach. Private NBT is not exposed.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X coordinate", -30_000_000, 30_000_000)
                .integer("y", "Block Y coordinate", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("z", "Block Z coordinate", -30_000_000, 30_000_000)
                .optionalBoolean("capabilities", "Read the visible face's public automation-port fields within interaction reach")
                .optionalString("face", "Optional visible port face: north, south, east, west, up or down")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        Integer x = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");
        if (x == null || y == null || z == null) {
            reply.accept(ToolArgs.errorJson("Parameters x, y, and z must be exact integers."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            // getBlockState on an arbitrary coordinate can synchronously load
            // or generate a chunk. Perception must never stall the server that way.
            reply.accept(ToolArgs.errorJson("Block position is outside the loaded world."));
            return;
        }

        var state = level.getBlockState(pos);
        var hit = com.mineagent.engine.act.BlockTargeting.findVisibleHit(sp, pos, 16.0);
        if (hit.isEmpty()) {
            var eye = sp.getEyePosition();
            var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
            var ray = level.clip(new net.minecraft.world.level.ClipContext(eye, center,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, sp));
            if (!state.isAir() || eye.distanceToSqr(center) > 256.0
                    || ray.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                reply.accept(ToolArgs.errorJson("Block is outside observation range or blocked from view."));
                return;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("fact_subject", "block:" + level.dimension().location() + ":" + x + "," + y + "," + z);
        result.addProperty("dimension",level.dimension().location().toString());
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (var property : state.getProperties()) {
                properties.addProperty(property.getName(),
                        state.getValue(property).toString());
            }
            result.add("properties", properties);
        }

        var blockEntity = level.getBlockEntity(pos);
        result.addProperty("has_block_entity", blockEntity != null);
        if (blockEntity != null) {
            result.addProperty("block_entity_type", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(blockEntity.getType()).toString());
        }
        if (ToolArgs.getBool(args, "capabilities", false)) {
            String face = ToolArgs.getString(args, "face", null);
            var direction = face == null ? null : net.minecraft.core.Direction.byName(face);
            if (face != null && direction == null) { reply.accept(ToolArgs.errorJson("Invalid public-port face")); return; }
            var port = direction == null ? com.mineagent.engine.act.BlockTargeting.findVisibleHit(sp, pos,
                    com.mineagent.engine.act.BlockTargeting.interactionReach(sp))
                    : com.mineagent.engine.act.BlockTargeting.findVisibleFace(sp, pos, direction,
                    com.mineagent.engine.act.BlockTargeting.interactionReach(sp));
            if (port.isEmpty()) {
                result.addProperty("capability_status", "requires_interaction_reach_and_visible_face");
            } else {
                try {
                    var snapshot = com.mineagent.api.platform.Services.platform().observeBlockCapabilities(
                            player, x, y, z, port.get().getDirection().getName());
                    result.addProperty("capability_face", port.get().getDirection().getName());
                    result.add("capabilities", new com.google.gson.Gson().toJsonTree(snapshot));
                } catch (RuntimeException unavailable) {
                    result.addProperty("capability_status", "adapter_unavailable");
                }
            }
        }
        result.addProperty("is_air", state.isAir());
        result.addProperty("is_solid", state.isSolidRender(level, pos));
        result.addProperty("hardness", state.getDestroySpeed(level, pos));
        reply.accept(result.toString());
    }
}
