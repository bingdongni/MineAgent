package com.mineagent.tools.crafting;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.task.TaskContext;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Craft through vanilla CraftingRecipe matching and assembly.
 *
 * <p>The tool intentionally does not emulate furnaces, stonecutters, smithing,
 * or other recipe types. Treating every recipe as inventory crafting bypasses
 * their stations, fuel, timing, and remainder rules.
 */
public class CraftTool implements Tool {

    private static final Set<String> TABLE_MODES = Set.of("auto", "table", "inventory");

    @Override
    public String name() { return "craft"; }

    @Override
    public String description() {
        return """
            Craft an item with a vanilla 2x2 or 3x3 crafting recipe.
            Set use_table to auto, table, or inventory. Recipes that do not fit
            the 2x2 inventory grid require a nearby crafting table.
            """;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("recipe_id", "Crafting recipe ID")
                .integer("count", "Number of crafting operations", 1, 64)
                .optionalString("use_table", "auto, table, or inventory")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                             Consumer<String> reply) {
        String recipeId = ToolArgs.getString(args, "recipe_id");
        if (recipeId == null || recipeId.isBlank()) {
            reply.accept("{\"error\":\"Missing required parameter 'recipe_id'.\"}");
            return;
        }
        Integer requestedCount = ToolArgs.getIntOrNull(args, "count");
        if (requestedCount == null) {
            reply.accept("{\"error\":\"count must be a valid integer.\"}");
            return;
        }
        if (requestedCount < 1 || requestedCount > 64) {
            reply.accept("{\"error\":\"count must be between 1 and 64.\"}");
            return;
        }
        int count = requestedCount;
        String tableMode = ToolArgs.getString(args, "use_table", "auto");
        if (!TABLE_MODES.contains(tableMode)) {
            reply.accept("{\"error\":\"use_table must be auto, table, or inventory.\"}");
            return;
        }

        ResourceLocation recipeLocation = ResourceLocation.tryParse(recipeId);
        if (recipeLocation == null) {
            reply.accept("{\"error\":\"Invalid recipe ID.\"}");
            return;
        }

        ServerPlayer sp = ((CompanionEntity) player).serverPlayer();
        RecipeHolder<?> holder = sp.level().getRecipeManager().getRecipes().stream()
                .filter(candidate -> candidate.id().equals(recipeLocation))
                .findFirst().orElse(null);
        if (holder == null) {
            reply.accept("{\"error\":\"Recipe not found.\"}");
            return;
        }
        if (!(holder.value() instanceof CraftingRecipe recipe)) {
            reply.accept("{\"error\":\"Recipe is not a crafting-grid recipe.\"}");
            return;
        }

        boolean needsTable = !recipe.canCraftInDimensions(2, 2);
        if ("inventory".equals(tableMode) && needsTable) {
            reply.accept("{\"error\":\"Recipe does not fit the 2x2 inventory grid.\"}");
            return;
        }
        if (("table".equals(tableMode) || needsTable) && !hasNearbyCraftingTable(sp)) {
            reply.accept("{\"error\":\"A crafting table is required within 4 blocks.\"}");
            return;
        }

        int craftedOperations = 0;
        int producedItems = 0;
        for (int attempt = 0; attempt < count; attempt++) {
            CraftPlan plan = buildPlan(recipe, sp.getInventory());
            if (plan == null || !recipe.matches(plan.input, sp.level())) {
                break;
            }

            ItemStack result = recipe.assemble(plan.input, sp.level().registryAccess());
            if (result.isEmpty()) {
                break;
            }
            NonNullList<ItemStack> remainders = recipe.getRemainingItems(plan.input);

            // Validation and dynamic assembly are complete. Only now consume
            // the exact slots assigned to each non-empty crafting cell.
            for (int sourceSlot : plan.sourceSlots) {
                if (sourceSlot < 0) continue;
                ItemStack source = sp.getInventory().getItem(sourceSlot);
                source.shrink(1);
                if (source.isEmpty()) {
                    sp.getInventory().setItem(sourceSlot, ItemStack.EMPTY);
                }
            }

            producedItems += result.getCount();
            returnOrDrop(sp, result);
            for (ItemStack remainder : remainders) {
                if (!remainder.isEmpty()) {
                    // RecipeManager owns the returned remainder list. Pass a
                    // copy because Inventory.add mutates the supplied stack;
                    // this is not a copy into a second inventory slot.
                    returnOrDrop(sp, remainder.copy());
                }
            }
            craftedOperations++;
        }

        if (craftedOperations == 0) {
            reply.accept("{\"error\":\"Missing ingredients or recipe input did not match.\"}");
            return;
        }

        TaskContext.syncInventory(sp);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("recipe", recipeLocation.toString());
        result.addProperty("crafted", craftedOperations);
        result.addProperty("produced_items", producedItems);
        if (craftedOperations < count) {
            result.addProperty("remaining_attempts", count - craftedOperations);
        }
        reply.accept(result.toString());
    }

    private static CraftPlan buildPlan(CraftingRecipe recipe, Inventory inventory) {
        int width;
        int height;
        List<Ingredient> ingredients;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
            ingredients = shaped.getIngredients();
        } else {
            width = recipe.canCraftInDimensions(2, 2) ? 2 : 3;
            height = width;
            ingredients = recipe.getIngredients().stream()
                    .filter(ingredient -> !ingredient.isEmpty())
                    .toList();
        }

        if (ingredients.isEmpty() || ingredients.size() > width * height) {
            // Special recipes with no declarative ingredients cannot be
            // reconstructed safely from an arbitrary inventory.
            return null;
        }

        int inventorySlots = Math.min(36, inventory.getContainerSize());
        int[] available = new int[inventorySlots];
        for (int i = 0; i < inventorySlots; i++) {
            available[i] = inventory.getItem(i).getCount();
        }

        List<ItemStack> grid = new ArrayList<>(width * height);
        int[] sourceSlots = new int[width * height];
        java.util.Arrays.fill(sourceSlots, -1);
        for (int cell = 0; cell < width * height; cell++) {
            Ingredient ingredient = cell < ingredients.size()
                    ? ingredients.get(cell) : Ingredient.EMPTY;
            if (ingredient.isEmpty()) {
                grid.add(ItemStack.EMPTY);
                continue;
            }

            int sourceSlot = -1;
            for (int slot = 0; slot < inventorySlots; slot++) {
                ItemStack candidate = inventory.getItem(slot);
                if (available[slot] > 0 && ingredient.test(candidate)) {
                    sourceSlot = slot;
                    available[slot]--;
                    break;
                }
            }
            if (sourceSlot < 0) return null;
            ItemStack one = inventory.getItem(sourceSlot).copy();
            one.setCount(1);
            grid.add(one);
            sourceSlots[cell] = sourceSlot;
        }

        return new CraftPlan(CraftingInput.of(width, height, grid), sourceSlots);
    }

    private static boolean hasNearbyCraftingTable(ServerPlayer sp) {
        var origin = sp.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (sp.level().getBlockState(origin.offset(dx, dy, dz))
                            .is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void returnOrDrop(ServerPlayer sp, ItemStack stack) {
        sp.getInventory().add(stack);
        if (!stack.isEmpty()) {
            sp.drop(stack, false, true);
        }
    }

    private record CraftPlan(CraftingInput input, int[] sourceSlots) {}
}
