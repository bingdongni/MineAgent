# Changelog

All notable changes to MineAgent will be documented in this file. The project follows [Semantic Versioning](https://semver.org/) where practical during its alpha stage.

## [0.2.0] - 2026-08-06

### Added

- Add a multi-rate realtime cognition layer: vanilla and survival control remain at 20 Hz, local situation frames refresh at 5 Hz or every emergency tick, and the LLM is reserved for open-ended deliberation.
- Add immutable situation snapshots covering vitals, hazards, owner state, executor state, nearby actors, projectiles, drops, relationships, visibility, approach vectors, and grounded threat ownership.
- Add a receding-horizon tactical planner with hard safety constraints and context-specific factors for survival, owner defense, threat control, team cohesion, information gain, and verified goal progress.
- Add an owner-scoped live team blackboard and `coordinate_team` tool for roles, commitments, duplicate-work warnings, and explicit support requests.
- Add dependency edges and cycle validation to `todowrite` plans through `depends_on`.
- Add deterministic multilingual retrieval for successful and failed experiences using ASCII tokens and Chinese character n-grams.
- Add tests for tactical posture selection, plan dependencies, team-state isolation and expiry, parameterized skill retrieval, and relevant successful experience recall.

### Changed

- Wake the LLM only for owner commands, verified task completion, explicit team support, or structured replanning events; routine body narration is batched into the next real decision.
- Stop broadcasting every companion utterance to every sibling. Routine cooperation now uses live state, while only explicit `[TEAM]` communication wakes teammates.
- Use Minecraft game ticks consistently in Theory of Mind, with bounded evidence weights and temporal decay; structured server observations outrank chat keyword guesses.
- Expose a stable core tool schema by default and activate specialized schemas after `query_extra_tools`, reducing repeated prompt tokens while keeping tool order cache-stable.
- Store learned skills as parameterized JSON tool traces. Asynchronous actions enter the skill library only after executor-verified terminal outcomes.
- Rank combat threats by who they endanger as well as distance, defend the owner and sibling companions, and jump while fleeing only to clear a grounded collision.

### Fixed

- Prevent a blocked plan node from permanently occupying the current executable step when an independent ready step exists.
- Reject missing plan dependencies, self/cyclic dependency graphs, and invalid partial replacements without destroying the last valid plan.
- Prevent plan-only blocked states from causing a null dereference during skill learning.
- Remove N-by-N companion request amplification and the resulting stale-response/token cascade.
- Correct the former 60-second owner-intent window, which mixed epoch milliseconds with game ticks and effectively expired in about 1.2 seconds.

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
