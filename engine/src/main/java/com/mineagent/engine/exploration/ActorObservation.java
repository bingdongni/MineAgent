package com.mineagent.engine.exploration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.engine.world.SemanticWorldModel;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

/** Only known observation tools may publish actor-instance facts into the learning substrate. */
final class ActorObservation {
    private ActorObservation() {}
    static void project(String tool,String raw,String correlation,long tick,SemanticWorldModel world) {
        if (!"inspect_entity".equals(tool) || raw==null || raw.length()>12_000) return;
        try {
            JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            String subject=root.get("fact_subject").getAsString();
            if (!subject.startsWith("entity:") || !UUID.fromString(subject.substring(7)).toString().equals(subject.substring(7))) return;
            var retained = new HashSet<String>();
            for(String key:List.of("entity_id","entity_type","dimension","display_name","alive","on_fire","in_water","health","max_health")) {
                if(!root.has(key)||!root.get(key).isJsonPrimitive()) continue;
                String value=root.get(key).getAsString();
                if(value.length()>256) continue;
                retained.add(key);
                world.observe(subject,key,value,null,0.95,tool,correlation,tick,80,false);
            }
            for (var old : world.factsForSubject(subject, tick))
                if (old.source().equals(tool) && !retained.contains(old.predicate()))
                    world.retract(subject, old.predicate(), tool, correlation, tick, false);
        } catch(RuntimeException malformed) { /* No private fields or invented defaults. */ }
    }
}
