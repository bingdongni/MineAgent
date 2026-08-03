# End Game Overview

## Overview
The End Game is the culmination of Minecraft's progression. After defeating the Ender Dragon, a new world of possibilities opens: elytra, shulker boxes, end cities, and the outer End islands. This skill provides a high-level roadmap for the entire end-game journey.

## Prerequisites
- All skills leading to dragon combat completed
- End portal activated and dragon defeated
- Diamond or netherite equipment

## Progression Roadmap

### Phase 1: Pre-End (Steps 1-5)
The journey to the End requires sequential progress:

```
# 1. Establish base and gather iron
load_skill("tier_progression")  # Get to iron tier

# 2. Enter the Nether
load_skill("nether_entry")

# 3. Get blaze rods
load_skill("blaze_rods")
# Target: 16+ blaze rods

# 4. Get ender pearls
load_skill("ender_pearls")
# Target: 20+ ender pearls

# 5. Find stronghold and activate portal
load_skill("stronghold_finding")
# Craft eyes of ender, find and fill portal
```

### Phase 2: Dragon Fight (Step 6)
```
# 6. Defeat the Ender Dragon
load_skill("dragon_combat")
# Target: Dragon defeated, exit portal active
```

### Phase 3: Post-Dragon (Steps 7-10)
After the dragon, new opportunities open:

```
# 7. Get the Dragon Egg
# The egg appears on the exit portal
# Use a piston or torch trick to collect it

# 8. Explore Outer End Islands
# Build a bridge ~1000 blocks from the main island
# Or throw an ender pearl through the exit portal gateway
build("cobblestone", x=1000, y=60, z=0)  # Bridge outward

# 9. Find an End City
# End cities contain:
# - Shulker boxes (infinite storage!)
# - Elytra (flight! in the ship at the top)
# - Loot (diamond tools, enchanted iron armor)
scan_blocks("purpur_block", range=64)  # End city material

# 10. Get Elytra
# The elytra is in the ship floating next to the end city
# Navigate to the ship (careful — void below!)
# Open the item frame and take the elytra
interact_at(x=ship_x, y=ship_y, z=ship_z)
```

### Phase 4: Endgame (Steps 11-15)
```
# 11. Enchant Elytra with Unbreaking III
load_skill("enchanting")
# Elytra + Unbreaking III = very long flight time

# 12. Farm Shulker Shells
# Kill shulkers in end cities
# 2 shells = 1 shulker box
# Shulker boxes are the best storage in the game

# 13. Upgrade to Netherite
# Netherite is better than diamond in every way
# 1 netherite ingot + diamond item = netherite item
# Netherite: more damage, more durability, knockback resistant, fire resistant

# 14. Optional: Summon Wither
# Wither = second boss, drops nether star for beacon
# Beacon = powerful area buff (speed, haste, resistance, etc.)

# 15. Optional: Respawn Dragon
# Place 4 end crystals on the exit portal edges
# Dragon respawns — you can fight it again for 500 XP
```

## Complete Timeline

| Phase | Step | Time Estimate | Key Loot |
|-------|------|---------------|----------|
| Pre-End | Iron gear | 30 min | Iron armor + tools |
| Pre-End | Nether portal | 10 min | Nether access |
| Pre-End | Blaze rods | 20 min | 16+ blaze rods |
| Pre-End | Ender pearls | 30 min | 20+ ender pearls |
| Pre-End | Stronghold | 20 min | Activated portal |
| Dragon | Dragon fight | 15 min | 12000 XP, dragon egg |
| Post | Bridge to outer End | 10 min | End city access |
| Post | End city + Elytra | 20 min | Elytra, shulker boxes |
| Endgame | Enchant Elytra | 15 min | Unbreaking III Elytra |
| Endgame | Netherite upgrade | 60 min | Netherite gear |
| Endgame | Wither + Beacon | 30 min | Nether star, beacon |

## Tool Usage Examples

### Full Speedrun Sequence
```
# This is the minimum sequence to reach the End
# Time: ~90 minutes for experienced AI

# 1-3. Get iron gear
load_skill("tier_progression")
mine_until("iron_ingot", count=31)  # Full armor + sword + pickaxe
craft("iron_armor", count=4)
craft("iron_sword", count=1)
craft("iron_pickaxe", count=1)
equip_all()

# 4. Get diamond pickaxe (for obsidian)
mine_until("diamond", count=3)
craft("diamond_pickaxe")

# 5. Enter Nether
load_skill("nether_entry")
# ... build portal, enter

# 6-7. Get blaze rods + ender pearls
load_skill("blaze_rods")
load_skill("ender_pearls")

# 8-9. Find stronghold + defeat dragon
load_skill("stronghold_finding")
load_skill("dragon_combat")
```

### Post-Dragon Elytra Run
```
# After dragon is defeated
# 1. Throw ender pearl through gateway
equip("ender_pearl")
use_item()  # Aim at the gateway portal

# 2. Find end city
scan_blocks("purpur_block", range=128)

# 3. Navigate to city ship (has elytra)
goto(x=ship_x, y=ship_y, z=ship_z, goal_mode="xz")

# 4. Get elytra from item frame
interact_at(x=frame_x, y=frame_y, z=frame_z)

# 5. Equip and fly back to main island
equip("elytra")
# Jump off and glide back
```

## Common Pitfalls

1. **Skipping steps**: You MUST follow the progression. You can't fight blazes without entering the Nether first.
2. **Under-preparing**: Each phase requires specific gear. Don't skip iron armor for diamond — iron is faster to get.
3. **Losing the portal**: Always mark your portals. The return path is critical.
4. **Dying in the End**: No respawn point in the End. You go back to overworld spawn.
5. **Void death**: Items lost to the void are PERMANENTLY gone. No recovery.
6. **Rushing dragon**: Without proper preparation (arrows, armor, blocks), the dragon fight is very hard.

## Resource Requirements
Cumulative resources for the full journey:
| Resource | Amount | Used In |
|----------|--------|---------|
| Iron Ingots | 31+ | Armor, tools, anvil |
| Diamonds | 3+ | Pickaxe for obsidian |
| Obsidian | 10+ | Nether portal |
| Blaze Rods | 16+ | Eyes of ender, brewing |
| Ender Pearls | 20+ | Eyes of ender |
| Food | 128+ | All phases |
| Cobblestone | 256+ | Building, bridging |

## Safety Warnings
- **NEVER** enter the End without at least 12 eyes of ender for the portal
- **NEVER** fight the dragon without diamond armor — iron is too weak
- **ALWAYS** have a clear retreat plan at every stage
- **ALWAYS** store important items in a chest at your base before risky endeavors
- **WATCH** your durability — broken armor mid-fight is deadly
- The End has no day/night cycle — endermen spawn constantly
- Netherite items float in lava but still burn — fire resistance is still valuable
