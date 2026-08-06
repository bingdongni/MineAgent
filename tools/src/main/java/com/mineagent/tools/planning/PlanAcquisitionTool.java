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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Builds a bounded, evidence-backed dependency plan for acquiring an item. */
public final class PlanAcquisitionTool implements Tool {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 96;
    private static final int MAX_ALTERNATIVES = 8;

    @Override public String name() { return "plan_acquisition"; }

    @Override
    public String description() {
        return "Expand an exact registered item/count into a bounded dependency graph using "
                + "live inventory, actually observed storage/drops, recipe yields, ingredient "
                + "alternatives and registered recipe types. Use before a long production task; "
                + "unknown station mechanics remain explicit exploration leaves.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Exact registered output item ID")
                .integer("desired_count", "Desired total carried count", 1, 4096)
                .optionalInteger("max_depth", "Maximum recursive recipe depth (default 6)",
                        1, MAX_DEPTH)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String requested = ToolArgs.getString(args, "item_id");
        ResourceLocation target = requested == null ? null
                : ResourceLocation.tryParse(requested.trim());
        if (target == null || !BuiltInRegistries.ITEM.containsKey(target)) {
            reply.accept(ToolArgs.errorJson("Unknown registered item: " + requested));
            return;
        }
        Integer desired = ToolArgs.getIntOrNull(args, "desired_count");
        Integer depth = ToolArgs.has(args, "max_depth")
                ? ToolArgs.getIntOrNull(args, "max_depth") : 6;
        if (desired == null || desired < 1 || desired > 4096) {
            reply.accept(ToolArgs.errorJson("'desired_count' must be an integer from 1 to 4096."));
            return;
        }
        if (depth == null || depth < 1 || depth > MAX_DEPTH) {
            reply.accept(ToolArgs.errorJson("'max_depth' must be an integer from 1 to "
                    + MAX_DEPTH + "."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        var loop = TaskContext.agentLoop(player);
        if (loop == null) {
            reply.accept(ToolArgs.errorJson("Agent cognitive state is not available."));
            return;
        }
        long gameTick = sp.level().getGameTime();
        WorldAssetIndex index = loop.worldAssetIndex();
        index.observeInventory(WorldAssetObserver.position(sp),
                WorldAssetObserver.inventory(sp), gameTick);

        Ledger ledger = Ledger.from(index.snapshot());
        RecipeIndex recipes = RecipeIndex.create(sp);
        PlanningContext context = new PlanningContext(recipes, ledger, depth);
        PlanNode root = context.plan(target.toString(), desired, 0,
                new LinkedHashSet<>());

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("target", target.toString());
        result.addProperty("desired_count", desired);
        result.addProperty("complete_dependency_contract", root.unresolved() == 0
                && !context.truncated);
        result.addProperty("execution_ready", root.unresolved() == 0
                && !context.truncated && context.executionRequirements.isEmpty());
        result.addProperty("node_count", context.nodeCount);
        result.addProperty("truncated", context.truncated);
        result.add("plan", root.toJson());
        JsonArray leaves = new JsonArray();
        context.unresolvedLeaves.forEach((item, count) -> {
            JsonObject leaf = new JsonObject();
            leaf.addProperty("item", item);
            leaf.addProperty("count", count);
            leaves.add(leaf);
        });
        result.add("unresolved_leaves", leaves);
        JsonArray executionRequirements = new JsonArray();
        context.executionRequirements.forEach(executionRequirements::add);
        result.add("execution_requirements", executionRequirements);
        result.addProperty("execution_rule",
                "Retrieve observed assets before production; execute crafting recipes with craft; "
                        + "for other recipe types use the named station and verify its live GUI; "
                        + "explore unknown station mechanics instead of guessing or spawning outputs.");
        reply.accept(result.toString());
    }

    private record PlanNode(String item, int requested, int fromCarried,
                            int fromObservedAssets, int fromPlannedSurplus, int unresolved,
                            String recipeId, String recipeType, String station,
                            String execution, int outputPerBatch, int batches,
                            List<IngredientPlan> ingredients, String note) {
        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("item", item);
            value.addProperty("requested", requested);
            value.addProperty("from_carried", fromCarried);
            value.addProperty("from_observed_assets", fromObservedAssets);
            value.addProperty("from_planned_surplus", fromPlannedSurplus);
            value.addProperty("unresolved", unresolved);
            if (recipeId != null) {
                value.addProperty("recipe_id", recipeId);
                value.addProperty("recipe_type", recipeType);
                value.addProperty("station", station);
                value.addProperty("execution", execution);
                value.addProperty("output_per_batch", outputPerBatch);
                value.addProperty("batches", batches);
            }
            if (note != null) value.addProperty("note", note);
            JsonArray children = new JsonArray();
            for (IngredientPlan ingredient : ingredients) children.add(ingredient.toJson());
            value.add("ingredients", children);
            return value;
        }
    }

    private record IngredientPlan(String selected, int count,
                                  List<String> alternatives, PlanNode dependency) {
        JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("selected", selected);
            value.addProperty("count", count);
            JsonArray options = new JsonArray();
            alternatives.forEach(options::add);
            value.add("alternatives", options);
            value.add("dependency", dependency.toJson());
            return value;
        }
    }

