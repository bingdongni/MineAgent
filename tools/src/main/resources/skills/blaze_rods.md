# Blaze Rods

## Overview
Blaze rods are essential for brewing potions and crafting Eyes of Ender to locate strongholds. They are dropped by Blazes, hostile mobs found exclusively in Nether Fortresses. This skill covers finding a fortress, fighting blazes, and collecting rods efficiently.

## Prerequisites
- **Portal**: Must be in the Nether (see `nether_entry` skill)
- **Armor**: Full iron armor minimum, diamond preferred
- **Weapon**: Iron sword or better, bow with 64+ arrows
- **Food**: 32+ food items
- **Blocks**: 64+ cobblestone for bridges/shields
- **Optional**: Fire Resistance potion (makes this trivially easy)
- **Optional**: Snowballs (deal damage to blazes!)

## Step-by-Step Procedure

### Step 1: Find a Nether Fortress
Nether Fortresses contain blaze spawners and are the only source of blazes.

```
# Search for nether fortress structures
look_around(range=64)

# Nether fortresses are made of nether bricks (dark red/brown)
# They generate in "strips" — travel along X or Z axis to find them
# Fortresses are more common in certain biomes:
# - Nether Wastes (most common)
# - Soul Sand Valley (less common)

# Move along one axis to find fortress
goto(x=current_x + 100, y=current_y, z=current_z, goal_mode="xz")
look_around(range=64)
```

Tips for finding fortresses:
- Travel along the X or Z axis (fortresses generate in strips perpendicular to the axis)
- Look for nether brick structures — bridges, staircases, walls
- Fortresses are often at Y=60-80
- Use `/locate fortress` if commands are available

### Step 2: Locate the Blaze Spawner
Inside the fortress, find the blaze spawner room:

```
# Navigate through the fortress
# Blaze spawners are in rooms with nether brick floors and nether fences
# Look for the spinning cage entity

# Common fortress layout:
# - Corridors with nether brick arches
# - Blaze spawner rooms (small rooms with spawner + nether fence posts)
# - Wither skeleton areas (crossroads, larger rooms)
# - Staircases connecting levels

scan_blocks("spawner", range=32)
```

### Step 3: Prepare the Spawner Room
Before fighting, prepare the area:

```
# Build a 2-block-high ceiling to prevent blazes from floating up
# Place blocks around the spawner at Y+2 and Y+3
build("cobblestone", x=spawner_x, y=spawner_y+3, z=spawner_z)

# Create a 1-block gap to hit blazes from safety
# Build a wall with a slit:
build("cobblestone", x=spawner_x+2, y=spawner_y, z=spawner_z)
build("cobblestone", x=spawner_x+2, y=spawner_y+1, z=spawner_z)
# Leave y+2 empty for attack gap

# Place blocks behind you so you can't be pushed back
```

### Step 4: Fight the Blazes
```
# Wait for blazes to spawn (they spawn in groups of 1-4)
# When a blaze appears:

# Option A: Melee through gap
melee_attack(target="blaze")

# Option B: Ranged attack
ranged_attack(target="blaze", lead=0.5)

# Option C: Snowball barrage (snowballs deal 1 damage to blazes)
# This is very effective and cheap!
use_item_on("snowball", target="blaze")

# When blaze dies, collect the blaze rod
pickup_items(range=4)
```

Blaze behavior:
- Health: 20 (10 hearts)
- Melee damage: 6 (3 hearts)
- Fireball damage: 5 (2.5 hearts) + 5 seconds fire
- Spawn in groups of 1-4 per spawner activation
- Drop 1 rod per kill (affected by Looting: 0-1 extra per level)
- Vulnerable to snowballs (1 damage each)

### Step 5: Collect Rods and Retreat
```
# You need at least 6-12 blaze rods for progression:
# - 1 rod = blaze powder (crafting) + brewing stand
# - Each eye of ender = 1 blaze powder + 1 ender pearl
# - You need ~12-16 eyes of ender for the portal
# - So you need ~12-16 blaze rods minimum

# Craft blaze powder
craft("blaze_powder", count=12)  # 1 rod = 2 powder

# Craft brewing stand
craft("brewing_stand", count=1)  # 1 rod + 3 cobblestone

# Return to portal
goto(x=portal_x, y=portal_y, z=portal_z, goal_mode="xz")
```

## Tool Usage Examples

### Efficient Farming Loop
```
# Repeat this loop until you have enough rods
while rods_collected < target:
    # Wait for spawn
    wait(5)  # seconds
    
    # Check for blazes
    entities = look_around(range=8)
    blazes = filter(entities, type="blaze")
    
    for blaze in blazes:
        ranged_attack(target=blaze)
    
    pickup_items(range=6)
    rods_collected = count_item("blaze_rod")
```

### Emergency Fire Escape
```
# If on fire from blaze attack
# Water does NOT work in the Nether — it evaporates
# Options:
# 1. Fire Resistance potion (best)
use_item("fire_resistance_potion")

# 2. Milk bucket (clears fire effect)
use_item("milk_bucket")

# 3. Wait it out (5 seconds of fire = 4 hearts damage)
# Eat food to regenerate during fire
use_item("cooked_porkchop")
```

## Common Pitfalls

1. **Water in the Nether**: Water evaporates instantly in the Nether. You CANNOT use water to put out fires.
2. **Fire without resistance**: Without fire resistance, blaze fireballs deal 2.5 hearts + 4 hearts of fire damage = 6.5 hearts total. With iron armor, this is still 3-4 hearts.
3. **Blaze floating**: Blazes fly up when hit. Build a low ceiling to trap them.
4. **Multiple blazes**: Blaze spawners can produce 4 blazes at once. Don't get surrounded.
5. **Rods are not guaranteed**: Blazes have a drop chance for rods. With no Looting, it's not 100%. Keep killing.
6. **Wither skeletons**: Fortress corridors also contain wither skeletons. Don't get distracted — they inflict Wither effect.
7. **Looting doesn't help much**: Looting adds 0-1 extra rods per level, not guaranteed.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Bow + Arrows | 1 + 64+ | Ranged combat preferred |
| Iron Armor+ | Full set | Diamond preferred |
| Food | 32+ | Cooked meat for saturation |
| Cobblestone | 64+ | For fortification |
| Snowballs | 128+ | Cheap and effective vs blazes |
| Fire Resistance | 1+ potions | If available, makes it trivial |

## Safety Warnings
- **NEVER** fight blazes without fire resistance if you're new to the Nether
- **NEVER** rely on water in the Nether — it does not work
- **ALWAYS** have a retreat path planned back to your portal
- **ALWAYS** build cover before engaging the spawner
- **WATCH** your health — fire damage continues after the initial hit
- Blaze spawners can activate even if you're 16 blocks away
- Wither skeletons in corridors can inflict the Wither effect (damage over time, heals undead)
- Piglins may attack if they see you fighting near them (distress calls)
