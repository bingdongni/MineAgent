# Farming

## Overview
Farming is the backbone of sustainable resource generation in Minecraft. This skill covers crop farming, animal husbandry, and automated farm designs for food, materials, and trade goods.

## Prerequisites
- **Hoe**: For tilling farmland (any tier)
- **Seeds/saplings/animals**: Starting stock
- **Water source**: Farmland needs water within 4 blocks
- **Bone meal**: Optional, accelerates growth
- **Fences/gates**: For animal pens

## Crop Farming

### Wheat Farm
```
# Build a basic wheat farm
# Farmland needs water within 4 blocks horizontally

# 1. Place water source
build("water", x=0, y=0, z=0)

# 2. Till the surrounding dirt (within 4 blocks of water)
use_item_on("hoe", target="dirt")
# Till in a 9×9 pattern around the water

# 3. Plant seeds
use_item_on("wheat_seeds", target="farmland")

# 4. Wait for growth (7 stages, ~1-3 real minutes per crop)
# Or use bone meal for instant growth
use_item_on("bone_meal", target="wheat")

# 5. Harvest when fully grown (stage 7)
interact_at(target="wheat")  # Drops 1 wheat + 0-3 seeds

# 6. Re-plant seeds
use_item_on("wheat_seeds", target="farmland")
```

### Carrot/Potato/Beetroot Farm
```
# Same as wheat but with different crops
# Carrots: plant carrots directly on farmland
# Potatoes: plant potatoes directly on farmland
# Beetroot: plant beetroot seeds on farmland

# Carrots are great for:
# - Food (golden carrots!)
# - Trading with farmers
# - Pig food (for breeding)

use_item_on("carrot", target="farmland")
# Harvest drops 1-4 carrots (affected by Fortune)
```

### Pumpkin/Melon Farm
```
# Pumpkins and melons grow on farmland but produce fruit adjacent

# 1. Till a row of farmland
# 2. Plant pumpkin/melon seeds
# 3. Leave an EMPTY dirt/grass block adjacent for the fruit to grow

# Layout (P = planted, _ = empty space for fruit):
# _ P _ P _ P
# _ _ _ _ _ _

# The fruit grows on the empty space
# Each fruit takes 10-30 minutes to grow
# Harvest with shears (for carved version) or any tool
use_item_on("shears", target="pumpkin")
```

### Sugar Cane Farm
```
# Sugar cane grows on dirt/sand adjacent to water
# Grows up to 3 blocks tall

# Layout (W = water, S = sugar cane, D = dirt):
# D S D S D S D
# W D W D W D W
# D S D S D S D

# Plant sugar cane on dirt NEXT to water
build("water", x=0, y=0, z=0)
build("dirt", x=1, y=0, z=0)
use_item_on("sugar_cane", target="dirt")

# Harvest the TOP 2 blocks (leave the bottom to regrow)
interact_at(x=1, y=2, z=0)  # Top
interact_at(x=1, y=1, z=0)  # Middle

# Sugar cane uses:
# - Paper (3 sugar cane → 3 paper) → books → enchanting
# - Sugar → potions, food
# - Trading with librarians
```

## Animal Husbandry

### Cow Farm
```
# Cows provide: leather, raw beef, milk

# 1. Build a pen with fences
build("oak_fence", x=pen_x, y=pen_y, z=pen_z)  # Enclosure
build("oak_fence_gate", x=gate_x, y=gate_y, z=gate_z)  # Entrance

# 2. Lure cows with wheat
equip("wheat")
# Cows follow you when holding wheat
goto(x=cow_x, y=cow_y, z=cow_z)  # Lead them to pen

# 3. Breed two cows
use_item_on("wheat", target="cow_1")
use_item_on("wheat", target="cow_2")
# Baby cow spawns! Grows up in 20 minutes

# 4. Kill adult cows for drops
melee_attack(target="cow")
pickup_items(range=4)
# Drops: 0-2 leather, 1-3 raw beef

# 5. Milk cows (right-click with bucket)
equip("bucket")
use_item_on("bucket", target="cow")
# Get: milk bucket (cures all potion effects)
```

### Sheep Farm
```
# Sheep provide: wool, mutton

# Lure with wheat, breed with wheat (same as cows)
# Shearing is better than killing for wool:
equip("shears")
use_item_on("shears", target="sheep")
# Drops 1-3 wool (color matches sheep color)
# Sheep regrows wool after eating grass

# Dye sheep for colored wool:
use_item_on("red_dye", target="white_sheep")
# Now the sheep is red and produces red wool!
```

### Chicken Farm
```
# Chickens provide: raw chicken, feathers, eggs

# Lure with seeds, breed with seeds
use_item_on("wheat_seeds", target="chicken_1")
use_item_on("wheat_seeds", target="chicken_2")

# Chickens lay eggs every 5-10 minutes
# Collect eggs automatically with hoppers:
build("hopper", x=pen_x, y=pen_y-1, z=pen_z)
build("chest", x=pen_x, y=pen_y-2, z=pen_z)

# Throw eggs to hatch chicks (1/8 chance per throw)
equip("egg")
use_item()  # Throws egg, may spawn chick

# Feathers are essential for arrows and books
# 1 feather + 1 stick + 1 flint = 4 arrows
```

