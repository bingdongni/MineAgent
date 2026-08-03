# World Atlas

## Overview
The Minecraft world is divided into biomes and structures, each with unique resources, terrain, and mob spawns. This skill serves as an atlas — a reference guide to all biomes, structures, and what you can find in each.

## Prerequisites
- Understanding of coordinates (X, Y, Z)
- Basic exploration ability

## Overworld Biomes

### Temperate Biomes
| Biome | Resources | Structures | Notes |
|-------|-----------|------------|-------|
| Plains | Wheat, seeds, animals | Village | Easy starting biome |
| Forest | Oak/birch logs, apples | — | Common, good wood source |
| Dark Forest | Dark oak logs, mushrooms | Woodland Mansion | Dense, hard to navigate |
| Swamp | Sugar cane, lily pads, clay | Witch hut | Slimes spawn at night |
| Meadow | Flowers, bees | — | 1.18+ mountain biome |

### Hot Biomes
| Biome | Resources | Structures | Notes |
|-------|-----------|------------|-------|
| Desert | Sand, cactus, dead bush | Village, temple | No rain, no wood |
| Savanna | Acacia logs, grass | Village | Flat, good for building |
| Badlands | Terracotta, gold ore | — | Gold ore at any Y level |
| Jungle | Cocoa, bamboo, melon | Temple, pyramid | Very dense vegetation |

### Cold Biomes
| Biome | Resources | Structures | Notes |
|-------|-----------|------------|-------|
| Taiga | Spruce logs, berries | Village | Wolves spawn here |
| Snowy Plains | Snow, ice | — | Limited resources |
| Ice Spikes | Packed ice, blue ice | — | Rare, beautiful |
| Grove | Snow, spruce | — | 1.18+ mountain biome |

### Ocean Biomes
| Biome | Resources | Structures | Notes |
|-------|-----------|------------|-------|
| Ocean | Kelp, seagrass | Shipwreck, ruins | Varies by temperature |
| Deep Ocean | Prismarine, guardian | Ocean Monument | Dangerous! |
| Lukewarm Ocean | Tropical fish, coral | — | Warm variant |
| Frozen Ocean | Ice, polar bears | — | Icebergs |

### Cave Biomes (1.18+)
| Biome | Resources | Structures | Notes |
|-------|-----------|------------|-------|
| Lush Caves | Moss, azalea, dripping | — | Underground greenery |
| Dripstone Caves | Pointed dripstone | — | Stalactites/stalagmites |
| Deep Dark | Sculk, reinforced deepslate | Ancient City | Warden spawns here! |

```
# Find a specific biome
scan_blocks("biome_marker", range=128)  # Check biome at position

# Travel to find biomes
# Biomes generate in "climate zones" — similar biomes cluster together
# Move 1000+ blocks to find new climate zones
goto(x=current_x + 1000, y=current_y, z=current_z)
```

## Nether Biomes

| Biome | Resources | Mobs | Structures |
|-------|-----------|------|------------|
| Nether Wastes | Netherrack, quartz | Piglins, magma cubes | Nether Fortress, Bastion |
| Soul Sand Valley | Soul sand/soil, fossils | Skeletons, ghasts | — |
| Crimson Forest | Crimson fungi, stems | Hoglins | — |
| Warped Forest | Warped fungi, stems | Endermen | Best enderman farm! |
| Basalt Deltas | Basalt, blackstone | Magma cubes | — |

```
# Nether biomes are arranged in 3D (columns)
# Different biomes at same X/Z but different Y
# Travel horizontally to find different biomes
```

## End Biomes

| Biome | Resources | Mobs | Structures |
|-------|-----------|------|------------|
| The End | End stone | Endermen, dragon | Exit portal |
| End Midlands | Chorus plants | Endermen | — |
| End Highlands | Chorus plants | Endermen, shulkers | End City |
| End Barrens | End stone | Endermen | — |

## Structures Reference

