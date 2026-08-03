# Ender Pearls

## Overview
Ender pearls are required for crafting Eyes of Ender (stronghold locator + portal filler) and for emergency teleportation. They are dropped by Endermen when killed. This skill covers finding endermen, fighting them efficiently, and collecting pearls.

## Prerequisites
- **Armor**: Full iron armor minimum (endermen deal 7 damage)
- **Weapon**: Iron sword or better
- **Water bucket**: CRITICAL — endermen are teleported away by water and cannot teleport while in contact with water
- **Food**: 20+ food items
- **Blocks**: 32+ for building 2-block-high shelters

## Step-by-Step Procedure

### Step 1: Find Endermen
Endermen spawn at night in the Overworld or anywhere in the End.

```
# At night, endermen spawn in overworld (need light level < 7)
# Best biomes: Warped Forest in Nether (most endermen)
# Overworld: any dark area at night

# In Overworld at night:
look_around(range=32)
# Filter for enderman entities

# In Nether Warped Forest (best farm):
scan_blocks("warped_stem", range=32)  # Find warped forest biome
```

Spawn rates by location:
- **Warped Forest (Nether)**: Highest spawn rate, best for farming
- **Overworld night**: Moderate, but easy to access
- **The End**: Constant spawns, but dangerous without gear

### Step 2: Aggro the Enderman
Endermen are neutral until provoked. Ways to aggro:

```
# Method 1: Look at their eyes (look directly at them for 2+ seconds)
# The enderman will open its mouth and shake — then attack

# Method 2: Attack them directly
melee_attack(target="enderman")

# Method 3: They aggro if you look at them while within 64 blocks
# Just standing near them and looking works
```

### Step 3: Fight the Enderman
Endermen are tricky because they teleport when hit by projectiles.

```
# CRITICAL RULE: Endermen teleport away from arrows/snowballs/tridents
# You MUST use melee attacks

# Strategy A: Water bucket trap (safest)
# 1. Aggro the enderman
# 2. Place water at your feet
use_item_on("water_bucket", target="self_pos")
# 3. The enderman teleports into the water and takes damage
# 4. It cannot teleport away while in water
# 5. Hit it repeatedly
melee_attack(target="enderman")
# 6. Collect water when done
use_item_on("bucket", target="water_source")

# Strategy B: 2-block-high shelter
# 1. Build a 2-block-high roof over you (your height = 1.8, under 2-block gap)
build("cobblestone", x=pos_x, y=pos_y+2, z=pos_z)
build("cobblestone", x=pos_x+1, y=pos_y+2, z=pos_z)
# 2. Stand under it — endermen are 3 blocks tall, can't reach you
# 3. Hit the enderman from safety
melee_attack(target="enderman")

# Strategy C: Boat trap
# 1. Place a boat near the enderman
build("boat", x=enderman_x, y=enderman_y, z=enderman_z)
# 2. The enderman may walk into the boat and get trapped
# 3. Hit it while it's stuck in the boat
```

### Step 4: Collect the Pearl
```
# Endermen drop 0-1 ender pearls on death
# With Looting III: 0-4 pearls (each level adds 1 to max)
pickup_items(range=4)

# Count your pearls
get_self_status()  # Check inventory
```

### Step 5: Farm Multiple Endermen
You need approximately 12-20 ender pearls for the End portal.

```
# In Warped Forest (best method):
# 1. Build a 2-high platform
# 2. Wait for endermen to spawn
# 3. Aggro them one at a time
# 4. Kill with water bucket + sword
# 5. Repeat

while pearl_count < 20:
    enderman = find_nearest("enderman", range=32)
    if enderman:
        # Aggro
        look_at(enderman)
        # Kill with water trap
        use_item_on("water_bucket", target="self_pos")
        melee_attack(target=enderman)
        pickup_items(range=4)
        pearl_count = count_item("ender_pearl")
    else:
        wait(2)  # seconds
```

## Tool Usage Examples

### Emergency Ender Pearl Teleport
```
# Ender pearls can teleport you to where they land
# Costs 5 damage (2.5 hearts) from fall impact
# Use for escaping dangerous situations

# Throw pearl to safety
equip("ender_pearl")
use_item()  # Throws in look direction

# WARNING: You take 5 damage on landing
# Make sure you have enough health!
# Also: if pearl lands in unloaded chunk, it disappears
```

### Ender Pearl Stasis (Chorus Fruit Trick)
```
# Advanced: throw pearl into exit portal in End
# When you later jump into the portal, you teleport to where the pearl was
# This is a late-game trick for setting up teleport points
```

## Common Pitfalls

1. **Arrows don't work**: Endermen teleport away from ALL projectiles (arrows, snowballs, tridents, eggs, etc.). Only melee hits and water damage work.
2. **Enderman teleporting on hit**: Even melee hits can cause teleportation. They usually teleport 5-16 blocks away. Chase them down.
3. **Pearl teleportation damage**: Using an ender pearl deals 5 fall damage. Don't use when low health.
4. **Pearl into void**: Throwing a pearl into the void kills you — the teleport puts you in the void.
5. **Not enough pearls**: You need ~12-16 eyes of ender for the portal. Each eye = 1 pearl + 1 blaze powder. Get extras (20+) for thrown eyes that break.
6. **Fighting in the End**: In the End, enderman spawns are constant and aggressive. Don't look at them accidentally while fighting the dragon.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Sword | Iron+ | Melee only |
| Water Bucket | 1 | Critical for safe fighting |
| Armor | Full iron+ | Endermen deal 3.5 hearts |
| Food | 20+ | For healing |
| Cobblestone | 32+ | For 2-high shelters |
| Looting III Sword | Optional | 0-4 drops vs 0-1 |

## Safety Warnings
- **NEVER** look at an enderman you don't intend to fight (they aggro from 64 blocks)
- **NEVER** use ranged weapons on endermen — they teleport and the shot misses
- **ALWAYS** carry a water bucket when hunting endermen — it's your best defense
- **ALWAYS** ensure 6+ hearts before using an ender pearl for teleport
- **AVOID** fighting endermen in the End unless prepared — they swarm
- Endermen can pick up and move blocks (dirt, sand, gravel, etc.) — don't build your house out of these
- Endermen take damage from water and lava — use this to your advantage
- In the End, looking at an enderman's middle section (not eyes) does NOT aggro them — only the eyes
- Ender pearl teleportation can clip you into blocks and suffocate you