### Pig Farm
```
# Pigs provide: raw porkchop (best food when cooked)

# Lure with carrot/potato/beetroot
# Breed with carrot/potato/beetroot
use_item_on("carrot", target="pig_1")
use_item_on("carrot", target="pig_2")

# Cooked porkchop: restores 8 hunger + 12.8 saturation
# One of the best foods in the game!
smelt("raw_porkchop", fuel="coal")
```

## Automated Farm Designs

### Simple Auto-Harvester (Observer + Hopper)
```
# Auto-harvesting sugar cane:
# Observer detects when sugar cane grows to height 3
# Piston breaks the top blocks
# Hopper minecart collects drops

# Layout:
build("observer", x=1, y=1, z=0, facing="up")  # Detects growth
build("piston", x=1, y=2, z=0, facing="down")  # Pushes to break
build("hopper_minecart", x=1, y=0, z=0)  # On rail below
build("rail", x=1, y=0, z=0)

# When sugar cane grows to height 3:
# 1. Observer detects update
# 2. Piston fires, breaking top 2 blocks
# 3. Items fall into hopper minecart
# 4. Bottom block regrows
```

### Villager-Based Auto Farm
```
# Farmer villagers can plant and harvest crops automatically!
# They need:
# - Farmland within their village boundary
# - Composter (their workstation)
# - Access to the farm

# Setup:
build("composter", x=0, y=0, z=0)  # Farmer workstation
# Build farmland around the composter
# Plant initial crop
# The farmer will:
# 1. Plant seeds on empty farmland
# 2. Harvest grown crops
# 3. Share food with other villagers
# 4. Compost seeds → bone meal

# To collect the harvest, use a hopper under the farmland
build("hopper", x=farm_x, y=farm_y-1, z=farm_z)
```

## Tool Usage Examples

### Golden Carrot Farm
```
# Golden carrots = best food in the game (14 hunger, 21.6 saturation)
# Requires: carrot farm + gold farm

# 1. Grow carrots
build("carrot_farm", x=0, y=0, z=0)
harvest("carrot", count=64)

# 2. Mine gold (badlands biome has gold at any Y)
mine_until("gold_ore", count=64)
smelt("raw_gold", count=64)

# 3. Craft golden carrots
craft("golden_carrot", count=64)  # 1 carrot + 8 gold nuggets each
# 8 gold nuggets = 1 gold ingot in the crafting table
# So 64 golden carrots = 64 carrots + 64 gold ingots
```

### Tree Farm (Manual)
```
# Oak trees are the most versatile (drop apples!)
# Birch trees never have branches (always straight trunk)

# Plant saplings
use_item_on("oak_sapling", target="dirt")

# Wait for growth or use bone meal
use_item_on("bone_meal", target="oak_sapling")

# Harvest the trunk
# Start from bottom, work up
use_item_on("axe", target="oak_log")
# Collect saplings (from leaf decay) and apples

# Re-plant a sapling where the trunk was
use_item_on("oak_sapling", target="dirt")
```

## Common Pitfalls

1. **Farmland drying out**: Farmland without nearby water (4 blocks) dries out and reverts to dirt. Crops are destroyed.
2. **Trampling farmland**: Jumping on farmland turns it to dirt. Use fences/pathways to avoid walking on crops.
3. **Wrong light level**: Crops need light level ≥ 8 to grow. Place torches or glowstone nearby.
4. **Animal pen too small**: Animals can escape through fence corners. Double-check enclosure corners.
5. **Overbreeding**: Too many animals in a pen causes lag and overcrowding. Limit to ~20 per pen.
6. **Not replanting**: Always replant after harvest! Carry seeds in your hotbar.

## Resource Requirements
| Farm Type | Setup Cost | Yield Rate | Notes |
|-----------|-----------|------------|-------|
| Wheat | Seeds + water + hoe | ~1 stack/5 min | Manual harvest |
| Sugar Cane | 1 sugar cane + water | ~1 stack/10 min | Easy to automate |
| Cow | 2 cows + wheat | 1 cow/5 min breed | Leather + beef |
| Chicken | 2 chickens + seeds | 1 egg/5 min, 1 chick/7 min | Feathers + eggs |
| Villager Farm | Villager + composter | Auto-harvest | Best long-term |

## Safety Warnings
- **NEVER** jump on farmland — it reverts to dirt and kills the crop
- **NEVER** place blocks on farmland — same effect as jumping
- **ALWAYS** ensure water is within 4 blocks of all farmland
- **ALWAYS** have a light source near crops (torch every 8 blocks)
- **WATCH** for animals trampling crops — fence them out
- Iron golems can trample crops too — keep them out of farm areas
- Growing trees can destroy nearby structures — plant saplings 3+ blocks from builds
- Sugar cane can only be planted on dirt/sand/moss directly adjacent to water
