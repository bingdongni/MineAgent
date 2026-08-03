package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Get world information — time of day, weather, dimension, seed,
 * and other global state.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class GetWorldInfoTool implements Tool {

    @Override
    public String name() { return "get_world_info"; }

    @Override
    public String description() {
        return """
            Get current world information: time of day, weather, dimension,
            game difficulty, and game rules relevant to gameplay.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        var sp = ((CompanionEntity) player).serverPlayer();
        var level = sp.level();
        var server = level.getServer();
        var worldData = server.getWorldData();

        // Time of day (0-24000)
        long dayTime = level.getDayTime() % 24000;
        String timeOfDay;
        if (dayTime < 6000) timeOfDay = "day";
        else if (dayTime < 12000) timeOfDay = "afternoon";
        else if (dayTime < 13800) timeOfDay = "dusk";
        else if (dayTime < 22200) timeOfDay = "night";
        else timeOfDay = "dawn";

        // Weather
        boolean isRaining = level.isRaining();
        boolean isThundering = level.isThundering();

        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("day_time", dayTime);
        result.addProperty("time_of_day", timeOfDay);
        result.addProperty("total_time", level.getGameTime());
        result.addProperty("is_raining", isRaining);
        result.addProperty("is_thundering", isThundering);
        if (isRaining) {
            // Numeric JSON properties are locale-independent; String.format
            // could emit a comma decimal separator on some host locales.
            result.addProperty("rain_level", level.getRainLevel(1.0f));
        }
        result.addProperty("difficulty", level.getDifficulty().getKey());
        result.addProperty("is_hardcore", worldData.isHardcore());
        JsonObject rules = new JsonObject();
        rules.addProperty("do_daylight_cycle", level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT));
        rules.addProperty("mob_griefing", level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING));
        result.add("game_rules", rules);

        // Spawn position
        var spawnPos = level.getSharedSpawnPos();
        JsonObject spawn = new JsonObject();
        spawn.addProperty("x", spawnPos.getX());
        spawn.addProperty("y", spawnPos.getY());
        spawn.addProperty("z", spawnPos.getZ());
        result.add("spawn_position", spawn);
        reply.accept(result.toString());
    }
}
