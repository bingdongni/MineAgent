package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Get the owner player's status — health, food, position, game mode,
 * and other relevant information.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class GetOwnerStatusTool implements Tool {

    @Override
    public String name() { return "get_owner_status"; }

    @Override
    public String description() {
        return """
            Get the owner player's current status: health, food, position,
            game mode, experience level, and active effects.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var owner = ((CompanionEntity) player).serverPlayerOwner();
        // The owner may be offline while the companion is restored or while a
        // queued LLM response is finishing. Do not dereference a stale owner.
        if (owner == null) {
            reply.accept("{\"error\":\"Owner is not currently online.\"}");
            return;
        }
        var pos = owner.blockPosition();

        // Gson preserves valid JSON regardless of locale and escapes player
        // names, which can contain quotes through server-side display names.
        JsonObject result = new JsonObject();
        result.addProperty("name", owner.getName().getString());
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        result.add("position", position);
        result.addProperty("health", owner.getHealth());
        result.addProperty("max_health", owner.getMaxHealth());
        result.addProperty("food", owner.getFoodData().getFoodLevel());
        result.addProperty("saturation", owner.getFoodData().getSaturationLevel());
        result.addProperty("game_mode", owner.gameMode.getGameModeForPlayer().getName());
        result.addProperty("xp_level", owner.experienceLevel);
        result.addProperty("air", owner.getAirSupply());

        var effectsJson = new com.google.gson.JsonArray();
        var effects = owner.getActiveEffects();
        for (var effect : effects) {
            JsonObject effectJson = new JsonObject();
            effectJson.addProperty("effect", net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                    .getKey(effect.getEffect().value()).toString());
            effectJson.addProperty("amplifier", effect.getAmplifier());
            effectJson.addProperty("duration", effect.getDuration());
            effectsJson.add(effectJson);
        }
        result.add("effects", effectsJson);
        reply.accept(result.toString());
    }
}
