# Enchanting

## Overview
Enchanting adds powerful modifiers to tools, weapons, and armor using an enchanting table or anvil. This skill covers setting up an enchanting station, understanding enchantments, and maximizing enchantment quality.

## Prerequisites
- **Enchanting table**: 4 obsidian + 2 diamonds + 1 book
- **Bookshelves**: 15 bookshelves for max level (level 30)
- **XP**: Experience levels for enchanting (up to 30 per enchant)
- **Lapis lazuli**: 1-3 per enchant (consumed by enchanting table)
- **Books**: For bookshelves and anvil enchanting

## Step-by-Step Procedure

### Step 1: Build the Enchanting Setup
```
# Craft enchanting table
craft("enchanting_table", count=1)  # 4 obsidian + 2 diamond + 1 book
build("enchanting_table", x=0, y=0, z=0)

# Build 15 bookshelves around the table
# Bookshelf layout (must be within 2 blocks, same or 1 Y level up):
# B B B . B B B
# B . . . . . B
# B B B . B B B
# (where . = air/other, B = bookshelf)

# Each bookshelf: 6 planks + 3 leather (for 3 books, then 9 planks + 3 books)
# Or: 3 books + 6 planks per bookshelf

craft("bookshelf", count=15)
# Place them in the correct pattern around the table
build("bookshelf", x=-2, y=0, z=-2)
build("bookshelf", x=-1, y=0, z=-2)
# ... (15 total in the correct positions)
```

### Step 2: Gather XP
```
# Methods to get XP:
# 1. Kill mobs (hostile mobs give most XP)
# 2. Mine certain ores (coal, diamond, emerald, lapis, redstone, quartz)
# 3. Smelt items (any smelting gives small XP)
# 4. Trade with villagers
# 5. Bottle o' enchanting (creative/trade)

# Best mob XP farm: Zombie/skeleton spawner
# Setup a spawner grinder for passive XP
scan_blocks("spawner", range=32)

# Or just kill mobs at night
while xp_level < 30:
    mob = find_nearest("hostile", range=16)
    if mob:
        melee_attack(target=mob)
    else:
        wait(1)
```

### Step 3: Enchant an Item
```
# Open the enchanting table
interact_at(x=0, y=0, z=0)

# Place the item in the left slot
# Place lapis lazuli in the right slot (1-3 consumed)

# Three enchantment options appear:
# - Top: lowest level requirement (worst enchantments)
# - Middle: medium level requirement
# - Bottom: highest level requirement (best enchantments)

# To get the best enchantments, you need:
# - 15 bookshelves around the table
# - Level 30 (for the bottom option)
# - 3 lapis lazuli (for the bottom option)

# Select the bottom (best) option
enchant(item="diamond_sword", option="bottom")  # Costs 3 lapis + 30 levels
```

### Step 4: Use the Anvil for Combined Enchanting
The anvil lets you combine enchanted books with items, or two items together.

```
# Craft anvil
craft("anvil", count=1)  # 3 iron blocks + 4 iron ingots

# Combine enchanted book with item
# Place item in left slot, enchanted book in right slot
interact_at(x=anvil_x, y=anvil_y, z=anvil_z)
combine("diamond_sword", "sharpness_v_book")  # Costs XP levels

# Anvil cost rules:
# - Each combination has an "anvil cost" that increases
# - If the cost exceeds 40 levels, "Too Expensive!" — can't combine
# - Renaming items costs 1 level (resets the anvil cost penalty slightly)
```

## Key Enchantments Reference

### Sword Enchantments
| Enchantment | Max Level | Effect | Priority |
|-------------|-----------|--------|----------|
| Sharpness | V | +1.25 damage per level | Best for general use |
| Smite | V | +2.5 vs undead | Vs zombies/skeletons |
| Bane of Arthropods | V | +2.5 vs arthropods | Niche |
| Fire Aspect | II | Sets target on fire | Great for mobs |
| Looting | III | +1 drop per level | Essential for farming |
| Knockback | II | +3 blocks push per level | Usually bad |
| Sweeping Edge | III | AoE attack damage | Crowd control |
| Unbreaking | III | Durability lasts longer | Always good |
| Mending | I | Repairs with XP orb | Best repair |

### Armor Enchantments
| Enchantment | Max Level | Effect | Priority |
|-------------|-----------|--------|----------|
| Protection | IV | +4% reduction per level | Best general |
| Blast Protection | IV | Vs explosions + knockback | Vs creepers |
| Fire Protection | IV | Vs fire + reduced burn time | Vs Nether |
| Projectile Protection | IV | Vs arrows/ghast balls | Vs skeletons |
| Feather Falling | IV | Reduces fall damage | Boots only |
| Thorns | III | Damage attacker | High anvil cost |
| Unbreaking | III | Durability bonus | Always |
| Mending | I | XP repair | Always |
| Aqua Affinity | I | Mine underwater at normal speed | Helmet |
| Respiration | III | +15s underwater per level | Helmet |
| Depth Strider | III | Move faster in water | Boots |
| Frost Walker | II | Freeze water under you | Boots |
| Soul Speed | III | Walk fast on soul sand | Boots |

