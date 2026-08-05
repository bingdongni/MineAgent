package com.mineagent.tools.combat;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.TaskDispatch;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Attack an entity with a melee weapon. The companion will move within
 * range and repeatedly strike until the target is dead or the task
 * is cancelled.
 *
 * <p>This is an <b>async</b> tool - it dispatches a MeleeAttackTaskRecord
 * and returns a task_id immediately.
 */
public class MeleeAttackTool implements Tool {

    @Override
    public String name() { return "melee_attack"; }

    @Override public boolean dispatchesAsyncTask() { return true; }

    @Override
    public String description() {
        return """
            Attack an entity with a melee weapon. The companion will navigate
            within melee range and strike the target repeatedly until it dies
            or the task is cancelled. Uses the currently equipped weapon.
            
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("entity_id", "The entity ID of the target to attack", 0, Integer.MAX_VALUE)
                .optionalInteger("hold_ticks", "Extra aim ticks before each swing (0-40, default 0)", 0, 40)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        if (!ToolArgs.has(args, "entity_id")) {
            reply.accept("{\"error\":\"Missing required parameter 'entity_id'.\"}");
            return;
        }
        Integer entityId = ToolArgs.getIntOrNull(args, "entity_id");
        if (entityId == null || entityId < 0) {
            reply.accept(ToolArgs.errorJson("'entity_id' must be a non-negative integer."));
            return;
        }
        Integer holdTicks = ToolArgs.has(args, "hold_ticks")
                ? ToolArgs.getIntOrNull(args, "hold_ticks") : 0;
        if (holdTicks == null || holdTicks < 0 || holdTicks > 40) {
            reply.accept(ToolArgs.errorJson("'hold_ticks' must be an integer from 0 to 40."));
            return;
        }
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = sp.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive() || target == sp || !target.isAttackable()) {
            reply.accept(ToolArgs.errorJson("Entity " + entityId + " was not found or cannot be attacked."));
            return;
        }

        var record = new MeleeAttackTaskRecord(toolCallId, entityId, holdTicks);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for melee attacks. */
    public static class MeleeAttackTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final int entityId;
        public final int holdTicks;

        public MeleeAttackTaskRecord(String toolCallId, int entityId, int holdTicks) {
            super(toolCallId);
            this.entityId = entityId;
            this.holdTicks = holdTicks;
        }
    }
}
