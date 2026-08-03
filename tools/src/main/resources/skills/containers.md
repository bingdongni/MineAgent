# Containers

## Overview
Containers are storage blocks that hold items: chests, barrels, shulker boxes, hoppers, droppers, and dispensers. This skill covers using each type, organizing storage, and building automated item transfer systems.

## Prerequisites
- Basic crafting ability (planks, iron ingots)
- Understanding of block placement and interaction

## Container Types

### Chest
- **Capacity**: 27 slots (9×3)
- **Double chest**: 54 slots (two chests placed adjacent)
- **Can be trapped**: Trapped chest emits redstone signal when opened
- **Crafting**: 8 planks → 1 chest

```
# Craft and place a chest
craft("chest", count=1)
build("chest", x=pos_x, y=pos_y, z=pos_z)

# Open the chest
interact_at(x=pos_x, y=pos_y, z=pos_z)

# Place items in chest
# (Items in hand move to chest on shift-click)
deposit("iron_ingot", count=32)

# Create a double chest by placing adjacent
build("chest", x=pos_x+1, y=pos_y, z=pos_z)  # Merges into double chest
```

### Barrel
- **Capacity**: 27 slots (same as chest)
- **Can be opened with block above**: Unlike chests, barrels work even with a block on top
- **Crafting**: 8 planks + 1 slab → 1 barrel
- **Villager workstation**: Fisherman

```
craft("barrel", count=1)
build("barrel", x=pos_x, y=pos_y, z=pos_z)
# Advantage: can place blocks on top and still open
build("stone", x=pos_x, y=pos_y+1, z=pos_z)  # Still works!
```

### Shulker Box
- **Capacity**: 27 slots
- **Breaks with items inside**: Drops as an item containing its contents
- **Dyed colors**: 16 colors available
- **Crafting**: 2 shulker shells + 1 chest → 1 shulker box
- **Best for**: Portable storage, inventory expansion

```
# Shulker shells come from killing shulkers (End cities)
craft("shulker_box", count=1)

# Fill it with items
interact_at(x=shulker_x, y=shulker_y, z=shulker_z)
deposit("diamond", count=64)

# Break it — it drops with items inside!
use_item_on("pickaxe", target="shulker_box")
# Pick up the shulker box item — it contains your diamonds!

# Place it again later
build("shulker_box", x=new_x, y=new_y, z=new_z)
# Items are still inside!
```

### Hopper
- **Capacity**: 5 slots
- **Pulls from above**: Automatically pulls items from container above
- **Pushes to side/below**: Pushes items to container it's pointing at
- **Crafting**: 5 iron ingots + 1 chest → 1 hopper
- **Key use**: Item sorting, automation

```
craft("hopper", count=1)

# Place hopper under a chest
build("chest", x=0, y=1, z=0)
build("hopper", x=0, y=0, z=0)

# Items dropped on the chest will be pulled into the hopper
# Hopper transfers 1 item every 8 redstone ticks (0.4 seconds)

# Hopper minecart for rail-based item transport
craft("hopper_minecart", count=1)
```

### Dropper
- **Capacity**: 9 slots
- **Drops items**: When powered, ejects one item as a dropped item entity
- **Random slot**: Picks a random slot to drop from
- **Crafting**: 7 cobblestone + 1 redstone → 1 dropper

```
craft("dropper", count=1)
build("dropper", x=0, y=1, z=0, facing="down")

# Power with redstone to drop an item
build("button", x=0, y=1, z=1)
interact_at(x=0, y=1, z=1)  # Presses button, dropper drops item
```

### Dispenser
- **Capacity**: 9 slots
- **Dispenses items**: Uses items based on type (shoots arrows, places blocks, etc.)
- **Crafting**: 7 cobblestone + 1 bow + 1 redstone → 1 dispenser

```
# Dispenser behavior depends on item:
# Arrow → shoots like a skeleton
# Fire charge → shoots like a blaze
# Water bucket → places water
# TNT → places and ignites TNT
# Flint & steel → ignites block in front

craft("dispenser", count=1)
build("dispenser", x=0, y=1, z=0, facing="south")

# Load with arrows
interact_at(x=0, y=1, z=0)  # Open dispenser
deposit("arrow", count=64)

# Power to shoot
build("button", x=0, y=1, z=-1)
interact_at(x=0, y=1, z=-1)  # Shoots an arrow south
```

