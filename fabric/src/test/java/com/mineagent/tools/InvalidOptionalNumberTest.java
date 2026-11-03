package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class InvalidOptionalNumberTest {
    @Test void invalidOptionalNumbersReturnErrorsBeforeServerStateAccess() {
        record Case(Tool tool, String arguments) {}
        List<Case> cases = List.of(
                new Case(new com.mineagent.tools.perception.InspectItemTool(), "{\"inventory_slot\":\"bad\"}"),
                new Case(new com.mineagent.tools.perception.InspectEntityTool(), "{\"entity_id\":\"bad\"}"),
                new Case(new com.mineagent.tools.perception.LookAroundTool(), "{\"radius\":\"bad\"}"),
                new Case(new com.mineagent.tools.perception.ScanBlocksTool(), "{\"block_type\":\"minecraft:stone\",\"radius\":\"bad\"}"),
                new Case(new com.mineagent.tools.perception.ScanNearbyEntitiesTool(), "{\"radius\":\"bad\"}"),
                new Case(new InteractAtTool(), "{\"button\":\"use\",\"x\":0,\"y\":64,\"z\":0,\"hold_ticks\":\"bad\"}"),
                new Case(new InteractEntityTool(), "{\"button\":\"use\",\"entity_id\":1,\"hold_ticks\":\"bad\"}"),
                new Case(new com.mineagent.tools.combat.MeleeAttackTool(), "{\"entity_id\":1,\"hold_ticks\":\"bad\"}"),
                new Case(new com.mineagent.tools.combat.RangedAttackTool(), "{\"entity_id\":1,\"charge_ticks\":\"bad\"}"),
                new Case(new com.mineagent.tools.inventory.UseItemTool(), "{\"hand\":\"main\",\"hold_ticks\":\"bad\"}"),
                new Case(new com.mineagent.tools.inventory.CollectItemsTool(), "{\"radius\":\"bad\"}"),
                new Case(new com.mineagent.tools.inventory.CollectItemsTool(), "{\"count\":\"bad\"}"),
                new Case(new com.mineagent.tools.planning.ResolveNeedTool(), "{\"kind\":\"capability\",\"target\":\"mining\",\"desired_count\":\"bad\"}"),
                new Case(new com.mineagent.tools.management.WaitForTool(), "{\"kind\":\"duration\",\"duration_ticks\":1,\"radius\":\"bad\"}")
        );
        for (Case sample : cases) {
            JsonObject args = JsonParser.parseString(sample.arguments()).getAsJsonObject();
            AtomicReference<String> result = new AtomicReference<>();
            assertDoesNotThrow(() -> sample.tool().onServerCall("invalid", args, null, result::set), sample.tool().name());
            assertNotNull(result.get(), sample.tool().name());
            assertTrue(JsonParser.parseString(result.get()).getAsJsonObject().has("error"), sample.tool().name());
        }
    }
}
