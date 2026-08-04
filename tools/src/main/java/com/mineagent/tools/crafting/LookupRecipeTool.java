package com.mineagent.tools.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Consumer;

/** Queries registered recipes by an exact input or output item ID. */
public class LookupRecipeTool implements Tool {
    private static final int MAX_RESULTS = 20;

    @Override public String name() { return "lookup_recipe"; }
    @Override public String description() {
        return "Find registered recipes that produce or consume an exact item ID, including ingredient alternatives.";
    }
    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "Registered item ID (e.g. 'minecraft:stick')")
                .string("mode", "Search mode: 'output' or 'input'")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String query = ToolArgs.getString(args, "query");
        ResourceLocation queryId = query == null ? null : ResourceLocation.tryParse(query);
        if (queryId == null || !BuiltInRegistries.ITEM.containsKey(queryId)) {
            reply.accept(ToolArgs.errorJson("Unknown item: " + query));
            return;
        }
        String mode = ToolArgs.getString(args, "mode");
        if (!"output".equals(mode) && !"input".equals(mode)) {
            reply.accept(ToolArgs.errorJson("'mode' must be 'output' or 'input'."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        JsonArray matches = new JsonArray();
        for (var holder : sp.level().getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            var output = recipe.getResultItem(sp.level().registryAccess());
            boolean matchesQuery = "output".equals(mode)
                    ? BuiltInRegistries.ITEM.getKey(output.getItem()).equals(queryId)
                    : recipe.getIngredients().stream().anyMatch(ingredient -> {
                        if (ingredient.isEmpty()) return false;
                        for (var candidate : ingredient.getItems()) {
                            if (BuiltInRegistries.ITEM.getKey(candidate.getItem()).equals(queryId)) return true;
                        }
                        return false;
                    });
            if (!matchesQuery) continue;

            JsonObject value = new JsonObject();
            value.addProperty("recipe_id", holder.id().toString());
            JsonArray ingredients = new JsonArray();
            for (var ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                JsonArray alternatives = new JsonArray();
                for (var candidate : ingredient.getItems()) {
                    alternatives.add(BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString());
                }
                ingredients.add(alternatives);
            }
            value.add("ingredients", ingredients);
            value.addProperty("result", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
            value.addProperty("result_count", output.getCount());
            value.addProperty("recipe_type", BuiltInRegistries.RECIPE_TYPE
                    .getKey(recipe.getType()).toString());
            matches.add(value);
            if (matches.size() >= MAX_RESULTS) break;
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", queryId.toString());
        result.addProperty("mode", mode);
        result.add("results", matches);
        result.addProperty("total", matches.size());
        result.addProperty("truncated", matches.size() >= MAX_RESULTS);
        reply.accept(result.toString());
    }
}
