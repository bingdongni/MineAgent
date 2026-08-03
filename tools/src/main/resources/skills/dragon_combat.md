# Dragon Combat

## Overview
The Ender Dragon is the final boss of Minecraft. Defeating it is required to access the outer End islands (for elytra, shulker boxes, etc.). This skill covers preparation, the fight strategy, and post-fight cleanup.

## Prerequisites
- **Portal**: End portal activated (see `stronghold_finding` skill)
- **Armor**: Full diamond armor (enchanted preferred — Protection IV or Blast Protection IV)
- **Weapon**: Diamond sword (Sharpness V preferred) + bow (Power V preferred)
- **Arrows**: 2-3 stacks (128-192)
- **Pickaxe**: Diamond pickaxe (for destroying end crystals)
- **Food**: 64+ golden carrots or cooked porkchops (saturation)
- **Blocks**: 128+ cobblestone/end stone for pillaring
- **Water bucket**: For breaking fall damage (MLG)
- **Potions**: Slow Falling (8:00) is EXTREMELY helpful
- **Optional**: Snowballs for crystal breaking, bed bomb strategy

## Step-by-Step Procedure

### Step 1: Enter the End
```
# Jump into the End portal
goto(x=portal_x, y=portal_y, z=portal_z)

# You appear on the obsidian platform
# Look around to orient yourself
look_around(range=64)

# The dragon will be circling the main island
# DO NOT look at endermen!
```

### Step 2: Destroy the End Crystals
End crystals heal the dragon. You MUST destroy all of them first.

```
# There are 10 crystals total:
# - 9 on obsidian pillars around the island
# - 1 on the bedrock cage in the center (caged crystal)

# Strategy: Shoot crystals with bow from the center
# The pillars have heights: some short (Y=30-50), some tall (Y=80-100)

# For exposed crystals (no iron bars cage):
equip("bow")
ranged_attack(target="end_crystal_1")
ranged_attack(target="end_crystal_2")
# ... shoot all visible crystals

# For the caged crystal (center bedrock pillar):
# You must build up to it and break the iron bars
goto(x=caged_crystal_x, y=0, z=caged_crystal_z, goal_mode="xz")
# Pillar up to the cage
build("cobblestone", x=pos_x, y=pos_y+1, z=pos_z)
build("cobblestone", x=pos_x, y=pos_y+2, z=pos_z)
# ... continue to cage height

# Break iron bars
use_item_on("diamond_pickaxe", target="iron_bars")

# Hit or shoot the crystal
melee_attack(target="end_crystal_caged")
```

### Step 3: Fight the Dragon
Once all crystals are destroyed, the dragon can be damaged.

```
# The dragon has two attack phases:
# 1. Circling phase: Dragon flies around the island
# 2. Perching phase: Dragon lands on the exit portal

# During CIRCLING phase:
# - Wait for the dragon to fly close
# - Shoot it with a bow when it's within range
# - Aim slightly ahead of the dragon's path
ranged_attack(target="ender_dragon", lead=2.0)

# During PERCHING phase:
# - The dragon lands on the exit portal (center)
# - Run up and hit it with your sword
# - Hit the dragon's HEAD for maximum damage
# - CRITICAL: Back away when it rises — it does a breath attack
goto(x=portal_x, y=portal_y, z=portal_z, goal_mode="xz")
melee_attack(target="ender_dragon_head")
# Back away immediately
goto(x=portal_x + 10, y=portal_y, z=portal_z, goal_mode="xz")
```

Dragon behavior details:
- Health: 200 (100 hearts)
- Breath attack: Purple acid cloud, 3 damage per second
- Wing attack: Knockback + 5 damage (perching phase)
- Dragon fireball: Purple breath projectile
- Immune to: Fire, lava, cactus, wither, falling damage
- Weak to: Player melee (sword), arrows, snowballs

### Step 4: Avoid Endermen
```
# The End spawns endermen constantly
# CRITICAL: Do NOT look at endermen while fighting

# Wear a carved pumpkin on your head to prevent aggro
equip("carved_pumpkin")  # Head slot

# If you accidentally aggro an enderman:
# 1. Look away immediately
# 2. Walk into water (if you placed any)
# 3. Build a 2-high shelter and hide
# 4. The enderman will de-aggro if you stay out of sight for 60 seconds
```

