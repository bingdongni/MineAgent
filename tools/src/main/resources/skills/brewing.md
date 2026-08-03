# Brewing

## Overview
Brewing creates potions that provide temporary buffs or debuffs. Potions are essential for advanced gameplay: fire resistance for the Nether, slow falling for the End, strength for combat, etc. This skill covers the brewing system, recipes, and strategies.

## Prerequisites
- **Brewing stand**: 1 blaze rod + 3 cobblestone
- **Blaze powder**: Fuel for the brewing stand (not consumed per brew, lasts 20 operations)
- **Water bottles**: Glass bottles filled with water
- **Nether wart**: Base ingredient for most potions
- **Various modifiers**: Redstone, glowstone, gunpowder, dragon breath

## Step-by-Step Procedure

### Step 1: Set Up a Brewing Station
```
# Craft brewing stand
craft("brewing_stand", count=1)  # 1 blaze rod + 3 cobblestone
build("brewing_stand", x=0, y=0, z=0)

# Craft glass bottles
craft("glass_bottle", count=3)  # 3 glass → 3 bottles

# Fill bottles with water
use_item_on("glass_bottle", target="water_source")
# Now you have water bottles

# Place blaze powder in the fuel slot (top-left)
# 1 blaze powder = 20 brewing operations
deposit("blaze_powder", slot="fuel")
```

### Step 2: Brew an Awkward Potion (Base)
All potions start with an awkward potion (water bottle + nether wart).

```
# Open the brewing stand
interact_at(x=0, y=0, z=0)

# Place 3 water bottles in the bottom slots
deposit("water_bottle", slot=1)
deposit("water_bottle", slot=2)
deposit("water_bottle", slot=3)

# Place nether wart in the top slot (ingredient)
deposit("nether_wart", slot="ingredient")

# Wait for brewing to complete (20 seconds per operation)
wait(20)

# Result: 3 awkward potions
# These are the base for ALL positive potions
```

### Step 3: Add the Effect Ingredient
```
# Each effect ingredient creates a specific potion from awkward base:
# Example: Fire Resistance

deposit("magma_cream", slot="ingredient")  # Fire Resistance ingredient
wait(20)

# Result: 3 Fire Resistance potions (3:00 duration)
```

### Step 4: Modify the Potion (Optional)
```
# Redstone: Extends duration to 8:00 (from 3:00)
deposit("redstone", slot="ingredient")
wait(20)
# Result: 3 Fire Resistance (8:00) potions

# Glowstone: Amplifies effect to level II but halves duration
# Not available for all potions (Fire Resistance has no level II)

# Gunpowder: Converts to splash potion (throwable)
deposit("gunpowder", slot="ingredient")
wait(20)
# Result: 3 Splash Fire Resistance (8:00) potions

# Dragon Breath: Converts splash to lingering (area effect cloud)
deposit("dragon_breath", slot="ingredient")
wait(20)
# Result: 3 Lingering Fire Resistance (2:00) potions
```

## Complete Potion Recipes

### Positive Potions (from Awkward)
| Potion | Ingredient | Duration | Effect |
|--------|-----------|----------|--------|
| Fire Resistance | Magma Cream | 3:00 | Immune to fire/lava |
| Water Breathing | Pufferfish | 3:00 | Breathe underwater |
| Night Vision | Golden Carrot | 3:00 | See in dark |
| Invisibility | Fermented Spider Eye | 3:00 | Invisible (armor shows) |
| Slow Falling | Phantom Membrane | 1:30 | No fall damage |
| Swiftness | Sugar | 3:00 | +20/40% speed |
| Strength | Blaze Powder | 3:00 | +3/6 attack damage |
| Healing | Glistering Melon | Instant | +4/8 hearts |
| Jump Boost | Rabbit's Foot | 3:00 | +1/2 jump height |
| Regeneration | Ghast Tear | 0:45 | +0.5/1 HP per sec |

### Negative Potions (from Awkward or by inversion)
| Potion | Ingredient | Duration | Effect |
|--------|-----------|----------|--------|
| Poison | Spider Eye | 0:45 | 1 damage/1.25s |
| Weakness | Fermented Spider Eye | 1:30 | -4/6 attack damage |
| Slowness | Sugar (inverted) | 1:30 | -15/60% speed |
| Harming | Fermented Spider Eye + Healing | Instant | 6/12 damage |