### Tool Enchantments
| Enchantment | Max Level | Effect | Priority |
|-------------|-----------|--------|----------|
| Efficiency | V | +1 mining speed per level | Essential |
| Fortune | III | +chance of extra drops | For ores |
| Silk Touch | I | Drops block itself | Incompatible with Fortune |
| Unbreaking | III | Durability bonus | Always |
| Mending | I | XP repair | Best repair |

### Bow Enchantments
| Enchantment | Max Level | Effect | Priority |
|-------------|-----------|--------|----------|
| Power | V | +25% arrow damage per level | Essential |
| Punch | II | +knockback on hit | Good |
| Flame | I | Arrow sets target on fire | Good |
| Infinity | I | Only need 1 arrow | Incompatible with Mending |
| Unbreaking | III | Durability bonus | Good with Infinity |

## Tool Usage Examples

### Optimal Diamond Sword
```
# Best sword: Sharpness V + Looting III + Mending + Unbreaking III
# Method 1: Enchanting table (get Sharpness V naturally at level 30)
enchant("diamond_sword")  # Hope for Sharpness V

# Method 2: Anvil + books (more controlled but expensive)
# Get enchanted books from villager librarians or fishing
combine("diamond_sword", "sharpness_v_book")     # Cost: ~5 levels
combine("result", "looting_iii_book")            # Cost: ~9 levels
combine("result", "mending_book")                # Cost: ~13 levels
combine("result", "unbreaking_iii_book")         # Cost: ~17 levels
# Total: ~44 levels — may be "Too Expensive!"
# Solution: use a second anvil or rename between combines
```

### Optimal Diamond Pickaxe
```
# Best mining pickaxe: Efficiency V + Fortune III + Mending + Unbreaking III
# Alternative: Silk Touch instead of Fortune

# For general mining:
combine("diamond_pickaxe", "efficiency_v_book")
combine("result", "fortune_iii_book")
combine("result", "mending_book")
combine("result", "unbreaking_iii_book")

# For silk touch (collecting ores/blocks as-is):
combine("diamond_pickaxe", "efficiency_v_book")
combine("result", "silk_touch_book")
combine("result", "mending_book")
```

### Villager Librarian Farming
```
# Best source of enchanted books: Librarian villagers
# 1. Place a lectern (librarian workstation)
build("lectern", x=0, y=0, z=0)

# 2. Trap a villager next to the lectern
# 3. Check their trade offers
trade_with("librarian")

# 4. If the enchanted book trade is bad, break the lectern
# 5. Place it again — villager re-rolls trades
# 6. Repeat until you get the book you want
# 7. Once you find Mending, NEVER break that lectern again!

# Mending book from librarian: costs ~10 emeralds
# This is the CHEAPEST way to get Mending
```

## Common Pitfalls

1. **Incompatible enchantments**: Protection variants conflict. Fortune + Silk Touch conflict. Infinity + Mending conflict. Check before combining.
2. **Too Expensive**: Anvil combinations above 40 levels fail. Plan your combine order to minimize cost.
3. **Wasting XP**: Don't enchant at low levels. Always use level 30 with 15 bookshelves for best results.
4. **Missing bookshelves**: Without 15 bookshelves, you can't access level 30 enchantments.
5. **Anvil breaking**: Anvils have durability and break after 25 uses. Keep backups.
6. **Random enchantments**: Enchanting table gives random enchantments. Use books + anvil for specific enchantments.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Obsidian | 4 | Enchanting table |
| Diamonds | 2 | Enchanting table |
| Books | 45+ | 1 for table, 3×15 for shelves |
| Leather | 45+ | For books |
| Sugar Cane | 135+ | For paper (3 per book) |
| Lapis Lazuli | 30+ | For enchanting |
| Iron Ingots | 31+ | For anvil (3 blocks + 4 ingots) |

## Safety Warnings
- **NEVER** put Thorns on all armor pieces — the anvil cost makes future repairs impossible
- **NEVER** waste Mending books on disposable items — save them for your best gear
- **ALWAYS** carry backup unenchanted tools — enchanted tools can still break (without Mending)
- **ALWAYS** rename items before the first combine — it reduces the anvil cost penalty
- **WATCH** your XP level — enchanting consumes 1-3 levels per operation
- The enchanting table interface is random — the same item at the same level can give different enchantments each time
