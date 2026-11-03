package com.mineagent.tools.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.*;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import java.util.Map;
import java.util.function.Consumer;

/** Public observations of one visible loaded actor; private AI/quest/NBT state is not read. */
public final class InspectEntityTool implements Tool {
    @Override public String name() { return "inspect_entity"; }
    @Override public String description() {
        return "Inspect a visible nearby actor by runtime entity_id: registered type, UUID, public name, health and effects. Custom dialogue/quest internals are not exposed.";
    }
    @Override public Map<String,Object> parameterSchema() {
        return Schema.object().integer("entity_id", "Runtime ID from look_around", 0, Integer.MAX_VALUE).build();
    }
    @Override public void onServerCall(String id, JsonObject args, AgentPlayer player, Consumer<String> reply) {
        Integer entityId = ToolArgs.getIntOrNull(args, "entity_id");
        if (entityId == null || entityId < 0) { reply.accept(ToolArgs.errorJson("entity_id must be a non-negative integer")); return; }
        var sp = ((CompanionEntity)player).serverPlayer();
        var entity = sp.serverLevel().getEntity(entityId);
        if (entity == null || !entity.isAlive() || entity == sp || sp.distanceToSqr(entity) > 256
                || entity.isInvisibleTo(sp) || !sp.hasLineOfSight(entity)) {
            reply.accept(ToolArgs.errorJson("Entity is unavailable, out of observation range, or not visible")); return;
        }
        JsonObject result = new JsonObject();
        result.addProperty("fact_subject", "entity:" + entity.getUUID());
        result.addProperty("entity_id", entity.getId());
        result.addProperty("entity_type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("dimension", sp.level().dimension().location().toString());
        result.addProperty("display_name", compact(entity.getName().getString(), 160));
        result.addProperty("alive", entity.isAlive());
        result.addProperty("on_fire", entity.isOnFire());
        result.addProperty("in_water", entity.isInWater());
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            if (Float.isFinite(living.getHealth())) result.addProperty("health", living.getHealth());
            if (Float.isFinite(living.getMaxHealth())) result.addProperty("max_health", living.getMaxHealth());
            JsonArray effects = new JsonArray();
            living.getActiveEffects().stream().limit(12).forEach(effect -> {
                JsonObject value = new JsonObject();
                value.addProperty("id", String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value())));
                value.addProperty("amplifier", effect.getAmplifier()); value.addProperty("duration_ticks", effect.getDuration());
                effects.add(value);
            });
            result.add("effects", effects);
            result.addProperty("effects_truncated", living.getActiveEffects().size() > 12);
        }
        result.addProperty("dialogue_status", "requires_supported_game_interaction_or_mod_adapter");
        reply.accept(result.toString());
    }
    private static String compact(String value,int limit) {
        String text = value.replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= limit ? text : text.substring(0,limit);
    }
}