### Step 5: Victory
```
# When the dragon dies:
# - It flies up and explodes in a burst of experience orbs
# - Drops 12,000 XP (first kill) or 500 XP (subsequent)
# - The exit portal activates in the center
# - A dragon egg appears on top of the portal

# Collect XP
pickup_items(range=16)

# Enter the exit portal to return to the Overworld
goto(x=exit_portal_x, y=exit_portal_y, z=exit_portal_z)

# To get the dragon egg:
# 1. Place a torch under the block the egg sits on
# 2. Break the block — egg falls onto torch and drops as item
# Or: Push the egg with a piston
```

## Tool Usage Examples

### Bed Bomb Strategy (Advanced)
```
# Beds explode in the End (like in the Nether)
# This can deal massive damage to the dragon
# Each bed explosion deals ~15-25 hearts to the dragon

# Strategy:
# 1. Wait for dragon to perch
# 2. Place a bed near the dragon's head
# 3. Try to sleep in the bed — it EXPLODES
# 4. The explosion damages the dragon

# WARNING: This also damages YOU!
# Use Blast Protection armor + place bed 1 block away

build("bed", x=dragon_x+1, y=dragon_y, z=dragon_z)
interact_at(x=dragon_x+1, y=dragon_y, z=dragon_z)  # BOOM!
```

### Safe Crystal Destruction
```
# Use snowballs to break crystals from a distance
# Snowballs are cheaper than arrows
# They break crystals on contact (no damage to dragon, but that's fine)

equip("snowball")
use_item()  # Throw at crystal
# Crystal explodes — dragon takes 10 damage from each crystal destroyed
```

### Slow Falling Potion Strategy
```
# Slow Falling prevents all fall damage
# This makes the fight MUCH easier — you can safely pillar up and fall

# Before entering the End:
craft("slow_falling_potion", duration="8:00")
use_item("slow_falling_potion")

# Now you can pillar up to any height without fear
# If the dragon knocks you off, you float down gently
```

## Common Pitfalls

1. **Forgetting crystals**: If even ONE crystal remains, the dragon heals. Check all 10 pillars.
2. **Enderman aggro**: Looking at endermen during the fight is disastrous. Wear a pumpkin.
3. **Falling off the island**: The void kills you instantly. Always have blocks to bridge back.
4. **Dragon breath**: The purple acid cloud lingers. Walk around it, not through it.
5. **Perching timing**: The dragon only perches for ~5 seconds. Hit fast, then retreat.
6. **Knockback**: The dragon's wing attack has extreme knockback. Don't stand near edges.
7. **Respawn**: If you die, you respawn at the world spawn (NOT the End). You must walk back.

## Resource Requirements
| Resource | Amount | Notes |
|----------|--------|-------|
| Diamond Armor | Full set | Enchanted preferred |
| Diamond Sword | 1 | Sharpness V ideal |
| Bow | 1 | Power V ideal |
| Arrows | 128-192 | For crystals + dragon |
| Cobblestone | 128+ | For pillaring |
| Food | 64+ | Golden carrots best |
| Water Bucket | 1 | MLG water save |
| Slow Falling | 1+ potions | Makes fight much easier |
| Pumpkin | 1 | Prevents enderman aggro |

## Safety Warnings
- **NEVER** look at endermen — they will swarm you and distract from the dragon fight
- **NEVER** stand on the edge of the island — dragon knockback can push you into the void
- **NEVER** walk through dragon breath — it deals 3 DPS and lingers for 30+ seconds
- **ALWAYS** have blocks in your hotbar — you may need to bridge back to the island
- **ALWAYS** destroy ALL crystals before focusing on the dragon — it heals from them
- **WATCH** for the dragon's charge attack — it flies directly at you with high damage
- The void is INSTANT DEATH — no items recovered, no XP recovered
- Bring a water bucket for MLG — place water at your feet when falling to negate fall damage
