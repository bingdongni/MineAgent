# Combat Basics

## Melee Combat
- **Approach**: Walk toward target until within 3 blocks
- **Attack**: Use `melee_attack` tool on the entity
- **Critical hits**: Jump and attack while falling for 50% bonus damage
- **Weapon priority**: Sword > Axe > Pickaxe > Fist
- **Combo timing**: Wait 0.6s between attacks for max DPS (sword)

## Ranged Combat
- **Use `ranged_attack`** for bow/crossbow/trident
- **Lead targets**: Aim where the mob will be, not where it is
- **Arrows**: Keep a stack of arrows in inventory

## Defense
- **Shield**: Use `equip` to hold a shield, `interact_at` to block
- **Retreat**: Use `goto` with goal_mode "xz" to flee
- **MLG**: Water bucket save triggers automatically on falls > 3 blocks

## Hostile Mobs Priority
1. Creeper (highest — explode near you)
2. Enderman (if you looked at it)
3. Skeleton (ranged threat)
4. Spider (fast and climbs)
5. Zombie (melee, slow)

## Important Rules
- NEVER fight underwater without a helmet with Respiration
- ALWAYS have a water bucket in hotbar when exploring
- EAT before fighting if hunger is below 18/20
- RUN from Creepers if you can't kill in 2 hits
