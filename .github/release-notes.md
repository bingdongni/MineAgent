MineAgent is currently an alpha release for Minecraft 1.21.1 and requires Java 21.

## Downloads

- `mineagent-fabric-*.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-*.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

The NeoForge build now uses the same complete client UI, key bindings, HUD/debug views, and loader-neutral network contract as Fabric. It remains experimental because its in-game compatibility coverage is still smaller. Please include the loader, exact versions, reproduction steps, and sanitized logs in bug reports.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
