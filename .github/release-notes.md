MineAgent 0.2.5 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release completes the L3 closed-loop execution architecture: learned skills now run one verified step at a time; an event-sourced semantic world model supplies temporal evidence; a hierarchical rolling planner repairs invalid plan suffixes; and a risk-bounded mechanism explorer supports falsifiable experiments with unfamiliar mod content. Multi-turn skills are learned only from fully successful verified episodes.

These systems materially improve autonomy, recovery, grounding, and token efficiency, but this alpha does not claim that arbitrary modpacks, competitive servers, or autonomous full-game completion have been validated.

## Downloads

- `mineagent-fabric-*.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-*.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

The NeoForge build now uses the same complete client UI, key bindings, HUD/debug views, and loader-neutral network contract as Fabric. It remains experimental because its in-game compatibility coverage is still smaller. Please include the loader, exact versions, reproduction steps, and sanitized logs in bug reports.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
