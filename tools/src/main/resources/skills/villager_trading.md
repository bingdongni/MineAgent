# Villager Trading

## Overview
Villager trading is one of the most powerful mechanics in Minecraft. Villagers provide renewable resources, enchanted books, rare items, and emeralds in exchange for common goods. This skill covers trapping villagers, setting up workstations, and optimizing trades.

## Prerequisites
- **Workstations**: Various blocks (lectern, cauldron, etc.) — one per villager type
- **Emeralds**: Initial currency (from mining, selling, or raid drops)
- **Beds**: Each villager needs a bed to rest and restock trades
- **Building materials**: To enclose villagers safely

## Villager Professions

### Top-Tier Villagers (Get These First!)
| Profession | Workstation | Best Trades | Priority |
|------------|------------|-------------|----------|
| Librarian | Lectern | Enchanted books (Mending!) | ★★★★★ |
| Weaponsmith | Grindstone | Diamond sword/axe | ★★★★ |
| Toolsmith | Smithing Table | Diamond pickaxe/shovel | ★★★★ |
| Armorer | Blast Furnace | Diamond armor | ★★★★ |
| Cleric | Brewing Stand | Redstone, glowstone, ender pearls | ★★★ |

### Utility Villagers
| Profession | Workstation | Best Trades | Priority |
|------------|------------|-------------|----------|
| Farmer | Composter | Wheat/carrot/potato → emeralds | ★★★ |
| Fisherman | Barrel | Fishing rod, campfire | ★★ |
| Shepherd | Loom | Wool, banners | ★★ |
| Cartographer | Cartography Table | Explorer maps | ★★★ |
| Stone Mason | Stonecutter | Stone variants | ★★ |

### Basic Villagers
| Profession | Workstation | Best Trades | Priority |
|------------|------------|-------------|----------|
| Butcher | Smoker | Meat → emeralds | ★★ |
| Leatherworker | Cauldron | Leather → emeralds | ★ |
| Fletcher | Fletching Table | Arrows, bows | ★★ |

## Step-by-Step Procedure

### Step 1: Find or Create a Village
```
# Find a natural village
scan_blocks("bell", range=128)  # Villages always have a bell

# Or cure zombie villagers (best for discount prices):
# 1. Find a zombie villager
look_around(range=32)
# Filter for "zombie_villager"

# 2. Trap it (it will despawn if not named!)
build("cobblestone_enclosure", x= zv_x, y= zv_y, z= zv_z)
# Or use a name tag to prevent despawning
use_item_on("name_tag", target="zombie_villager")

# 3. Throw Splash Weakness potion
equip("splash_weakness_potion")
use_item()  # Aim at zombie villager

# 4. Feed golden apple
equip("golden_apple")
use_item_on("golden_apple", target="zombie_villager")

# 5. Wait 2-5 minutes (random)
# Zombie villager shakes and converts to normal villager
# Cured villagers have PERMANENT trade discounts!
```

### Step 2: Set Up the Trading Hall
```
# Build an enclosed trading hall
# Each villager needs:
# - 1 bed (to restock trades at dawn)
# - 1 workstation (to lock their profession)
# - Enclosure (to prevent wandering/despawning)

# Layout: 1-wide cells
# [Wall] [Bed] [Villager] [Workstation] [Wall]

# Build cell 1 (Librarian)
build("bed", x=0, y=0, z=0)
build("lectern", x=2, y=0, z=0)
# Enclose with walls
build("glass", x=1, y=0, z=-1)
build("glass", x=1, y=0, z=1)
build("glass", x=1, y=1, z=-1)
# ... etc

# Push villager into cell
# Villagers pathfind toward their workstation during work hours
```

### Step 3: Lock the Desired Profession
```
# Villagers choose their profession based on nearby workstations
# Place the workstation BEFORE the villager has access to it
# Then give the villager access — they lock to that profession

# Example: Create a Librarian
# 1. Place lectern
build("lectern", x=2, y=0, z=0)

# 2. Push villager near it
goto(x=1, y=0, z=0)  # Stand next to lectern

# 3. Villager links to lectern and becomes Librarian
# 4. Check their first enchanted book trade
trade_with("librarian", slot=1)

# 5. If the book is not what you want, BREAK the lectern
use_item_on("pickaxe", target="lectern")

# 6. Place the lectern again
build("lectern", x=2, y=0, z=0)

# 7. Villager re-rolls their trades
# 8. Repeat until you get Mending (or desired enchantment)
# CRITICAL: Only break lectern before you trade!
# Once you trade, the profession is LOCKED PERMANENTLY
```

