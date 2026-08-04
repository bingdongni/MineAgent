package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/** Returns dimension-local world conditions as structured JSON. */
public class GetWorldInfoTool implements Tool {
    @Override public String name() { return "get_world_info"; }
    @Override public String description() {
        return "Get current dimension, time, weather, difficulty, relevant game rules, and spawn position.";
    }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        var level = ((CompanionEntity) player).serverPlayer().serverLevel();
        var worldData = level.getServer().getWorldData();
        long dayTime = Math.floorMod(level.getDayTime(), 24_000L);
        String phase = dayTime < 6_000 ? "day"
                : dayTime < 12_000 ? "afternoon"
                : dayTime < 13_800 ? "dusk"
                : dayTime < 22_200 ? "night" : "dawn";

        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("day_time", dayTime);
        result.addProperty("time_of_day", phase);
        result.addProperty("total_time", level.getGameTime());
        result.addProperty("is_raining", level.isRaining());
        result.addProperty("is_thundering", level.isThundering());
        if (level.isRaining()) result.addProperty("rain_level", level.getRainLevel(1.0f));
        result.addProperty("difficulty", level.getDifficulty().getKey());
        result.addProperty("is_hardcore", worldData.isHardcore());

        JsonObject rules = new JsonObject();
        rules.addProperty("do_daylight_cycle", level.getGameRules().getBoolean(
                net.minecraft.world.level.GameRules.RULE_DAYLIGHT));
        rules.addProperty("mob_griefing", level.getGameRules().getBoolean(
                net.minecraft.world.level.GameRules.RULE_MOBGRIEFING));
        result.add("game_rules", rules);

        var spawn = level.getSharedSpawnPos();
        JsonObject spawnPosition = new JsonObject();
        spawnPosition.addProperty("x", spawn.getX());
        spawnPosition.addProperty("y", spawn.getY());
        spawnPosition.addProperty("z", spawn.getZ());
        result.add("spawn_position", spawnPosition);
        reply.accept(result.toString());
    }
}
