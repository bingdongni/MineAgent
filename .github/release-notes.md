MineAgent 0.3.5 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release unifies companion creation and model connection into one configuration screen. Users can select a compact convenience preset or directly enter a registered protocol adapter, arbitrary model ID, API key, and official, relay, aggregator, or self-hosted base URL. Presets are field fillers, not a model allow-list.

The built-in protocol surface now includes `openai-compatible` Chat Completions, `anthropic-compatible` Messages, and `gemini-compatible` generateContent. All previous provider IDs remain available for stored configuration and companions. Compatible endpoints can use model IDs that are not listed by MineAgent, including models released after this mod version. New incompatible private protocols remain extensible through `LLMProviderRegistry`; an unpublished future wire protocol cannot be guaranteed before an adapter exists.

Configuration and creation now travel through a dedicated validated payload on both loaders. API keys no longer enter slash commands, the server sends only a non-secret configuration summary, and configuration is committed only after companion creation succeeds. A saved key is reused only while protocol, model, and endpoint remain unchanged, preventing accidental credential forwarding to a newly selected relay. OpenAI-compatible local services can intentionally run without authentication.

Regression tests cover arbitrary model IDs, credential and field limits, endpoint normalization, and protocol aliases. Both loader modules compile from the same shared UI and API contract.

## Downloads

- `mineagent-fabric-0.3.5.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-0.3.5.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