    private static final class PlanningContext {
        private final RecipeIndex recipes;
        private final Ledger ledger;
        private final int maxDepth;
        private final Map<String, Integer> unresolvedLeaves = new LinkedHashMap<>();
        private final Set<String> executionRequirements = new LinkedHashSet<>();
        private int nodeCount;
        private boolean truncated;

        private PlanningContext(RecipeIndex recipes, Ledger ledger, int maxDepth) {
            this.recipes = recipes;
            this.ledger = ledger;
            this.maxDepth = maxDepth;
        }

        private PlanNode plan(String item, int requested, int depth, Set<String> path) {
            nodeCount++;
            Ledger.Supply supplied = ledger.take(item, requested);
            int deficit = Math.max(0, requested - supplied.total());
            if (deficit == 0) {
                return leaf(item, requested, supplied, 0, "satisfied by observed ownership");
            }
            if (nodeCount >= MAX_NODES || depth >= maxDepth) {
                truncated = true;
                addLeaf(item, deficit);
                return leaf(item, requested, supplied, deficit,
                        "dependency budget reached; observe or extend planning depth");
            }
            if (!path.add(item)) {
                addLeaf(item, deficit);
                return leaf(item, requested, supplied, deficit,
                        "recipe cycle detected; choose a different recipe or source");
            }

            RecipeChoice choice = recipes.best(item);
            if (choice == null || choice.ingredients().isEmpty()) {
                path.remove(item);
                addLeaf(item, deficit);
                return leaf(item, requested, supplied, deficit,
                        choice == null ? "no registered producing recipe"
                                : "recipe exposes no static ingredient contract; inspect its mechanism");
            }

            int batches = divideRoundUp(deficit, choice.outputCount());
            if ("crafting_table_3x3".equals(choice.station())) {
                executionRequirements.add("observe_or_reach:minecraft:crafting_table");
            } else if (!"craft".equals(choice.execution())) {
                // Registered non-crafting recipes describe item dependencies,
                // but they do not prove that the matching machine is present,
                // reachable, powered, or exposes a usable GUI contract.
                executionRequirements.add("observe_station_contract:"
                        + choice.station());
                if (isFuelledCooking(choice.recipeType())) {
                    executionRequirements.add("supply_fuel:" + choice.recipeType());
                }
            }
            List<IngredientPlan> children = new ArrayList<>();
            int unresolved = 0;
            for (Ingredient ingredient : choice.ingredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                List<String> alternatives = recipes.alternatives(ingredient);
                String selected = selectAlternative(alternatives);
                if (selected == null) {
                    unresolved += batches;
                    addLeaf("unresolved_ingredient:" + choice.recipeId(), batches);
                    continue;
                }
                PlanNode dependency = plan(selected, batches, depth + 1,
                        new LinkedHashSet<>(path));
                unresolved += dependency.unresolved();
                children.add(new IngredientPlan(selected, batches,
                        alternatives, dependency));
            }
            path.remove(item);
            int surplus = batches * choice.outputCount() - deficit;
            if (surplus > 0) ledger.addPlanned(item, surplus);
            return new PlanNode(item, requested, supplied.carried(), supplied.observed(),
                    supplied.planned(), unresolved, choice.recipeId(), choice.recipeType(), choice.station(),
                    choice.execution(), choice.outputCount(), batches,
                    List.copyOf(children), choice.note());
        }

        private String selectAlternative(List<String> alternatives) {
            return alternatives.stream().min(Comparator
                    .comparingInt((String item) -> -ledger.available(item))
                    .thenComparingInt(item -> recipes.hasProducer(item) ? 0 : 1)
                    .thenComparing(item -> item)).orElse(null);
        }

        private void addLeaf(String item, int count) {
            unresolvedLeaves.merge(item, Math.max(0, count), Integer::sum);
        }

        private static PlanNode leaf(String item, int requested, Ledger.Supply supplied,
                                     int unresolved, String note) {
            return new PlanNode(item, requested, supplied.carried(), supplied.observed(),
                    supplied.planned(), unresolved, null, null, null, null,
                    0, 0, List.of(), note);
        }
    }

    private static final class Ledger {
        private final Map<String, Integer> carried = new HashMap<>();
        private final Map<String, Integer> observed = new HashMap<>();
        private final Map<String, Integer> planned = new HashMap<>();

