package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Close the currently open container GUI. If no container is open,
 * this is a no-op.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class CloseGuiTool implements Tool {

    @Override
    public String name() { return "close_gui"; }

    @Override
    public String description() {
        return """
            Close the currently open container GUI (chest, furnace, crafting
            table, etc.). If no container is open, this does nothing.
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

        if (sp.containerMenu != sp.inventoryMenu) {
            sp.closeContainer();
            reply.accept("{\"success\":true,\"message\":\"Container GUI closed.\"}");
        } else {
            reply.accept("{\"success\":true,\"message\":\"No container GUI was open.\"}");
        }
    }
}
