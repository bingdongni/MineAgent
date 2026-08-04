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
 * Shoot a projectile at a target entity. The companion will aim at the target
 * and fire the ranged weapon (bow, crossbow, etc.). Supports charge time
 * for bows.
 *
 * <p>This is an <b>async</b> tool - it dispatches a RangedAttackTaskRecord
 * and returns a task_id immediately.
 */
public class RangedAttackTool implements Tool {

    @Override
    public String name() { return "ranged_attack"; }

    @Override
    public String description() {
        return """
            Shoot a projectile at a target entity. The companion will aim at
            the target and fire the currently held ranged weapon (bow, crossbow,
            trident, etc.). For bows, specify charge_ticks to control draw time.
            
            Returns a task_id for tracking.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("entity_id", "The entity ID of the target to shoot at", 0, Integer.MAX_VALUE)
                .optionalInteger("charge_ticks", "Bow/trident charge time in ticks (3-60, default 20). Longer bow charge increases damage.", 3, 60)
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
        Integer chargeTicks = ToolArgs.has(args, "charge_ticks")
                ? ToolArgs.getIntOrNull(args, "charge_ticks") : 20;
        // A bow released before three ticks has zero vanilla shooting power,
        // so accepting 1-2 advertised a shot that could never be produced.
        if (chargeTicks == null || chargeTicks < 3 || chargeTicks > 60) {
            reply.accept(ToolArgs.errorJson("'charge_ticks' must be an integer from 3 to 60."));
            return;
        }
        var sp = ((com.mineagent.engine.entity.CompanionEntity) player).serverPlayer();
        var target = sp.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive() || target == sp || !target.isAttackable()) {
            reply.accept(ToolArgs.errorJson("Entity " + entityId + " was not found or cannot be attacked."));
            return;
        }

        var record = new RangedAttackTaskRecord(toolCallId, entityId, chargeTicks);
        TaskDispatch.dispatchAsync(player, record, reply);
    }

    /** Task record for ranged attacks. */
    public static class RangedAttackTaskRecord extends com.mineagent.api.task.TaskRecord {
        public final int entityId;
        public final int chargeTicks;

        public RangedAttackTaskRecord(String toolCallId, int entityId, int chargeTicks) {
            super(toolCallId);
            this.entityId = entityId;
            this.chargeTicks = chargeTicks;
        }
    }
}
