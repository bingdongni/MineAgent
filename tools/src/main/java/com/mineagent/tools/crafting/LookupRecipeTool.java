package com.mineagent.tools.crafting;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Search for recipes by input or output item. Returns matching recipes
 * with ingredient lists and result information.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class LookupRecipeTool implements Tool {

    @Override
    public String name() { return "lookup_recipe"; }

    @Override
    public String description() {
        return """
            Search for crafting recipes by input ingredient or output item.
            Specify a search term and mode:
            - "output": find recipes that produce the specified item
            - "input": find recipes that use the specified item as ingredient
            
            Returns a list of matching recipes with ingredient details.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "Item ID to search for (e.g. 'minecraft:stick', 'minecraft:oak_planks')")
                .string("mode", "Search mode: 'output' (find recipes producing this item) or 'input' (find recipes using this item)")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String query = ToolArgs.getString(args, "query");
        if (query == null) {
            reply.accept("{\"error\":\"Missing required parameter 'query'.\"}");
            return;
        }
        String mode = ToolArgs.getString(args, "mode");
        if (mode == null) {
            reply.accept("{\"error\":\"Missing required parameter 'mode'.\"}");
            return;
        }

        if (!mode.equals("output") && !mode.equals("input")) {
            reply.accept(ToolArgs.errorJson("Invalid mode: " + mode
                    + ". Use 'output' or 'input'."));
            return;
        }

        var queryId = net.minecraft.resources.ResourceLocation.tryParse(query);
        if (queryId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(queryId)) {
            reply.accept(ToolArgs.errorJson("Unknown item: " + query));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var recipeManager = sp.level().getRecipeManager();
        var allRecipes = recipeManager.getRecipes();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":").append(new com.google.gson.Gson().toJson(query))
          .append(",\"mode\":\"").append(mode).append("\",\"results\":[");

        int found = 0;
        for (var holder : allRecipes) {
            var recipe = holder.value();
            boolean matches = false;

            if (mode.equals("output")) {
                var resultId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(recipe.getResultItem(sp.level().registryAccess()).getItem()).toString();
                matches = resultId.equals(query);
            } else {
                // Check if any ingredient matches
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    for (var stack : ingredient.getItems()) {
                        var ingId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).toString();
                        if (ingId.equals(query)) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) break;
                }
            }

            if (matches) {
                if (found > 0) sb.append(",");
                sb.append("{\"recipe_id\":\"").append(holder.id().toString()).append("\"");

                // Ingredients
                sb.append(",\"ingredients\":[");
                boolean first = true;
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    if (!first) sb.append(",");
                    var items = ingredient.getItems();
                    if (items.length > 0) {
                        sb.append("\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(items[0].getItem()).toString()).append("\"");
                    }
                    first = false;
                }
                sb.append("]");

                // Result
                var result = recipe.getResultItem(sp.level().registryAccess());
                sb.append(",\"result\":\"").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(result.getItem()).toString()).append("\"");
                sb.append(",\"result_count\":").append(result.getCount());

                sb.append("}");
                found++;
                if (found >= 20) break; // Limit results
            }
        }

        sb.append("],\"total\":").append(found).append("}");
        reply.accept(sb.toString());
    }
}
