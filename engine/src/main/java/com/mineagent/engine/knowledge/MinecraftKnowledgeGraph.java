package com.mineagent.engine.knowledge;

import java.util.*;

/**
 * HDKG 知识图 — Minecraft 合成规则和工具依赖的有向图。
 *
 * <p>灵感来自 Optimus-1 (NeurIPS 2024) 的 Hierarchical Directed Knowledge Graph。
 * 让 AI 在规划时显式检索合成规则，避免 LLM 幻觉合成配方。
 *
 * <p>当前版本包含 Minecraft 核心合成链的静态知识：
 * <ul>
 *   <li>木头 → 板材 → 棍子 → 工具</li>
 *   <li>圆石 → 石制工具 → 铁矿 → 铁锭 → 铁制工具</li>
 *   <li>铁矿 → 铁锭 → 盾牌/盔甲</li>
 *   <li>钻石 → 钻石工具 → 下界</li>
 * </ul>
 */
public class MinecraftKnowledgeGraph {

    /**
     * 一条合成规则。
     *
     * @param inputs  输入物品 (物品名 → 数量)
     * @param output  输出物品
     * @param outputCount 输出数量
     * @param station 合成站（crafting_table / furnace / smithing_table）
     */
    public record Recipe(
            Map<String, Integer> inputs,
            String output,
            int outputCount,
            String station
    ) {}

    /** 物品 → 可生产的合成规则列表。 */
    private final Map<String, List<Recipe>> outputIndex = new HashMap<>();

    /** 物品 → 作为输入的合成规则列表（反向索引，查"这个材料能做什么"）。 */
    private final Map<String, List<Recipe>> inputIndex = new HashMap<>();

    public MinecraftKnowledgeGraph() {
        initCoreRecipes();
    }

    /**
     * 查询某个物品的合成配方。
     *
     * @param outputItem 目标物品名
     * @return 所有可用配方，或空列表
     */
    public List<Recipe> findRecipes(String outputItem) {
        return outputIndex.getOrDefault(outputItem.toLowerCase(), Collections.emptyList());
    }

    /**
     * 查询某个材料能合成什么。
     *
     * @param inputItem 输入材料名
     * @return 所有使用该材料的配方
     */
    public List<Recipe> findUses(String inputItem) {
        return inputIndex.getOrDefault(inputItem.toLowerCase(), Collections.emptyList());
    }

    /**
     * 规划从当前背包状态到目标物品的合成链。
     *
     * @param target 目标物品
     * @param inventory 当前背包（物品名 → 数量）
     * @return 合成步骤列表（从最基础的材料开始），或 null 表示无法合成
     */
    public List<Recipe> planCraftingChain(String target, Map<String, Integer> inventory) {
        List<Recipe> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        if (planCraftingChainRecursive(target, 1, inventory, chain, visited)) {
            // 从最基础的开始（chain 是反向收集的，需要反转）
            Collections.reverse(chain);
            return chain;
        }
        return null;
    }

    /**
     * 生成供 LLM 使用的知识摘要。
     */
    public String summarizeForPrompt() {
        return """
            ## Minecraft 合成知识（已知规则）
            - 原木 → 4 板材（合成台）
            - 2 板材 → 4 棍子（合成台）
            - 3 板材 → 1 合成台（合成台）
            - 2 棍子 + 3 板材/圆石/铁锭/钻石 → 对应工具（合成台）
            - 棍子 + 5 铁锭 → 盾牌（合成台）
            - 铁矿 → 铁锭（熔炉+燃料）
            - 金矿 → 金锭（熔炉+燃料）
            - 圆石 → 熔炉（合成台，8 圆石）
            - 棍子 + 线 → 钓鱼竿
            - 棍子 + 羽毛 + 燧石 → 箭矢
            - 钻石 + 黑曜石 + 书 → 附魔台
            遇到合成需求时，先查这个知识表，不要幻觉配方。
            """;
    }

    // ── 内部方法 ──

