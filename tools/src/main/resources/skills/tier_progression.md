# Tier Progression

## Overview
Equipment in Minecraft follows a tier system: Wood → Stone → Iron → Diamond → Netherite. Each tier provides better durability, damage, and mining speed. This skill covers efficient progression through each tier and when to upgrade.

## Prerequisites
- Starting from scratch (wood tools)
- Basic knowledge of crafting recipes

## Tier Statistics

| Tier | Mining Speed | Sword Damage | Durability | Enchantability |
|------|-------------|-------------|------------|----------------|
| Wood | 2.0 | 4 | 59 | 15 |
| Stone | 4.0 | 5 | 131 | 5 |
| Iron | 6.0 | 6 | 250 | 14 |
| Diamond | 8.0 | 7 | 1561 | 10 |
| Netherite | 9.0 | 8 | 2031 | 15 |

Armor defense (full set):
| Tier | Defense | Toughness | Knockback Resist |
|------|---------|-----------|-----------------|
| Leather | 7 | 0 | 0% |
| Iron | 15 | 0 | 0% |
| Diamond | 20 | 8 | 0% |
| Netherite | 20 | 12 | 100% |

## Step-by-Step Procedure

### Tier 0: Wood (Skip if possible)
Wood tools are only needed briefly to get stone.

```
# Punch a tree
interact_at(target="oak_log")

# Craft wood planks
craft("oak_planks", count=4)  # 1 log = 4 planks

# Craft crafting table
craft("crafting_table", count=1)
build("crafting_table", x=pos_x, y=pos_y, z=pos_z)

# Craft sticks
craft("stick", count=8)  # 2 planks = 4 sticks

# Craft wood pickaxe
craft("wooden_pickaxe", count=1)

# You ONLY need a wood pickaxe — skip other wood tools
```

### Tier 1: Stone
Stone tools are adequate for early mining.

```
# Mine cobblestone with wood pickaxe
use_item_on("wooden_pickaxe", target="stone")

# Craft stone tools
craft("stone_pickaxe", count=1)
craft("stone_sword", count=1)
craft("stone_axe", count=1)  # Optional: weapon + tool

# Discard wood pickaxe — stone is strictly better
discard("wooden_pickaxe")

# Mine more cobblestone for building
mine_until("cobblestone", count=64)
```

### Tier 2: Iron
Iron is the first "serious" tier. Get full iron armor before going further.

```
# Find iron ore (Y=-58 to Y=72, best at Y=-58 in 1.18+)
goto(x=pos_x, y=-58, z=pos_z)
scan_blocks("iron_ore", range=32)

# Mine iron ore
use_item_on("stone_pickaxe", target="iron_ore")
mine_until("raw_iron", count=31)  # Full armor(24) + sword(2) + pickaxe(3) + shield(1) = 30+

# Smelt raw iron
build("furnace", x=pos_x, y=pos_y, z=pos_z)
smelt("raw_iron", fuel="coal", count=31)

# Craft iron gear
craft("iron_helmet", count=1)     # 5 ingots
craft("iron_chestplate", count=1) # 8 ingots
craft("iron_leggings", count=1)   # 7 ingots
craft("iron_boots", count=1)      # 4 ingots
craft("iron_sword", count=1)      # 2 ingots
craft("iron_pickaxe", count=1)    # 3 ingots
craft("shield", count=1)          # 1 iron + 6 planks

# Equip everything
equip("iron_helmet", slot="head")
equip("iron_chestplate", slot="chest")
equip("iron_leggings", slot="legs")
equip("iron_boots", slot="feet")
equip("iron_sword", slot="mainhand")
```

### Tier 3: Diamond
Diamond is required for obsidian mining and is the standard for the Nether.

```
# Find diamonds (Y=-58 to Y=-48, best at Y=-59 in 1.18+)
goto(x=pos_x, y=-59, z=pos_z)
scan_blocks("diamond_ore", range=32)

# Mine with IRON pickaxe (stone can't mine diamonds!)
mine_until("diamond", count=5)  # Pickaxe(3) + sword(2) minimum

# More diamonds for armor: 5+4+8+7+4 = 28 total
# But you can skip diamond armor if you have enchanted iron

# Craft diamond pickaxe FIRST (for obsidian)
craft("diamond_pickaxe", count=1)  # 3 diamonds

# Craft diamond sword
craft("diamond_sword", count=1)    # 2 diamonds

# Optional: Diamond armor (24 diamonds total)
# Skip if you're short — enchanted iron can work
craft("diamond_helmet", count=1)     # 5 diamonds
craft("diamond_chestplate", count=1) # 8 diamonds
craft("diamond_leggings", count=1)   # 7 diamonds
craft("diamond_boots", count=1)      # 4 diamonds
```

