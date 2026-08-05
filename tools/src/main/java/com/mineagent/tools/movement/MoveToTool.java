package com.mineagent.tools.movement;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Move the companion to a target position using A* pathfinding.
 * Supports 4 goal modes: xz (horizontal), xzy (exact block),
 * y (altitude), block (adjacent to a block).
 *
 * <p>This is an <b>async</b> tool - it dispatches a MoveToTaskRecord
 * and returns a task_id immediately.
 */
public class MoveToTool implements Tool {

    @Override
    public String name() { return "goto"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return """
            Move to a target position using pathfinding. Supports 4 goal modes:
            - "xz": walk to horizontal coordinates (x, z) at current y-level
            - "xzy": walk to exact block position (x, y, z)
            - "y": climb/descend to altitude y
            - "block": walk adjacent to the block at (x, y, z)
            
            The companion will dig through blocks, build bridges, and climb pillars
            to reach the goal. Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("goal_mode", "Goal mode: xz, xzy, y, or block")
                .optionalInteger("x", "Target X coordinate (required for xz/xzy/block)", -30_000_000, 30_000_000)
                .optionalInteger("y", "Target Y coordinate (required for xzy/y/block)", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", "Target Z coordinate (required for xz/xzy/block)", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String goalMode = ToolArgs.getString(args, "goal_mode");
        if (goalMode == null) {
            reply.accept("{\"error\":\"Missing required parameter 'goal_mode'. Use xz/xzy/y/block.\"}");
            return;
        }
        Integer parsedX = ToolArgs.getIntOrNull(args, "x");
        Integer y = ToolArgs.getIntOrNull(args, "y");
        Integer z = ToolArgs.getIntOrNull(args, "z");

        // Validate goal mode
        if (!java.util.Set.of("xz", "xzy", "y", "block").contains(goalMode)) {
            reply.accept(ToolArgs.errorJson("Invalid goal_mode: " + goalMode
                    + ". Use xz/xzy/y/block."));
            return;
        }

        // Validate required coordinates per goal mode
        switch (goalMode) {
            case "xz" -> {
                if (parsedX == null || z == null) {
                    reply.accept("{\"error\":\"goal_mode 'xz' requires both x and z coordinates.\"}");
                    return;
                }
            }
            case "xzy", "block" -> {
                if (parsedX == null || y == null || z == null) {
                    reply.accept(ToolArgs.errorJson("goal_mode '" + goalMode
                            + "' requires x, y, and z coordinates."));
                    return;
                }
            }
            case "y" -> {
                if (y == null) {
                    reply.accept("{\"error\":\"goal_mode 'y' requires a y coordinate.\"}");
                    return;
                }
            }
        }

        int x = parsedX != null ? parsedX : (int) Math.floor(player.posX());
        var target = new net.minecraft.core.BlockPos(x,
                y != null ? y : (int) Math.floor(player.posY()),
                z != null ? z : (int) Math.floor(player.posZ()));
        var level = ((com.mineagent.engine.entity.CompanionEntity) player)
                .serverPlayer().serverLevel();
        if (!level.isInWorldBounds(target)) {
            reply.accept(ToolArgs.errorJson("Target is outside this dimension's world bounds: " + target.toShortString()));
            return;
        }

        // Create task record (would be a concrete MoveToTaskRecord)
        // For now, dispatch via TaskDispatch
        var record = new MoveToTaskRecord(toolCallId, goalMode, x, y, z);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for movement. */
    public static class MoveToTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final String goalMode;
        public final int x;
        public final Integer y;
        public final Integer z;

        public MoveToTaskRecord(String toolCallId, String goalMode, int x, Integer y, Integer z) {
            super(toolCallId);
            this.goalMode = goalMode;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