### Step 4: Trade and Level Up
```
# Villagers have 5 trade levels: Novice → Apprentice → Journeyman → Expert → Master
# Each level unlocks better trades
# Level up by completing trades (each trade gives XP to the villager)

# Trade cycle:
trade_with("librarian", slot=1)  # Buy paper → emeralds
trade_with("librarian", slot=2)  # Sell emeralds → book
# ... repeat until leveled up

# Trade restocking:
# Villagers restock trades twice per day (at work hours)
# If trades are "locked out" (max uses reached), wait until dawn
# The villager must have access to their workstation to restock
wait_until_dawn()
trade_with("librarian")  # Trades restocked!
```

### Step 5: Optimize with Curing Discounts
```
# Curing a zombie villager gives PERMANENT discounts
# Each cure reduces prices by ~25-40%
# Multiple cures stack! (up to ~70% discount)

# With max discount:
# Mending book: ~10 emeralds → ~2-3 emeralds!
# Diamond armor: ~30 emeralds → ~5-10 emeralds!

# The discount applies to ALL trades, not just one
# This is incredibly powerful for mass trading
```

## Tool Usage Examples

### Iron Farm (Armorer/Weaponsmith)
```
# Armorers and weaponsmiths buy iron for emeralds
# At novice level: 4 iron → 1 emerald
# This is a way to convert iron to emeralds

# Set up iron golem farm → sell iron → buy diamond gear
trade_with("armorer", sell="iron_ingot", count=4)

# At expert/master level, armorers sell diamond armor
trade_with("armorer", buy="diamond_chestplate")
# Costs ~35 emeralds (less with curing discount!)
```

### Mending Book Farm
```
# Most important villager setup: Librarian with Mending
# 1. Trap a villager next to a lectern
# 2. Check first enchanted book trade
# 3. If not Mending, break and replace lectern
# 4. Repeat until Mending appears
# 5. Trade once to lock the profession
# 6. Level up to access the Mending trade

# Cost: ~10 emeralds per Mending book (less with discount)
# This is BY FAR the cheapest way to get Mending
# Compare: 30+ levels + lapis at enchanting table (random!)
```

### Emerald Generation
```
# Best ways to get emeralds from villagers:

# Method 1: Farmer buys wheat/carrots/potatoes
# Build a farm, sell crops → emeralds
build("wheat_farm", x=0, y=0, z=0)  # Auto-farm
harvest("wheat", count=20)
trade_with("farmer", sell="wheat", count=20)

# Method 2: Stone Mason buys stone
# You mine stone anyway — sell the cobblestone!
mine_until("cobblestone", count=64)
smelt("cobblestone", count=64)  # → stone
trade_with("stone_mason", sell="stone", count=10)

# Method 3: Shepherd buys wool
# Wool is easy to mass-produce with sheep
shear("sheep", count=16)
trade_with("shepherd", sell="white_wool", count=16)
```

## Common Pitfalls

1. **Breaking workstation after trading**: Once you trade with a villager, their profession is LOCKED. Breaking the workstation won't change it. It just prevents restocking!
2. **No bed**: Villagers need a bed to restock trades. Without a bed, trades never refresh.
3. **Despawning**: Unnamed villagers can despawn! Use name tags or enclose them securely.
4. **Wrong time**: Villagers only work during daytime (work hours). They won't restock at night.
5. **Gossip decay**: Trade discounts from curing slowly decay over time. They never fully disappear but decrease.
6. **Overcrowding**: Villagers need space. If too many are in a small area, they panic and won't work.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Workstations | 1 per villager | Profession-specific |
| Beds | 1 per villager | For restocking |
| Emeralds | 20+ starter | From mining/raids |
| Building blocks | 64+ | For enclosures |
| Name tags | Optional | Prevents despawning |

## Safety Warnings
- **NEVER** trade with a villager before checking if their offers are what you want — you'll lock their profession
- **NEVER** leave villagers unprotected at night — raids and zombies can kill them
- **ALWAYS** have a bed for each villager — without it, trades never restock
- **ALWAYS** enclose trading villagers — open-air villagers wander off or get killed
- **WATCH** for raid triggers — killing a patrol captain triggers a raid near your village!
- Zombie sieges can occur in large villages — build walls and iron golems for defense
- Villagers can't reach their workstation through walls — ensure line of sight or direct access