### Modifiers
| Modifier | Effect | Notes |
|----------|--------|-------|
| Redstone | +Duration (3:00→8:00) | Not for instant potions |
| Glowstone Dust | +Level (I→II) | Halves duration |
| Gunpowder | Splash (throwable) | Can hit other entities |
| Dragon Breath | Lingering (area cloud) | Requires splash first |

## Tool Usage Examples

### Essential Nether Potions
```
# Fire Resistance (8:00) — absolutely essential for Nether
brew("awkward_potion", ingredient="nether_wart")
brew("fire_resistance", ingredient="magma_cream")
brew("fire_resistance_extended", ingredient="redstone")
# Carry 2-3 of these before entering the Nether

# Night Vision (8:00) — helps see in Nether fog
brew("awkward_potion", ingredient="nether_wart")
brew("night_vision", ingredient="golden_carrot")
brew("night_vision_extended", ingredient="redstone")
```

### Essential End Potions
```
# Slow Falling (4:00 with Redstone) — CRITICAL for dragon fight
brew("awkward_potion", ingredient="nether_wart")
brew("slow_falling", ingredient="phantom_membrane")
brew("slow_falling_extended", ingredient="redstone")
# Redstone extends to 4:00 (base is only 1:30)

# Strength II (1:30) — extra damage for dragon perching phase
brew("awkward_potion", ingredient="nether_wart")
brew("strength", ingredient="blaze_powder")
brew("strength_ii", ingredient="glowstone")
```

### Splash Potions for Combat
```
# Splash Healing II — damage undead, heal allies
brew("awkward_potion", ingredient="nether_wart")
brew("healing", ingredient="glistering_melon")
brew("healing_ii", ingredient="glowstone")
brew("splash_healing_ii", ingredient="gunpowder")
# Throw at your feet to heal, or at undead to damage

# Splash Poison — damage mobs over time
brew("awkward_potion", ingredient="nether_wart")
brew("poison", ingredient="spider_eye")
brew("poison_extended", ingredient="redstone")
brew("splash_poison_extended", ingredient="gunpowder")
```

### Potion of Turtle Master (Advanced)
```
# Turtle Master: Slowness IV + Resistance III
# Extremely powerful for tanking hits
brew("awkward_potion", ingredient="nether_wart")
brew("turtle_master", ingredient="turtle_helmet")
brew("turtle_master_extended", ingredient="redstone")
# You move VERY slowly but take very little damage
# Great for surviving the wither
```

## Common Pitfalls

1. **No blaze powder fuel**: The brewing stand needs blaze powder in the fuel slot. Without it, nothing brews.
2. **Wrong base**: Most potions need awkward base. You can't add fire resistance to a water bottle directly.
3. **Glowstone + Redstone conflict**: You can't have both extended AND amplified. Choose one.
4. **Inverted potions**: Some inverted potions are weird (Strength → Weakness, Swiftness → Slowness).
5. **Splash self-damage**: Splash Harming hurts YOU too. Throw it at enemies, not yourself.
6. **Nether wart location**: Nether wart ONLY grows in Nether Fortresses (on soul sand). It doesn't grow in the Overworld but can be planted there.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Blaze Rod | 1+ | For brewing stand + fuel |
| Nether Wart | 4+ | Base for all potions |
| Glass | 3+ per brew | For bottles |
| Modifier items | varies | Redstone, glowstone, etc. |
| Effect items | varies | Magma cream, golden carrot, etc. |

## Safety Warnings
- **NEVER** enter the Nether without Fire Resistance potions — lava is everywhere
- **NEVER** throw splash potions at your feet unless you know the effect (Harming = death!)
- **ALWAYS** carry at least 2 Fire Resistance (8:00) potions in the Nether
- **ALWAYS** brew Slow Falling before the dragon fight — it prevents void death
- **WATCH** potion durations — a 3:00 potion runs out faster than you think
- Night Vision + Invisibility combo: Night Vision prevents the darkness from Invisibility side effects
- Turtle Master makes you VERY slow — don't use it when you need to run away