    private boolean planCraftingChainRecursive(String target, int count,
                                                 Map<String, Integer> inventory,
                                                 List<Recipe> chain,
                                                 Set<String> visited) {
        if (visited.contains(target)) return false;
        visited.add(target);

        // 检查背包是否已有足够数量
        int have = inventory.getOrDefault(target.toLowerCase(), 0);
        if (have >= count) return true;

        // 找配方
        List<Recipe> recipes = findRecipes(target);
        if (recipes.isEmpty()) return false; // 无法合成

        Recipe recipe = recipes.get(0); // 取第一个配方
        int needBatches = (int) Math.ceil((double) (count - have) / recipe.outputCount());

        // 递归检查所有输入材料
        for (var entry : recipe.inputs().entrySet()) {
            String input = entry.getKey();
            int needed = entry.getValue() * needBatches;
            int haveInput = inventory.getOrDefault(input.toLowerCase(), 0);
            if (haveInput < needed) {
                if (!planCraftingChainRecursive(input, needed - haveInput, inventory, chain, visited)) {
                    return false; // 某个输入材料无法获取
                }
            }
        }

        chain.add(recipe);
        return true;
    }

    private void addRecipe(Map<String, Integer> inputs, String output,
                            int outputCount, String station) {
        Recipe recipe = new Recipe(inputs, output, outputCount, station);
        outputIndex.computeIfAbsent(output.toLowerCase(), k -> new ArrayList<>()).add(recipe);
        for (String input : inputs.keySet()) {
            inputIndex.computeIfAbsent(input.toLowerCase(), k -> new ArrayList<>()).add(recipe);
        }
    }

    private void initCoreRecipes() {
        // 木材链
        addRecipe(Map.of("oak_log", 1), "oak_planks", 4, "inventory");
        addRecipe(Map.of("oak_planks", 2), "stick", 4, "inventory");
        addRecipe(Map.of("oak_planks", 4), "crafting_table", 1, "inventory");

        // 工具链
        addRecipe(Map.of("stick", 2, "oak_planks", 3), "wooden_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "oak_planks", 3), "wooden_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "cobblestone", 3), "stone_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "cobblestone", 3), "stone_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "iron_ingot", 3), "iron_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "iron_ingot", 3), "iron_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "diamond", 3), "diamond_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "diamond", 3), "diamond_axe", 1, "crafting_table");

        // 武器
        addRecipe(Map.of("stick", 1, "oak_planks", 2), "wooden_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "cobblestone", 2), "stone_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "iron_ingot", 2), "iron_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "diamond", 2), "diamond_sword", 1, "crafting_table");

        // 盔甲
        addRecipe(Map.of("iron_ingot", 8), "iron_chestplate", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 5), "iron_helmet", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 7), "iron_leggings", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 4), "iron_boots", 1, "crafting_table");
        addRecipe(Map.of("diamond", 8), "diamond_chestplate", 1, "crafting_table");

        // 防具
        addRecipe(Map.of("iron_ingot", 6, "oak_planks", 1), "shield", 1, "crafting_table");

        // 熔炼
        addRecipe(Map.of("raw_iron", 1), "iron_ingot", 1, "furnace");
        addRecipe(Map.of("raw_gold", 1), "gold_ingot", 1, "furnace");
        addRecipe(Map.of("raw_copper", 1), "copper_ingot", 1, "furnace");
        addRecipe(Map.of("sand", 1), "glass", 1, "furnace");

        // 熔炉
        addRecipe(Map.of("cobblestone", 8), "furnace", 1, "crafting_table");

        // 火把
        addRecipe(Map.of("stick", 1, "coal", 1), "torch", 4, "inventory");

        // 食物
        addRecipe(Map.of("wheat", 3), "bread", 1, "inventory");
        addRecipe(Map.of("wheat", 2, "egg", 1), "cake", 1, "crafting_table");

        // 红石基础
        addRecipe(Map.of("redstone", 1, "stick", 1), "redstone_torch", 1, "crafting_table");
        addRecipe(Map.of("cobblestone", 3, "redstone", 1), "repeater", 1, "crafting_table");

        // 附魔台
        addRecipe(Map.of("diamond", 2, "obsidian", 4, "book", 1), "enchanting_table", 1, "crafting_table");
        addRecipe(Map.of("leather", 1, "paper", 3), "book", 1, "crafting_table");
    }
}
