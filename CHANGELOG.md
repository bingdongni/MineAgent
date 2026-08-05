# Changelog

All notable changes to MineAgent will be documented in this file. The project follows [Semantic Versioning](https://semver.org/) where practical during its alpha stage.

## [0.1.4] - 2026-08-05

### Added

- Add shared visible-face targeting for mining, block use, and placement, including structured reach, occlusion, world-border, game-mode, and server-policy evidence.
- Add provider-normalized prompt-cache metrics for OpenAI-compatible, DeepSeek, Anthropic, and Gemini responses, with prompt, cached, cache-creation, completion, and latency logging.
- Add structured `scan_blocks` spatial memory for exact vanilla and modded block registry IDs.

### Changed

- Keep the system prompt byte-stable and attach volatile body, plan, memory, and world-asset evidence as a transient request tail to improve provider prefix-cache reuse.
- Compact conversation history with hysteresis and a bounded rolling summary, reducing repeated prompt rewrites and token churn during long tool sequences.
- Allow only one asynchronous body action per model response, while preserving synchronous perception and planning calls.

### Fixed

- Preserve body and owner events that arrive during an LLM turn; the previous handoff cleared the inbox before the next turn could consume it.
- Interrupt an obsolete blocking provider request when a newer owner command arrives, rather than waiting for the stale response before reacting.
- Publish paused tasks as `PAUSED` and terminal tasks as an idle body with last-outcome evidence, preventing false body-occupancy reasoning.
- Keep the emergency breath controller in charge until the player's eyes actually reach air, route through nearby open water/air cells, clear a reachable roof through vanilla mining, and recover from stalled fake-client swim input.
- Reset per-target mining timers, verify vanilla accepted progressive breaking, and stop describing geometry failures as protected regions.
- Resolve placement support faces through real line of sight, synchronize inventory after every vanilla placement attempt, and support modded blocks whose item form uses a different registry ID.
- Record scan observations directly in durable spatial memory instead of depending on English/Chinese keyword extraction from body narration.

## [0.1.3] - 2026-08-05

### Added

- Add a persistent, evidence-backed world asset index covering inventory, equipment, inspected storage, dropped items, placed blocks, facilities, positions, durability, capabilities, confidence, and observation age.
- Add `resolve_need`, which compares carried, stored, dropped, and world assets and queries the live recipe registry for exact item or capability needs, including modded registry content.
- Add deterministic tests for asset replacement, container invalidation, capability-based tool reuse, and durable-memory boundaries.

### Fixed

- Detect and report physical navigation stalls with grounded edge evidence; replanning excludes the failed directed edge instead of repeating it.
- Expose authoritative task progress, position, movement index, and failure evidence to the agent loop.
- Include vanilla and modded functional blocks in perception, with explicit facility affordances and reusable crafting-table coordinates.
- Return a structured navigation hint when a crafting table is nearby but outside interaction range.
- Fail truthfully on incomplete mining and bound repeated unreachable-target attempts.
- Prevent redundant crafting when the requested total is already carried; require an explicit, evidence-backed decision before ignoring known storage, a capable substitute, or a reusable placed object.
- Keep inspected container memory consistent after GUI transfers by associating an accepted block interaction with the actual open menu.
- Report player and container slot addresses separately in `inspect_gui`, and preserve the moved item ID after a full-stack transfer.
- Publish post-tick and post-action inventory evidence to the LLM thread without reading live Minecraft state off-thread.

## [0.1.0] - 2026-08-03

### Added

- Initial public alpha release by bingdongni.
- Supported Fabric build for Minecraft 1.21.1 with client menu, chat, HUD, and debug views.
- Experimental NeoForge build for Minecraft 1.21.1.
- LLM integrations for OpenAI, DeepSeek, Qwen, GLM, Moonshot, Grok, MiniMax, Anthropic, and Gemini.
- Fake-player lifecycle, priority scheduling, navigation, survival chains, task tools, and persistent memory.

### Known limitations

- AI actions remain model-dependent and may be slow, inefficient, or incorrect.
- NeoForge does not yet have Fabric-equivalent client UI, networking parity, or the same level of in-game validation.
- LLM API keys are stored in plain text for companion restoration; see `SECURITY.md`.
