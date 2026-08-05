package com.mineagent.tools.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import com.mineagent.engine.world.WorldAssetIndex;
import com.mineagent.engine.world.WorldAssetObserver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Resolves a concrete item or capability need against all grounded asset
 * locations before the model chooses retrieval, reuse or production.
 */
public final class ResolveNeedTool implements Tool {
    private static final int MAX_RECIPES = 12;

    @Override public String name() { return "resolve_need"; }

    @Override
    public String description() {
        return """
                Resolve an item or capability need before crafting or replacing
                equipment. Searches current inventory/equipment, actually
                inspected container contents, observed dropped items and world
                facilities. For exact items it also returns live registered
                recipes, including modded recipes. Container memory must be
                verified in reach before transfer.
                """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("kind", "Need kind: 'item' or 'capability'")
                .string("target", "Exact registered item ID, or an observed capability such as tool:axe, craft_3x3, smelt, retrieve_items, armor:head")
                .optionalInteger("desired_count", "Required exact item count (default 1)", 1, 4096)
                .optionalString("purpose", "What the asset must accomplish; used to preserve task intent in the result")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String kind = ToolArgs.getString(args, "kind");
        if (!"item".equals(kind) && !"capability".equals(kind)) {
            reply.accept(ToolArgs.errorJson("'kind' must be 'item' or 'capability'."));
            return;
        }
        String target = ToolArgs.getString(args, "target");
        if (target == null || target.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'target'."));
            return;
        }
        Integer desired = ToolArgs.has(args, "desired_count")
                ? ToolArgs.getIntOrNull(args, "desired_count") : 1;
        if (desired == null || desired < 1 || desired > 4096) {
            reply.accept(ToolArgs.errorJson("'desired_count' must be an integer from 1 to 4096."));
            return;
        }

        ResourceLocation itemId = null;
        if ("item".equals(kind)) {
            itemId = ResourceLocation.tryParse(target.trim());
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                reply.accept(ToolArgs.errorJson("Unknown registered item: " + target));
                return;
            }
            target = itemId.toString();
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson("Agent cognitive state is not available."));
            return;
        }
        WorldAssetIndex index = loop.worldAssetIndex();
        WorldAssetIndex.Position current = WorldAssetObserver.position(sp);
        long gameTick = sp.level().getGameTime();
        index.observeInventory(current, WorldAssetObserver.inventory(sp), gameTick);
        if (sp.containerMenu != null && sp.containerMenu != sp.inventoryMenu) {
            WorldAssetObserver.observeOpenMenu(index, sp, sp.containerMenu);
        }

        WorldAssetIndex.NeedResolution resolution = index.resolve(
                kind, target, desired, current, gameTick);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("kind", resolution.kind());
        result.addProperty("target", resolution.target());
        result.addProperty("desired_count", resolution.desiredCount());
        result.addProperty("carried_exact_count", resolution.carriedExactCount());
        result.addProperty("satisfied_now", resolution.satisfiedNow());
        result.addProperty("purpose", ToolArgs.getString(args, "purpose", "unspecified"));
        result.addProperty("asset_revision", resolution.revision());

        JsonArray candidates = new JsonArray();
        for (WorldAssetIndex.Candidate candidate : resolution.candidates()) {
            candidates.add(candidateJson(candidate));
        }
        result.add("candidates", candidates);

        JsonArray recipes = new JsonArray();
        if (itemId != null) addRecipes(sp, itemId, recipes);
        result.add("registered_recipes", recipes);
        String recommendation = resolution.recommendedAction();
        if ("inspect_recipes_or_acquire_inputs".equals(recommendation)
                && !recipes.isEmpty()) recommendation = "choose_registered_recipe_and_acquire_missing_inputs";
        result.addProperty("recommended_action", recommendation);
        result.addProperty("decision_rule",
                "Reuse carried assets first; verify known storage before retrieval; reuse a suitable world facility; produce only the unresolved deficit or an explicitly requested extra copy.");
        reply.accept(result.toString());
    }

    private static JsonObject candidateJson(WorldAssetIndex.Candidate candidate) {
        var asset = candidate.asset();
        JsonObject value = new JsonObject();
        value.addProperty("action", candidate.action());
        value.addProperty("scope", asset.scope().name().toLowerCase(java.util.Locale.ROOT));
        value.addProperty("resource_id", asset.resourceId());
        value.addProperty("kind", asset.kind());
        value.addProperty("count", asset.count());
        if (asset.slot() >= 0) value.addProperty("slot", asset.slot());
        if (asset.containerId() != null) value.addProperty("container_id", asset.containerId());
        if (asset.position() != null) {
            JsonObject position = new JsonObject();
            position.addProperty("dimension", asset.position().dimension());
            position.addProperty("x", asset.position().x());
            position.addProperty("y", asset.position().y());
            position.addProperty("z", asset.position().z());
            value.add("position", position);
        }
        if (Double.isFinite(candidate.distance())) {
            value.addProperty("distance", round(candidate.distance()));
        } else {
            value.addProperty("distance", "different_dimension");
        }
        value.addProperty("age_ticks", candidate.ageTicks());
        value.addProperty("confidence", round(asset.confidence()));
        JsonArray capabilities = new JsonArray();
        asset.capabilities().stream().sorted().forEach(capabilities::add);
        value.add("capabilities", capabilities);
        value.addProperty("reason", candidate.reason());
        return value;
    }

    private static void addRecipes(net.minecraft.server.level.ServerPlayer sp,
                                   ResourceLocation target, JsonArray output) {
        for (var holder : sp.level().getRecipeManager().getRecipes()) {
            var recipe = holder.value();
            var result = recipe.getResultItem(sp.level().registryAccess());
            if (result.isEmpty() || !BuiltInRegistries.ITEM
                    .getKey(result.getItem()).equals(target)) continue;
            JsonObject recipeJson = new JsonObject();
            recipeJson.addProperty("recipe_id", holder.id().toString());
            recipeJson.addProperty("result_count", result.getCount());
            recipeJson.addProperty("recipe_type", BuiltInRegistries.RECIPE_TYPE
                    .getKey(recipe.getType()).toString());
            if (recipe instanceof CraftingRecipe crafting) {
                recipeJson.addProperty("station", crafting.canCraftInDimensions(2, 2)
                        ? "inventory_2x2" : "crafting_table_3x3");
            }
            JsonArray ingredients = new JsonArray();
            for (var ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                JsonArray alternatives = new JsonArray();
                for (var candidate : ingredient.getItems()) {
                    alternatives.add(BuiltInRegistries.ITEM
                            .getKey(candidate.getItem()).toString());
                }
                ingredients.add(alternatives);
            }
            recipeJson.add("ingredients", ingredients);
            output.add(recipeJson);
            if (output.size() >= MAX_RECIPES) return;
        }
    }

    @Override public int defaultTimeoutSeconds() { return 5; }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
