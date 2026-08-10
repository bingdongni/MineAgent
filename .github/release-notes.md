MineAgent 0.3.6 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release adds human-controlled, independently persisted game modes for every companion. Configure & Create offers Survival, Creative, Adventure, and Hardcore and defaults every omitted or blank mode to Survival. Companion Chat now lets the human select any owned companion and change that companion's mode at any time.

Survival, Creative, and Adventure are backed by vanilla `GameType`, abilities, and interaction restrictions. Vanilla Hardcore is world-scoped, so MineAgent implements the accurate per-companion equivalent: Survival rules plus persistent permanent death. Once a Hardcore companion dies, its body is permanently locked: mode changes, `/mineagent respawn`, and world rejoin cannot revive it. The owner must create a new companion.

The fake-player game-mode wrapper no longer hard-codes Survival after a mode change. Creation applies the selected mode before registration, runtime changes cancel actions accepted under the previous permission model, and the server validates both companion ownership and the four-value mode vocabulary. Mode changes are rejected for permanently dead Hardcore bodies. The LLM receives the selected mode as trusted context but has no tool or endpoint with which to change it.

Old companion stores without a mode load as Survival. Game mode and Hardcore death state survive owner disconnects and world reloads. Both loader modules compile from the same shared UI and network contract.

## Downloads

- `mineagent-fabric-0.3.6.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-0.3.6.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