### Overworld Structures
| Structure | Loot | Finding Method | Danger Level |
|-----------|------|----------------|-------------|
| Village | Food, iron, books | Walk and find | Low |
| Stronghold | Eye of ender portal | Eye of ender | Medium |
| Mineshaft | Carts, rails, ores | Cave exploration | Medium |
| Temple/Pyramid | Gold, diamonds, TNT | Desert/jungle | Low (traps!) |
| Shipwreck | Iron, supplies | Ocean | Low |
| Ocean Ruins | Gold, prismarine | Ocean | Low |
| Ocean Monument | Gold, sponge | Deep ocean | High |
| Woodland Mansion | Totem of undying | Dark forest | Very High |
| Ancient City | Swift sneak, disc | Deep dark | Extreme |
| Pillager Outpost | Crossbow, illager | Plains/forest | Medium |

### Nether Structures
| Structure | Loot | Finding Method | Danger Level |
|-----------|------|----------------|-------------|
| Nether Fortress | Blaze rods, wither skulls | Walk and find | High |
| Bastion Remnant | Gold, piglin loot | Walk and find | Very High |
| Ruined Portal | Obsidian, gold | Walk and find | Low |

### End Structures
| Structure | Loot | Finding Method | Danger Level |
|-----------|------|----------------|-------------|
| End City | Diamond, elytra | Bridge outward | High (void) |
| End Ship | Elytra! | Part of end city | Very High |

## Tool Usage Examples

### Find a Village
```
# Villages generate in plains, desert, savanna, taiga, snowy plains
# Walk 500-2000 blocks to find one
scan_blocks("bell", range=128)  # Villages always have a bell

# Use the village for trading and supplies
interact_at(x=villager_x, y=villager_y, z=villager_z)
```

### Find an Ocean Monument
```
# Ocean monuments are in deep ocean biomes
# Look for prismarine blocks or guardian entities
scan_blocks("prismarine", range=64)

# Or use exploration maps from cartographer villagers
# "Ocean Explorer Map" leads to monument
trade_with("cartographer", item="ocean_explorer_map")
```

### Navigate the Deep Dark
```
# The Deep Dark is extremely dangerous (Warden!)
# Warden: 500 health, 30 damage, blind but hears everything

# Stealth rules:
# - Walk, NEVER sprint (sprinting = vibration)
# - Don't break blocks (vibrations!)
# - Don't place blocks (vibrations!)
# - Don't throw items (vibrations!)
# - Use wool to dampen vibrations

# To safely explore:
build("wool", x=pos_x, y=pos_y-1, z=pos_z)  # Walk on wool — no vibrations!
goto(x=target_x, y=target_y, z=target_z)  # Walk only, never sprint
```

## Common Pitfalls

1. **Wrong biome for resource**: Each resource is biome-specific. You won't find bamboo outside jungles.
2. **Climate zone trap**: Similar biomes cluster. If you need a desert but are in a cold zone, travel far.
3. **Ocean monument without prep**: Guardians deal 3+ damage and inflict Mining Fatigue III (can't break blocks!).
4. **Deep Dark noise**: Every action creates sculk vibrations. The Warden is blind but hears EVERYTHING.
5. **Nether biome borders**: Nether biomes change abruptly. Watch for sudden biome transitions.
6. **End city void**: End cities float over the void. One wrong step = permanent death.

## Resource Requirements
No specific resources needed — this is a reference skill.

## Safety Warnings
- **NEVER** enter the Deep Dark without wool blocks — the Warden will hear you
- **NEVER** fight a Warden head-on — it has 500 HP and 30 damage (15 hearts!)
- **ALWAYS** bring water when exploring caves — lava is common
- **ALWAYS** mark your path in structures — they're easy to get lost in
- **WATCH** for traps in jungle temples and desert pyramids — tripwire and TNT
- Ocean Monument Guardians inflict Mining Fatigue III — you cannot break blocks while inside
- Woodland Mansions contain evokers who summon Vex (flying ghost mobs that pass through walls)