### Tier 4: Netherite
Netherite is the ultimate tier, but expensive. Only upgrade after diamond.

```
# Find ancient debris (Y=8-22 in Nether, best at Y=15)
# Ancient debris is blast-resistant — use TNT or beds to find it
load_skill("nether_entry")
goto(x=nether_x, y=15, z=nether_z)

# Method 1: TNT mining
# Use TNT at Y=15 to blast away netherrack
# Ancient debris survives the explosion
build("tnt", x=pos_x, y=15, z=pos_z)
ignite("tnt")
# Look for ancient debris in the crater
scan_blocks("ancient_debris", range=8)

# Method 2: Strip mining
mine_until("ancient_debris", count=16)  # 4 debris = 1 netherite scrap
# 4 scraps + 4 gold = 1 netherite ingot
# Need 1 ingot per tool/armor piece to upgrade

# Process ancient debris
smelt("ancient_debris", count=16)  # 16 debris = 16 netherite scrap

# Craft netherite ingots
# 4 scraps + 4 gold ingots = 1 netherite ingot
craft("netherite_ingot", count=4)  # Need gold!

# Upgrade diamond gear to netherite
# Use smithing table
craft("smithing_table", count=1)
upgrade("diamond_sword", "netherite_ingot")   # 1 ingot each
upgrade("diamond_pickaxe", "netherite_ingot")
upgrade("diamond_chestplate", "netherite_ingot")
# ... etc
```

## Tool Usage Examples

### Efficient Early Game
```
# Optimal early game sequence (skip wood sword entirely)
# 1. Punch 3 logs
# 2. Craft crafting table + 6 sticks
# 3. Craft wood pickaxe
# 4. Mine 3 cobblestone
# 5. Craft stone pickaxe
# 6. Discard wood pickaxe
# 7. Mine 8 more cobblestone
# 8. Craft stone sword + stone axe
# 9. Done with wood tier in <2 minutes
```

### Iron Rush
```
# Skip stone armor entirely — go straight to iron
# Priority order:
# 1. Iron pickaxe (for mining more iron + diamonds)
# 2. Iron sword (for defense)
# 3. Iron chestplate (most defense per ingot)
# 4. Iron leggings
# 5. Iron helmet
# 6. Iron boots
# 7. Shield

# NEVER craft stone armor — it's a waste of cobblestone
# Iron is fast enough to get without stone armor
```

### When to Stop and Enchant
```
# Enchanted iron can be better than plain diamond
# Iron with Protection IV > Diamond with no enchant

# Recommended enchanting milestones:
# 1. Get iron gear
# 2. Build enchanting table + 15 bookshelves
# 3. Enchant iron sword with Sharpness III+
# 4. Enchant iron armor with Protection III+
# 5. Now go get diamonds
# 6. Enchant diamond gear properly
```

## Common Pitfalls

1. **Wood tools too long**: Only craft a wood pickaxe. Skip wood sword, axe, shovel, hoe.
2. **Stone armor**: NEVER craft stone/chain armor — it's not worth the resources.
3. **Wrong Y level**: Iron: Y=-58. Diamond: Y=-59. Ancient debris: Y=15 (Nether).
4. **Stone pick on diamonds**: Stone pickaxe CANNOT mine diamonds, gold, redstone, emerald, or lapis. You get the block break but no drop.
5. **Skipping shield**: Shields block 100% of damage from the front. Always craft one.
6. **Netherite before enchanting**: Don't upgrade to netherite before enchanting your diamond gear. Enchant diamond first, then upgrade (enchantments carry over).

## Resource Requirements
| Tier | Key Materials | Total Time |
|------|--------------|------------|
| Wood→Stone | 3 logs + 11 cobblestone | ~2 min |
| Iron | 31+ iron ingots | ~15 min |
| Diamond | 5-28 diamonds | ~30 min |
| Netherite | 16+ ancient debris + 16+ gold | ~60 min |

## Safety Warnings
- **NEVER** mine vertically down — dig a 2×1 staircase at minimum
- **NEVER** go to the Nether with stone gear — iron minimum
- **ALWAYS** have a water bucket when mining — lava pools are common at diamond level
- **ALWAYS** keep backup gear in a chest at your base
- **WATCH** for lava at Y=-58 — there are large lava pools at this depth
- Stone tools can't mine: diamond, gold, redstone, emerald, lapis, obsidian
- Iron tools can't mine: obsidian (need diamond)
