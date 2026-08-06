MineAgent 0.3.1 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release strengthens complex single-player task closure on top of the v0.3.0 L4 adaptation runtime. Strategic goals now have explicit acceptance state above the tactical graph, suffix repair preserves executor-verified checkpoints, and a completed action window cannot claim the owner's objective without machine-observed final conditions.

`plan_acquisition` builds a bounded recursive item dependency DAG from live inventory, actually observed assets, and registered recipes. The new vanilla `use_item` task handles immediate, consumable, and charged lifecycles, while `wait_for` gives furnaces, modded machines, GUI transitions, dimensions, blocks, and entities stable asynchronous conditions and diagnostic deadlines.

Plan replacement is rejected while a body task or skill owns its evidence binding. Survival preemption reconstructs continuous item use, empty GUI slots can be verified correctly, and unloaded chunks cannot prove entity absence. Version 10 memory remains backward-compatible with v1-v9 files and never restores in-flight body ownership.

Regression tests cover L3/L4 planning, memory, skills, mechanism exploration, realtime cognition, suffix repair, strategic acceptance, restart behavior, and legacy plan-state migration. This release provides general mechanisms rather than a hard-coded completion route; it does not claim deterministic success across arbitrary seeds, private mod APIs, all modpacks, or competitive servers.

## Downloads

- `mineagent-fabric-0.3.1.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-0.3.1.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

The NeoForge build uses the same complete client UI, key bindings, HUD/debug views, and loader-neutral network contract as Fabric. It remains experimental because its in-game compatibility coverage is smaller. Include the loader, exact versions, reproduction steps, and sanitized logs in bug reports.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
