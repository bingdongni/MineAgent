# Nether Entry

## Overview
Entering the Nether is a critical milestone for progression. The Nether provides access to blaze rods, netherite, wither skeletons, and end-game materials. This skill covers building a nether portal, entering safely, and establishing a foothold.

## Prerequisites
- **Equipment**: At least iron armor and iron pickaxe
- **Supplies**: 10+ obsidian blocks (or a water bucket + lava source), flint and steel
- **Food**: At least 20 food items (cooked meat preferred)
- **Optional**: Fire Resistance potion, stack of blocks for bridging

## Step-by-Step Procedure

### Step 1: Gather Obsidian
If you already have obsidian, skip to Step 2. Otherwise:

```
# Find a lava pool (often at Y=-50 to Y=10 in overworld)
scan_blocks("lava", range=32)

# Place water next to lava to convert it to obsidian
# Use a DIAMOND pickaxe — iron cannot mine obsidian
use_item_on("diamond_pickaxe", target="obsidian_block")
```

- You need 10 obsidian for a minimum portal (4x5 frame, corners optional)
- Full portal: 14 obsidian (4x5 with corners)
- Mining obsidian takes 9.4 seconds with diamond pickaxe

### Step 2: Build the Portal Frame
The portal frame must be 4 wide × 5 tall (interior 2×3):

```
# Build portal frame at your current position
# Layout (side view, X = obsidian, . = air):
# X X X X
# X . . X
# X . . X
# X . . X
# X X X X

# Build bottom row
build("obsidian", x=0, y=0, z=0)
build("obsidian", x=1, y=0, z=0)
build("obsidian", x=2, y=0, z=0)
build("obsidian", x=3, y=0, z=0)

# Build left pillar
build("obsidian", x=0, y=1, z=0)
build("obsidian", x=0, y=2, z=0)
build("obsidian", x=0, y=3, z=0)

# Build right pillar
build("obsidian", x=3, y=1, z=0)
build("obsidian", x=3, y=2, z=0)
build("obsidian", x=3, y=3, z=0)

# Build top row
build("obsidian", x=0, y=4, z=0)
build("obsidian", x=1, y=4, z=0)
build("obsidian", x=2, y=4, z=0)
build("obsidian", x=3, y=4, z=0)
```

### Step 3: Light the Portal
```
# Equip flint and steel
equip("flint_and_steel")

# Use it on the portal interior
interact_at(x=1, y=1, z=0)  # Or any interior block of the frame
```

The portal should turn purple with particles. If it doesn't, check:
- Frame shape is correct (4×5 outer dimensions)
- All frame blocks are obsidian
- No blocks blocking the interior

### Step 4: Enter the Nether
```
# Walk into the portal
goto(x=1, y=1, z=0, goal_mode="xz")

# Wait for loading screen (max 30 seconds in vanilla)
# You will appear in the Nether at the linked portal
```

### Step 5: Secure the Foothold
Immediately upon arrival:

```
# Check surroundings
look_around(range=16)

# Build a shelter around the portal
# Place cobblestone around the portal frame
build("cobblestone", x=-1, y=0, z=-1)
build("cobblestone", x=-1, y=1, z=-1)
build("cobblestone", x=-1, y=2, z=-1)
# ... continue enclosing the portal

# Place a chest for supplies
build("chest", x=-2, y=0, z=0)
```

## Tool Usage Examples

### Quick Portal with Water Bucket Method
```
# Find a lava pool
scan_blocks("lava", range=32)

# Place water at one edge of the lava pool
# This converts lava to obsidian automatically
use_item_on("water_bucket", target="lava_edge")

# Mine out the water source
use_item_on("bucket", target="water_source")

# Repeat until you have the portal shape
```

### Emergency Nether Escape
```
# If overwhelmed by mobs, retreat to portal
goto(x=portal_x, y=portal_y, z=portal_z, goal_mode="xz")

# If portal is destroyed, rebuild quickly
# You ALWAYS have 10 obsidian + flint_and_steel in your inventory, right?
build("obsidian", ...)  # Rebuild frame
equip("flint_and_steel")
interact_at(...)  # Relight
```

## Common Pitfalls

1. **Iron pickaxe on obsidian**: Iron CANNOT mine obsidian. You need diamond. The block will break but drop nothing.
2. **Wrong portal dimensions**: Must be 4×5 outer (2×3 inner). Other sizes don't work.
3. **Nether spawning on lava**: Nether portals can generate over lava oceans. Always bring blocks to bridge.
4. **Ghast fireballs**: Ghasts can destroy your portal (they break the obsidian? No — they can't. But they can hit you!). Actually, ghasts CANNOT break obsidian, so your portal is safe.
5. **Lost portal**: Mark the portal coordinates. In the Nether, coordinates are 1/8 of overworld. If you lose your portal, build a new one and it should link back.
6. **Sleeping in the Nether**: Beds EXPLODE in the Nether. Do NOT use them. Use a respawn anchor instead (requires crying obsidian + glowstone).

## Resource Requirements
| Resource | Amount | Source |
|----------|--------|--------|
| Obsidian | 10-14 | Lava + water, or mining |
| Flint & Steel | 1 | Flint + iron ingot |
| Diamond Pickaxe | 1 | 3 diamonds + 2 sticks |
| Cobblestone | 20+ | Mining, for shelter |
| Food | 20+ | Cooked meat |
| Blocks | 64+ | For bridging over lava |

## Safety Warnings
- **NEVER** enter the Nether without a diamond pickaxe — you may not be able to get back
- **NEVER** place a bed in the Nether — it will explode with lethal force
- **ALWAYS** mark your portal with torches or distinctive blocks
- **ALWAYS** bring fire resistance if available — lava is everywhere
- **WATCH** for lava below you when mining — the Nether has hidden lava pools
- **CARRY** blocks at all times — you may need to bridge over lava oceans instantly
- Ghast fireballs deal 6 hearts of damage on direct hit
- Blaze fireballs set you on fire for 5 seconds
- Piglins attack if you open chests near them without gold armor