## Tool Usage Examples

### Organized Storage System
```
# Build a categorized storage wall
# Each double chest stores one category

# Row 1: Building blocks
build("chest", x=0, y=0, z=0)    # Cobblestone/stone
build("chest", x=2, y=0, z=0)    # Wood/planks
build("chest", x=4, y=0, z=0)    # Sand/gravel/dirt

# Row 2: Ores and ingots
build("chest", x=0, y=0, z=2)    # Iron/gold
build("chest", x=2, y=0, z=2)    # Diamond/emerald
build("chest", x=4, y=0, z=2)    # Redstone/lapis/coal

# Row 3: Combat and tools
build("chest", x=0, y=0, z=4)    # Weapons/armor
build("chest", x=2, y=0, z=4)    # Tools
build("chest", x=4, y=0, z=4)    # Potions/food

# Sign each chest for identification
build("oak_sign", x=0, y=1, z=0)
write_sign("Building Blocks - Stone")
```

### Simple Item Sorter
```
# Hopper-based item sorter
# Uses the "filter" trick: named item in hopper's first slot

# Sorter for diamonds:
# 1. Hopper pointing into storage chest
build("hopper", x=0, y=0, z=0, facing="south")
# 2. Put 1 named diamond in slot 1 (filter)
deposit("diamond", slot=1, count=1)  # Named "Diamond"
# 3. Put 1 of any junk in other slots to block them
deposit("stone", slot=2, count=1)
deposit("stone", slot=3, count=1)
deposit("stone", slot=4, count=1)
deposit("stone", slot=5, count=1)
# 4. Only diamonds can pass through (they stack with the filter)
```

### Auto-Smelting System
```
# Hopper-fed furnace system

# Layout:
# [Chest: input] → [Hopper] → [Furnace] → [Hopper] → [Chest: output]

build("chest", x=0, y=1, z=0)     # Input chest (items to smelt)
build("hopper", x=0, y=0, z=0)    # Feeds into furnace top
build("furnace", x=0, y=-1, z=0)  # Furnace
build("hopper", x=0, y=-2, z=0)  # Pulls from furnace bottom
build("chest", x=0, y=-3, z=0)   # Output chest

# Also need fuel hopper from side:
build("hopper", x=-1, y=-1, z=0)  # Feeds fuel from side
build("chest", x=-2, y=-1, z=0)  # Fuel chest (coal/wood)
```

## Common Pitfalls

1. **Chest blocked above**: A chest CANNOT be opened if there's a block directly above it. Use barrels instead.
2. **Double chest limit**: Only two chests can combine. Three in a row = two separate chests.
3. **Hopper locked**: A hopper with all 5 slots filled with different 1-stack items acts as a filter but also gets "locked" — it won't pull new items unless they match.
4. **Shulker box in shulker box**: You CANNOT put a shulker box inside another shulker box. This prevents infinite storage exploits.
5. **Ender chest**: Ender chests are shared across all players and dimensions. Useful for accessing your base storage from anywhere.
6. **Hopper performance**: Too many hoppers (hopper chains) can cause lag. Limit chains to 8-10 hoppers.

## Resource Requirements
| Container | Materials | Notes |
|-----------|-----------|-------|
| Chest | 8 planks | Most basic storage |
| Barrel | 8 planks + 1 slab | Works with block above |
| Shulker Box | 2 shells + 1 chest | Portable, drops with items |
| Hopper | 5 iron + 1 chest | Auto item transfer |
| Ender Chest | 8 obsidian + 1 eye | Shared dimension storage |
| Dropper | 7 cobble + 1 redstone | Drops items |
| Dispenser | 7 cobble + 1 bow + 1 redstone | Uses items |

## Safety Warnings
- **NEVER** store all your valuables in one chest — spread them across multiple containers
- **NEVER** leave valuable chests unclaimed — other players (or mobs) can break them
- **ALWAYS** use an ender chest for your most critical items — it's accessible from anywhere
- **WATCH** for trapped chests — they look identical to regular chests but emit redstone signals
- Hoppers can be locked by powering them with redstone — useful for controlling item flow
- Shulker boxes destroyed by lava, fire, or cacti drop their contents as items on the ground
- Ender chests are blast-resistant but CAN be broken by pickaxes (drop as obsidian)
