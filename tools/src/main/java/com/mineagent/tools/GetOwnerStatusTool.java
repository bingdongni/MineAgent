package com.mineagent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Returns the live owner's status when the owner is online. */
public class GetOwnerStatusTool implements Tool {
    @Override public String name() { return "get_owner_status"; }
    @Override public String description() {
        return "Get the online owner's health, food, position, game mode, experience, and active effects.";
    }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        var owner = ((CompanionEntity) player).serverPlayerOwner();
        if (owner == null || owner.hasDisconnected()) {
            // Companions can survive owner dimension changes and disconnect
            // windows; status queries must not dereference a stale owner.
            reply.accept(ToolArgs.errorJson("The companion owner is currently offline."));
            return;
        }

        var pos = owner.blockPosition();
        JsonObject result = new JsonObject();
        result.addProperty("name", owner.getName().getString());
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        result.add("position", position);
        result.addProperty("dimension", owner.level().dimension().location().toString());
        result.addProperty("health", owner.getHealth());
        result.addProperty("max_health", owner.getMaxHealth());
        result.addProperty("food", owner.getFoodData().getFoodLevel());
        result.addProperty("saturation", owner.getFoodData().getSaturationLevel());
        result.addProperty("game_mode", owner.gameMode.getGameModeForPlayer().getName());
        result.addProperty("xp_level", owner.experienceLevel);
        result.addProperty("air", owner.getAirSupply());

        JsonArray effects = new JsonArray();
        for (var effect : owner.getActiveEffects()) {
            JsonObject value = new JsonObject();
            value.addProperty("effect", net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                    .getKey(effect.getEffect().value()).toString());
            value.addProperty("amplifier", effect.getAmplifier());
            value.addProperty("duration", effect.getDuration());
            effects.add(value);
        }
        result.add("effects", effects);
        reply.accept(result.toString());
    }
}