        private static Ledger from(List<WorldAssetIndex.Asset> assets) {
            Ledger result = new Ledger();
            for (WorldAssetIndex.Asset asset : assets) {
                if (asset == null || asset.count() <= 0) continue;
                if (asset.carried()) {
                    result.carried.merge(asset.resourceId(), asset.count(), Integer::sum);
                } else if (asset.stored()
                        || asset.scope() == WorldAssetIndex.Scope.DROPPED_ITEM) {
                    result.observed.merge(asset.resourceId(), asset.count(), Integer::sum);
                }
            }
            return result;
        }

        private int available(String item) {
            return carried.getOrDefault(item, 0) + observed.getOrDefault(item, 0)
                    + planned.getOrDefault(item, 0);
        }

        private Supply take(String item, int requested) {
            int fromCarried = takeFrom(carried, item, requested);
            int fromObserved = takeFrom(observed, item, requested - fromCarried);
            int fromPlanned = takeFrom(planned, item,
                    requested - fromCarried - fromObserved);
            return new Supply(fromCarried, fromObserved, fromPlanned);
        }

        private void addPlanned(String item, int count) {
            if (count > 0) planned.merge(item, count, Integer::sum);
        }

        private static int takeFrom(Map<String, Integer> source, String item, int requested) {
            if (requested <= 0) return 0;
            int current = source.getOrDefault(item, 0);
            int taken = Math.min(current, requested);
            if (taken == current) source.remove(item);
            else source.put(item, current - taken);
            return taken;
        }

        private record Supply(int carried, int observed, int planned) {
            int total() { return carried + observed + planned; }
        }
    }

    private record RecipeChoice(String recipeId, String recipeType, String station,
                                String execution, int outputCount,
                                List<Ingredient> ingredients, String note) {}

    private static final class RecipeIndex {
        private final Map<String, List<RecipeChoice>> byOutput = new HashMap<>();

        private static RecipeIndex create(net.minecraft.server.level.ServerPlayer player) {
            RecipeIndex result = new RecipeIndex();
            for (RecipeHolder<?> holder : player.level().getRecipeManager().getRecipes()) {
                var recipe = holder.value();
                ItemStack output = recipe.getResultItem(player.level().registryAccess());
                if (output.isEmpty()) continue;
                String outputId = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
                String type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
                String station;
                String execution;
                String note = null;
                if (recipe instanceof CraftingRecipe crafting) {
                    station = crafting.canCraftInDimensions(2, 2)
                            ? "inventory_2x2_or_crafting_table" : "crafting_table_3x3";
                    execution = "craft";
                } else {
                    station = type;
                    execution = "interact_with_station_and_verify_gui";
                    if (isFuelledCooking(type)) {
                        note = "Fuel is an external station requirement and is not part of the recipe ingredient list.";
                    } else {
                        note = "The registered recipe identifies dependencies, but its station contract must be observed or adapted.";
                    }
                }
                RecipeChoice choice = new RecipeChoice(holder.id().toString(), type,
                        station, execution, Math.max(1, output.getCount()),
                        List.copyOf(recipe.getIngredients()), note);
                result.byOutput.computeIfAbsent(outputId,
                        ignored -> new ArrayList<>()).add(choice);
            }
            result.byOutput.values().forEach(values -> values.sort(Comparator
                    .comparingInt((RecipeChoice value) -> "craft".equals(value.execution()) ? 0 : 1)
                    .thenComparingInt(value -> value.ingredients().isEmpty() ? 1 : 0)
                    .thenComparingInt(value -> value.ingredients().size())
                    .thenComparing(RecipeChoice::recipeId)));
            return result;
        }

        private RecipeChoice best(String output) {
            List<RecipeChoice> choices = byOutput.get(output);
            return choices == null || choices.isEmpty() ? null : choices.getFirst();
        }

        private boolean hasProducer(String item) {
            List<RecipeChoice> choices = byOutput.get(item);
            return choices != null && !choices.isEmpty();
        }

        private List<String> alternatives(Ingredient ingredient) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (ItemStack candidate : ingredient.getItems()) {
                if (!candidate.isEmpty()) {
                    result.add(BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString());
                }
                if (result.size() >= MAX_ALTERNATIVES) break;
            }
            return List.copyOf(result);
        }
    }

    private static boolean isFuelledCooking(String recipeType) {
        String path = recipeType.toLowerCase(Locale.ROOT);
        return path.endsWith(":smelting") || path.endsWith(":blasting")
                || path.endsWith(":smoking");
    }

    private static int divideRoundUp(int numerator, int denominator) {
        return (numerator + Math.max(1, denominator) - 1) / Math.max(1, denominator);
    }

    @Override public int defaultTimeoutSeconds() { return 5; }
}
