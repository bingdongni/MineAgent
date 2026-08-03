# Stronghold Finding

## Overview
Strongholds contain the End Portal, the only way to reach the End and fight the Ender Dragon. This skill covers locating a stronghold using Eyes of Ender, navigating the stronghold, and finding the portal room.

## Prerequisites
- **Eyes of Ender**: 15-20 crafted (blaze powder + ender pearl each)
- **Armor**: Full iron minimum (silverfish attacks)
- **Weapon**: Iron sword or better
- **Pickaxe**: Iron pickaxe for breaking stone bricks
- **Food**: 32+ items
- **Blocks**: 64+ cobblestone for pathfinding
- **Torches**: 64+ for marking explored areas

## Step-by-Step Procedure

### Step 1: Craft Eyes of Ender
```
# Each eye of ender = 1 blaze powder + 1 ender pearl
craft("eye_of_ender", count=16)

# You need 12 to fill the portal, plus extras for throwing
# Recommended: 20+ total (some break when thrown)
```

### Step 2: Locate the Stronghold Direction
Eyes of Ender fly toward the nearest stronghold when thrown.

```
# Throw an eye and observe its direction
equip("eye_of_ender")
use_item()  # Throws in look direction

# Follow the direction the eye flies
# It flies for ~12 blocks then either:
#   - Drops (can be picked up, 80% chance) — keep following
#   - Shatters (20% chance, gone forever) — use another

# Record the direction and travel
# Throw another eye every ~100 blocks to correct course
goto(x=eye_target_x, y=current_y, z=eye_target_z, goal_mode="xz")
```

### Step 3: Triangulate the Position
For more efficient finding, use triangulation:

```
# Method: Two-throw triangulation
# 1. Throw eye at position A, record angle
# 2. Move perpendicular ~100 blocks
# 3. Throw eye at position B, record angle
# 4. The stronghold is where the two lines intersect

# Throw 1
pos_a = get_self_status().position
throw_eye()  # Record angle_a

# Move perpendicular
goto(x=pos_a.x + 100, y=pos_a.y, z=pos_a.z)

# Throw 2
pos_b = get_self_status().position
throw_eye()  # Record angle_b

# Calculate intersection
# stronghold ≈ intersection of the two bearing lines
# Navigate to calculated position
goto(x=calc_x, y=calc_y, z=calc_z, goal_mode="xz")
```

### Step 4: Find the Stronghold Entrance
Strongholds are underground, usually at Y=-40 to Y=10.

```
# When the eye flies downward and hovers, you're above the stronghold
# It hovers at the portal room location

# Dig down to find the stronghold
# Start at Y=0 and look for stone bricks
goto(x=target_x, y=0, z=target_z)

# Search for stronghold blocks
scan_blocks("stone_bricks", range=32)
scan_blocks("infested_stone_bricks", range=32)  # Silverfish blocks!

# Or use the eye's hover point:
# When eye floats straight down and hovers, dig down at that spot
dig_down(y_from=0, y_to=-40)  # Careful!
```

### Step 5: Navigate the Stronghold
Strongholds are mazes of corridors, rooms, and staircases.

```
# Mark your path with torches (always on the RIGHT wall)
# This ensures you can find your way back

# Enter the stronghold
look_around(range=16)

# Navigate through corridors
# Stronghold rooms:
# - Corridor: long passages with stone brick
# - Crossing: intersections with multiple paths
# - Library: rooms with bookshelves and cobwebs
# - Portal Room: the room with the End Portal frame
# - Prison: small rooms with iron bars
# - Stairway: connecting different levels

# At each intersection, mark the right wall with a torch
build("torch", x=right_wall_x, y=right_wall_y, z=right_wall_z)
```

### Step 6: Find the Portal Room
```
# The portal room is distinctive:
# - Contains the End Portal frame (bedrock-like blocks with green particles)
# - Has a silverfish spawner in the center
# - Stone brick platform over a lava pool

# Keep exploring until you find it
# If the eye hovers in a specific direction, go that way

# When you find the portal room:
look_around(range=8)  # Look for portal frame blocks
scan_blocks("end_portal_frame", range=16)
```

### Step 7: Fill the Portal
```
# The portal frame has 12 slots
# Some may already have eyes (random, 0-12 pre-filled)
# Fill the remaining slots

# Count empty slots
empty_slots = count_empty_portal_frames()

# Place eyes in each empty slot
for each empty_slot:
    equip("eye_of_ender")
    use_item_on("eye_of_ender", target=empty_slot)

# When all 12 are filled, the portal activates (black with stars)
# Jump in!
goto(x=portal_x, y=portal_y, z=portal_z)
```

## Tool Usage Examples

### Efficient Eye Throwing
```
# Minimize wasted eyes by only throwing when direction changes
last_direction = None
while not near_stronghold:
    direction = throw_eye_and_measure()
    if direction != last_direction:
        # Significant direction change — we're close
        navigate(direction)
        last_direction = direction
    else:
        # Same direction — travel far before re-throwing
        move_forward(100)
```

### Silverfish Handling
```
# Silverfish hide in "infested" stone blocks
# When you break one, a silverfish spawns
# CRITICAL: If you hit a silverfish, it calls ALL nearby silverfish

# Best approach:
# 1. Don't break suspicious stone bricks
# 2. If silverfish spawns, kill it FAST before it calls others
# 3. Use a sword — one-hit kill if possible

melee_attack(target="silverfish")  # Kill in one hit!
```

## Common Pitfalls

1. **Eye shattering**: Eyes of Ender have a 20% chance to break each throw. Bring extras.
2. **Multiple strongholds**: There are multiple strongholds (up to 128 in 1.19+). The eye always points to the nearest one.
3. **Silverfish swarms**: Breaking infested blocks spawns silverfish that call reinforcements. Don't panic — back into a corridor and fight one at a time.
4. **Getting lost**: Strongholds are mazes. ALWAYS mark your path with torches.
5. **Portal room behind locked door**: Sometimes the portal room is blocked by iron bars. Break the bars with a pickaxe.
6. **Pre-filled eyes**: The portal may have 0-12 eyes already filled. Don't waste eyes overfilling.
7. **Stronghold at wrong Y**: Eyes hover at the portal room Y, which may be far from the entrance. Dig carefully.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Eyes of Ender | 20+ | 12 for portal, 8+ for navigation |
| Torches | 64+ | For marking path |
| Pickaxe | Iron+ | For breaking stone bricks/bars |
| Cobblestone | 64+ | For bridging/blocking |
| Food | 32+ | Silverfish encounters |
| Sword | Iron+ | For silverfish |

## Safety Warnings
- **NEVER** dig straight down — you may fall into lava or the void
- **NEVER** break stone bricks carelessly — they might be infested with silverfish
- **ALWAYS** mark your path — getting lost in a stronghold is deadly
- **ALWAYS** carry extra eyes — they break randomly when thrown
- **WATCH** for lava in the portal room — there's often a lava pool below the portal
- Silverfish swarms can overwhelm even diamond armor — kill them quickly one at a time
- The stronghold is at Y=-40 to Y=10 approximately — watch for deep lava
- If the eye hovers and you can't find the portal, you may be in the wrong section of the stronghold — keep exploring
