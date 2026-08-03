# Redstone Basics

## Redstone Components
- **Redstone Dust**: Carries signal up to 15 blocks, loses 1 strength per block
- **Redstone Torch**: Constant signal source (ON), inverts signal
- **Repeater**: Extends signal range, adds 1-4 tick delay, acts as diode
- **Comparator**: Compares signals, reads container fullness
- **Piston**: Pushes blocks 1 space (sticky pulls 1 back)
- **Observer**: Emits 1-tick pulse when block in front changes
- **Lever/Button/Pressure Plate**: Player-triggered signal sources

## Basic Circuits
- **NOT Gate**: Redstone torch on side of block with input
- **AND Gate**: Two inputs into a block, torch on other side
- **OR Gate**: Two inputs merge into same dust line
- **T-Flip-Flop**: Use observer + sticky piston for toggle
- **Clock**: Repeater loop (2+ repeaters set to delays)

## Tips for AI
- Use `build` to place redstone components
- Use `inspect_block` to read signal strength
- Start simple: lever → dust → lamp
- Redstone on top of slabs/stairs doesn't connect
- Quasi-connectivity: pistons can be powered from above
