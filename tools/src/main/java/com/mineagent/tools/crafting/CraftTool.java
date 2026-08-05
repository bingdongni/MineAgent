package com.mineagent.tools.crafting;

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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Crafts a registered crafting recipe while preserving vanilla item semantics. */
public class CraftTool implements Tool {

    private static final int MAX_BATCHES = 64;
    private static final int CRAFTING_TABLE_RADIUS = 4;
    private static final int CRAFTING_TABLE_DISCOVERY_RADIUS = 16;

    @Override
    public String name() { return "craft"; }

    @Override
    public String description() {
        return """
            Craft items using a registered crafting recipe. The companion uses
            its 2x2 grid or a nearby crafting table for 3x3 recipes. The count
            is the number of recipe batches, not the number of output items.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("recipe_id", "Registered crafting recipe ID (e.g. 'minecraft:stone_pickaxe')")
                .integer("count", "Number of recipe batches to craft (1-64)", 1, 64)
                .optionalString("use_table", "Crafting station: 'auto', 'table', or 'inventory' (default: 'auto')")
                .optionalInteger("desired_total", "Desired total count after crafting. Defaults to count multiplied by recipe output; existing inventory reduces work.", 1, 4096)
                .optionalString("purpose", "Why this item is needed; state the capability or explicit request for an additional copy")
                .optionalBoolean("bypass_reuse_check", "Use only after a returned reuse candidate was verified unavailable, unsafe, too costly, or the owner explicitly requested an additional copy")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String requestedId = ToolArgs.getString(args, "recipe_id");
        if (requestedId == null || requestedId.isBlank()) {
            reply.accept(ToolArgs.errorJson("Missing required parameter 'recipe_id'."));
            return;
        }
        ResourceLocation recipeId = ResourceLocation.tryParse(requestedId);
        if (recipeId == null) {
            reply.accept(ToolArgs.errorJson("Invalid recipe ID: " + requestedId));
            return;
        }
        Integer requestedBatches = ToolArgs.getIntOrNull(args, "count");
        if (requestedBatches == null || requestedBatches < 1 || requestedBatches > MAX_BATCHES) {
            reply.accept(ToolArgs.errorJson("'count' must be an integer from 1 to " + MAX_BATCHES + "."));
            return;
        }
        String mode = ToolArgs.getString(args, "use_table", "auto").toLowerCase(Locale.ROOT);
        if (!List.of("auto", "table", "inventory").contains(mode)) {
            reply.accept(ToolArgs.errorJson("'use_table' must be 'auto', 'table', or 'inventory'."));
            return;
        }

        var sp = ((CompanionEntity) player).serverPlayer();
        RecipeHolder<?> untypedHolder = sp.level().getRecipeManager().byKey(recipeId).orElse(null);
        if (untypedHolder == null) {
            reply.accept(ToolArgs.errorJson("Recipe '" + recipeId + "' was not found."));
            return;
        }
        if (!(untypedHolder.value() instanceof CraftingRecipe recipe)) {
            reply.accept(ToolArgs.errorJson("Recipe '" + recipeId
                    + "' is not a crafting-grid recipe. Use its required station instead."));
            return;
        }

        ItemStack preview = recipe.getResultItem(sp.level().registryAccess());
        String outputItemId = preview.isEmpty() ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(preview.getItem()).toString();
        int previewCount = preview.isEmpty() ? 0 : Math.max(1, preview.getCount());
        Integer desiredArgument = ToolArgs.has(args, "desired_total")
                ? ToolArgs.getIntOrNull(args, "desired_total") : null;
        if (ToolArgs.has(args, "desired_total")
                && (desiredArgument == null || desiredArgument < 1 || desiredArgument > 4096)) {
            reply.accept(ToolArgs.errorJson("'desired_total' must be an integer from 1 to 4096."));
            return;
        }
        int desiredTotal = desiredArgument == null
                ? Math.max(1, requestedBatches * Math.max(1, previewCount))
                : desiredArgument;
        boolean bypassReuse = ToolArgs.getBool(args, "bypass_reuse_check", false);

        WorldAssetIndex.ReuseAssessment reuse = null;
        if (outputItemId != null) {
            var loop = TaskContext.agentLoop(player);
            if (loop != null) {
                WorldAssetIndex.Position current = WorldAssetObserver.position(sp);
                loop.worldAssetIndex().observeInventory(current,
                        WorldAssetObserver.inventory(sp), sp.level().getGameTime());
                reuse = loop.worldAssetIndex().assessAcquisition(
                        outputItemId, desiredTotal,
                        WorldAssetObserver.substitutionCapabilities(preview),
                        WorldAssetObserver.quality(preview), current,
                        sp.level().getGameTime());

                if (reuse.carriedExactCount() >= desiredTotal) {
                    JsonObject result = new JsonObject();
                    result.addProperty("success", true);
                    result.addProperty("action", "reuse_inventory");
                    result.addProperty("recipe", recipeId.toString());
                    result.addProperty("item", outputItemId);
                    result.addProperty("desired_total", desiredTotal);
                    result.addProperty("carried_count", reuse.carriedExactCount());
                    result.addProperty("crafted_batches", 0);
                    result.addProperty("crafted_items", 0);
                    result.addProperty("reason",
                            "The requested total is already present; no ingredients were consumed.");
                    reply.accept(result.toString());
                    return;
                }

                int unresolved = desiredTotal - reuse.carriedExactCount();
                boolean enoughStoredExact = reuse.knownStoredExactCount() >= unresolved;
                boolean capableStoredSubstitute = desiredTotal == 1
                        && !reuse.storedSubstitutes().isEmpty();
                boolean reusableWorldObject = !reuse.worldExact().isEmpty();
                if (!bypassReuse && (enoughStoredExact || capableStoredSubstitute
                        || reusableWorldObject)) {
                    // Do not silently choose a universal cost formula here.
                    // Return grounded alternatives and require the deliberative
                    // layer to compare retrieval risk/time against production.
                    JsonObject decision = new JsonObject();
                    decision.addProperty("success", false);
                    decision.addProperty("status", "reuse_decision_required");
                    decision.addProperty("recipe", recipeId.toString());
                    decision.addProperty("item", outputItemId);
                    decision.addProperty("desired_total", desiredTotal);
                    decision.addProperty("carried_count", reuse.carriedExactCount());
                    decision.addProperty("unresolved_count", unresolved);
                    decision.addProperty("purpose",
                            ToolArgs.getString(args, "purpose", "unspecified"));
                    JsonArray candidates = new JsonArray();
                    reuse.storedExact().forEach(value -> candidates.add(reuseJson(value)));
                    reuse.storedSubstitutes().forEach(value -> candidates.add(reuseJson(value)));
                    reuse.worldExact().forEach(value -> candidates.add(reuseJson(value)));
                    decision.add("reuse_candidates", candidates);
                    decision.addProperty("required_next_step",
                            "Compare live safety, reachability, time and owner intent. Verify stored contents with goto + inspect_block_storage/open GUI before transfer. Retry with bypass_reuse_check=true only with a concrete reason or explicit extra-copy requirement.");
                    reply.accept(decision.toString());
                    return;
                }
            }
        }

        boolean fitsInventory = recipe.canCraftInDimensions(2, 2);
        boolean requireTable = mode.equals("table") || (!fitsInventory && mode.equals("auto"));
        if (mode.equals("inventory") && !fitsInventory) {
            reply.accept(ToolArgs.errorJson("Recipe '" + recipeId + "' does not fit the 2x2 inventory grid."));
            return;
        }
        if (requireTable) {
            BlockPos nearestTable = findNearestCraftingTable(
                    sp, CRAFTING_TABLE_DISCOVERY_RADIUS);
            if (nearestTable == null) {
                reply.accept(ToolArgs.errorJson("No crafting table was found within "
                        + CRAFTING_TABLE_DISCOVERY_RADIUS + " blocks for recipe '"
                        + recipeId + "'. Observe or obtain a table before retrying."));
                return;
            }
            double tableDistance = Math.sqrt(nearestTable.distToCenterSqr(sp.position()));
            if (tableDistance > CRAFTING_TABLE_RADIUS) {
                // Returning the known station coordinate turns a failed
                // prerequisite into an executable navigation step. The old
                // generic error made the model craft another table even when
                // its own table was visible only a few blocks farther away.
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("code", "crafting_table_out_of_reach");
                error.addProperty("error", "A crafting table exists nearby but is outside crafting range.");
                JsonObject table = new JsonObject();
                table.addProperty("x", nearestTable.getX());
                table.addProperty("y", nearestTable.getY());
                table.addProperty("z", nearestTable.getZ());
                table.addProperty("distance", Math.round(tableDistance * 10.0) / 10.0);
                error.add("nearest_table", table);
                error.addProperty("required_range", CRAFTING_TABLE_RADIUS);
                error.addProperty("suggested_action", "Use goto with goal_mode=block and the nearest_table coordinates, then retry craft.");
                reply.accept(error.toString());
                return;
            }
        }

        int gridSize = requireTable ? 3 : 2;
        int batchesToCraft = requestedBatches;
        if (previewCount > 0 && reuse != null) {
            int deficit = Math.max(0, desiredTotal - reuse.carriedExactCount());
            batchesToCraft = Math.min(requestedBatches,
                    Math.max(1, (deficit + previewCount - 1) / previewCount));
        }
        int craftedBatches = 0;
        int craftedItems = 0;
        String stopReason = null;
        for (int batch = 0; batch < batchesToCraft; batch++) {
            CraftPlan plan = createPlan(sp.getInventory(), recipe, gridSize, sp.level());
            if (plan == null) {
                stopReason = "Missing ingredients that form a valid recipe input.";
                break;
            }

            ItemStack output = recipe.assemble(plan.input(), sp.level().registryAccess());
            if (output.isEmpty()) {
                // Dynamic recipes are allowed only when their actual input
                // produces an output. Never consume ingredients based on the
                // recipe's preview stack, which can be empty or incomplete.
                stopReason = "The selected ingredients produced no crafting result.";
                break;
            }
            var remainders = recipe.getRemainingItems(plan.input());
            if (remainders.size() != plan.input().size()) {
                stopReason = "Recipe returned an invalid remainder list.";
                break;
            }

            // Everything above this point is a read-only preparation phase.
            // Once consumption begins, every source slot and remainder is
            // already known, so a failed recipe can never remove half a batch.
            consumeInputs(sp.getInventory(), plan);
            for (int gridSlot = 0; gridSlot < remainders.size(); gridSlot++) {
                ItemStack remainder = remainders.get(gridSlot).copy();
                if (!remainder.isEmpty()) {
                    returnRemainder(sp, plan.inventorySlots()[gridSlot], remainder);
                }
            }

            int outputCount = output.getCount();
            output.onCraftedBy(sp.level(), sp, outputCount);
            deliverOrDrop(sp, output);
            sp.triggerRecipeCrafted(untypedHolder, plan.input().items());
            sp.awardRecipes(List.of(untypedHolder));
            craftedBatches++;
            craftedItems += outputCount;
        }

        TaskContext.syncInventory(sp);
        if (craftedBatches == 0) {
            reply.accept(ToolArgs.errorJson("Cannot craft '" + recipeId + "'. "
                    + (stopReason != null ? stopReason : "Unknown crafting failure.")));
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("recipe", recipeId.toString());
        result.addProperty("crafted_batches", craftedBatches);
        result.addProperty("crafted_items", craftedItems);
        result.addProperty("desired_total", desiredTotal);
        result.addProperty("carried_before", reuse == null ? 0 : reuse.carriedExactCount());
        if (craftedBatches < batchesToCraft && stopReason != null) {
            result.addProperty("stopped_reason", stopReason);
        }
        reply.accept(result.toString());
    }

    private static JsonObject reuseJson(WorldAssetIndex.Candidate candidate) {
        JsonObject value = new JsonObject();
        var asset = candidate.asset();
        value.addProperty("action", candidate.action());
        value.addProperty("scope", asset.scope().name().toLowerCase(Locale.ROOT));
        value.addProperty("resource_id", asset.resourceId());
        value.addProperty("count", asset.count());
        if (asset.containerId() != null) value.addProperty("container_id", asset.containerId());
        if (asset.position() != null) {
            value.addProperty("dimension", asset.position().dimension());
            value.addProperty("x", asset.position().x());
            value.addProperty("y", asset.position().y());
            value.addProperty("z", asset.position().z());
        }
        if (Double.isFinite(candidate.distance())) {
            value.addProperty("distance", Math.round(candidate.distance() * 10.0) / 10.0);
        }
        value.addProperty("age_ticks", candidate.ageTicks());
        if (!asset.capabilities().isEmpty()) {
            JsonArray capabilities = new JsonArray();
            asset.capabilities().stream().sorted().forEach(capabilities::add);
            value.add("capabilities", capabilities);
        }
        return value;
    }

    private static CraftPlan createPlan(Inventory inventory, CraftingRecipe recipe,
                                        int gridSize, net.minecraft.world.level.Level level) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            // Special recipes such as map cloning derive requirements from
            // arbitrary component data and expose no ingredient contract.
            // Guessing those requirements would reintroduce duplication bugs.
            return null;
        }

        Ingredient[] gridIngredients = new Ingredient[gridSize * gridSize];
        Arrays.fill(gridIngredients, Ingredient.EMPTY);
        if (recipe instanceof ShapedRecipe shaped) {
            if (shaped.getWidth() > gridSize || shaped.getHeight() > gridSize) return null;
            if (ingredients.size() < shaped.getWidth() * shaped.getHeight()) return null;
            for (int row = 0; row < shaped.getHeight(); row++) {
                for (int column = 0; column < shaped.getWidth(); column++) {
                    int source = column + row * shaped.getWidth();
                    gridIngredients[column + row * gridSize] = ingredients.get(source);
                }
            }
        } else {
            if (ingredients.size() > gridIngredients.length) return null;
            for (int i = 0; i < ingredients.size(); i++) gridIngredients[i] = ingredients.get(i);
        }

        int[] assignedInventorySlots = new int[gridIngredients.length];
        Arrays.fill(assignedInventorySlots, -1);
        int[] available = new int[Inventory.INVENTORY_SIZE];
        for (int slot = 0; slot < available.length; slot++) {
            available[slot] = inventory.getItem(slot).getCount();
        }

        List<Integer> requiredGridSlots = new ArrayList<>();
        for (int i = 0; i < gridIngredients.length; i++) {
            if (!gridIngredients[i].isEmpty()) requiredGridSlots.add(i);
        }
        // Resolve restrictive ingredients first. This prevents a broad tag
        // ingredient from consuming the only stack valid for a later exact
        // ingredient while another tag-compatible stack was available.
        requiredGridSlots.sort((left, right) -> Integer.compare(
                matchingSlotCount(inventory, gridIngredients[left]),
                matchingSlotCount(inventory, gridIngredients[right])));

        return assignIngredients(0, requiredGridSlots, gridIngredients,
                assignedInventorySlots, available, inventory, recipe, gridSize, level);
    }

    private static CraftPlan assignIngredients(int cursor, List<Integer> requiredGridSlots,
                                               Ingredient[] gridIngredients,
                                               int[] assignedInventorySlots, int[] available,
                                               Inventory inventory, CraftingRecipe recipe,
                                               int gridSize,
                                               net.minecraft.world.level.Level level) {
        if (cursor >= requiredGridSlots.size()) {
            List<ItemStack> grid = new ArrayList<>(gridIngredients.length);
            for (int gridSlot = 0; gridSlot < gridIngredients.length; gridSlot++) {
                int inventorySlot = assignedInventorySlots[gridSlot];
                grid.add(inventorySlot >= 0
                        ? inventory.getItem(inventorySlot).copyWithCount(1)
                        : ItemStack.EMPTY);
            }
            CraftingInput input = CraftingInput.of(gridSize, gridSize, grid);
            return recipe.matches(input, level)
                    ? new CraftPlan(input, assignedInventorySlots.clone()) : null;
        }

        int gridSlot = requiredGridSlots.get(cursor);
        Ingredient ingredient = gridIngredients[gridSlot];
        List<ItemStack> triedEquivalentStacks = new ArrayList<>();
        for (int inventorySlot = 0; inventorySlot < Inventory.INVENTORY_SIZE; inventorySlot++) {
            ItemStack stack = inventory.getItem(inventorySlot);
            if (available[inventorySlot] <= 0 || !ingredient.test(stack)) continue;
            if (triedEquivalentStacks.stream().anyMatch(
                    tried -> ItemStack.isSameItemSameComponents(tried, stack))) continue;
            triedEquivalentStacks.add(stack);

            available[inventorySlot]--;
            assignedInventorySlots[gridSlot] = inventorySlot;
            CraftPlan plan = assignIngredients(cursor + 1, requiredGridSlots,
                    gridIngredients, assignedInventorySlots, available,
                    inventory, recipe, gridSize, level);
            if (plan != null) return plan;
            assignedInventorySlots[gridSlot] = -1;
            available[inventorySlot]++;
        }
        return null;
    }

    private static int matchingSlotCount(Inventory inventory, Ingredient ingredient) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (ingredient.test(inventory.getItem(slot))) count++;
        }
        return count;
    }

    private static void consumeInputs(Inventory inventory, CraftPlan plan) {
        for (int inventorySlot : plan.inventorySlots()) {
            if (inventorySlot < 0) continue;
            ItemStack stack = inventory.getItem(inventorySlot);
            stack.shrink(1);
            if (stack.isEmpty()) inventory.setItem(inventorySlot, ItemStack.EMPTY);
        }
    }

    private static void returnRemainder(net.minecraft.server.level.ServerPlayer sp,
                                        int sourceSlot, ItemStack remainder) {
        Inventory inventory = sp.getInventory();
        if (sourceSlot >= 0) {
            ItemStack current = inventory.getItem(sourceSlot);
            if (current.isEmpty()) {
                inventory.setItem(sourceSlot, remainder);
                return;
            }
            if (ItemStack.isSameItemSameComponents(current, remainder)) {
                int transferable = Math.min(remainder.getCount(),
                        current.getMaxStackSize() - current.getCount());
                if (transferable > 0) {
                    current.grow(transferable);
                    remainder.shrink(transferable);
                }
                if (remainder.isEmpty()) return;
            }
        }
        deliverOrDrop(sp, remainder);
    }

    private static void deliverOrDrop(net.minecraft.server.level.ServerPlayer sp, ItemStack stack) {
        if (stack.isEmpty()) return;
        sp.getInventory().add(stack);
        if (!stack.isEmpty()) sp.drop(stack, false, true);
    }

    private static BlockPos findNearestCraftingTable(
            net.minecraft.server.level.ServerPlayer sp, int radius) {
        BlockPos center = sp.blockPosition();
        BlockPos nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -6, -radius),
                center.offset(radius, 6, radius))) {
            if (!sp.level().hasChunkAt(pos)
                    || !sp.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double distanceSq = pos.distToCenterSqr(sp.position());
            if (distanceSq > (double) radius * radius || distanceSq >= nearestDistanceSq) continue;
            nearest = pos.immutable();
            nearestDistanceSq = distanceSq;
        }
        return nearest;
    }

    private record CraftPlan(CraftingInput input, int[] inventorySlots) {}
}
