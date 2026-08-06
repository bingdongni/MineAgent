MineAgent 0.2.6 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release focuses on long-session intelligence and responsiveness. Long-term recall is now conditioned on the latest owner objective, strategic goal, and active plan step. Verified world-action outcomes and owner goals persist, while routine scans and derived asset projections remain volatile. Legacy semantic and learned-skill pollution is filtered during backward-compatible v8 memory migration.

Repeated blocked heartbeats and equivalent cognition/replan events are coalesced before they can wake or cancel LLM calls. Dynamic tools stay exposed for one owner objective, discovery results no longer duplicate complete schemas, and routine follow-up generations use a bounded output budget without constraining initial strategy or large build payloads.

The release includes 46 passing Fabric regression tests covering planning, semantic memory, asset persistence, goal-conditioned retrieval, skills, exploration, and cognition. These changes improve grounding, latency, cache reuse, and token efficiency, but this alpha does not claim that arbitrary modpacks, competitive servers, or autonomous full-game completion have been validated.

## Downloads

- `mineagent-fabric-0.2.6.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-0.2.6.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

The NeoForge build uses the same complete client UI, key bindings, HUD/debug views, and loader-neutral network contract as Fabric. It remains experimental because its in-game compatibility coverage is smaller. Include the loader, exact versions, reproduction steps, and sanitized logs in bug reports.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
