# Changelog

All notable changes to MineAgent will be documented in this file. The project follows [Semantic Versioning](https://semver.org/) where practical during its alpha stage.

## [0.3.5] - 2026-08-10

### Added

- Add vendor-neutral `openai-compatible`, `anthropic-compatible`, and `gemini-compatible` protocol IDs while retaining all legacy provider IDs.
- Add a dedicated credential-bearing `companion_setup` payload on Fabric and NeoForge, with shared field limits, control-character rejection, and server-authoritative validation.
- Add arbitrary model-ID and custom registered-adapter entry, compact vendor presets, hidden/revealable API-key input, and non-secret server configuration summaries.
- Add no-auth OpenAI-compatible support for local Ollama, LM Studio, vLLM, and similar endpoints.
- Add regression tests for arbitrary future model IDs, setup validation, protocol aliases, and endpoint path normalization.

### Changed

- Merge Create Companion and Model Connection into one responsive two-column page and remove the duplicate control-panel route.
- Treat provider presets and `/mineagent models` output as examples rather than an allow-list.
- Persist a complete connection tuple only after companion creation succeeds.
- Reuse a stored API key only when protocol, model, and endpoint still match the server summary, preventing credential forwarding after a target change.
- Accept host roots, version roots, and complete OpenAI/Anthropic endpoint paths without duplicating API suffixes.

### Fixed

- Prevent invalid effort or connection fields from partially updating global configuration through a sequence of slash commands.
- Prevent API keys from entering command history, chat feedback, or ordinary server logs during visual setup.
- Prevent the global API-key requirement from rejecting intentionally unauthenticated local adapters.
- Preserve third-party provider IDs returned by the server instead of silently remapping them to OpenAI compatibility.

## [0.3.1] - 2026-08-06

### Added

- Add verifier-backed strategic goal states and machine-checkable semantic acceptance conditions above the tactical plan graph.
- Add suffix-only plan repair that retains verified checkpoints, constraints, evidence, and stable dependency identities.
- Add `plan_acquisition`, a bounded recursive item dependency planner over live carried assets, observed storage/drops, recipe yields, batches, alternatives, planned surplus, and unknown station leaves.
- Add asynchronous vanilla `use_item` execution for immediate, natural-duration, and charged item lifecycles in either hand with optional 3D aim.
- Add asynchronous `wait_for` execution for duration, inventory, semantic fact, GUI slot, dimension, block, and entity conditions with stability windows and hard deadlines.
- Add regression coverage for suffix preservation, blocked-milestone recovery, strategic acceptance, same-goal failure reset, restart ownership, and v1-v9 plan-state compatibility.

### Changed

- Keep strategic progress below 100 percent until both executor-backed milestones and explicit top-level acceptance conditions are verified.
- Rebind recovery actions to blocked milestones and clear only the invalid tactical failure window after an accepted repair.
- Give the explicitly requested acquisition DAG a bounded complete tool-result budget instead of truncating it at the generic action-result limit.
- Increment the memory format to version 10; v1-v9 files remain readable and in-flight body ownership remains non-restorable.

### Fixed

- Prevent plan replacement while a body task or learned skill still owns evidence bindings.
- Prevent survival preemption from turning an interrupted consumable use into success or releasing an item without rebuilding its vanilla use state.
- Allow GUI-slot waits to verify empty slots and zero counts without confusing unrelated slot contents for the requested item.
- Reject malformed wait, dimension, presence, and aim parameters instead of silently applying defaults.
- Prevent unloaded chunks from proving that an entity is absent.
- Prevent an accepted same-goal repair from immediately retriggering a stale repeated-failure signal.

## [0.3.0] - 2026-08-06

### Added

- Add an environment-scoped mechanism knowledge base for bounded block, item, entity, menu, recipe, property, and GUI-slot profiles learned from real tool observations.
- Add loader/mod-version/registry fingerprints so confirmed rules become stale instead of replaying silently after the game environment changes.
- Add competing evidence-weighted hypotheses, independent-context confirmation, counterexample invalidation, persistent per-subject exploration budgets, and information-per-cost probe ranking.
- Add explicit reversible compensation for state-changing experiments and compile confirmed action rules into ordinary postcondition-verified skills.
- Add L4 regression coverage for causal baselines, independent confirmation, contradictions, fingerprint invalidation, compensation requirements, GUI structure, restart semantics, safe probe ranking, and goal-relevant recall.

### Changed

- Require every medium-risk exploration probe to declare a verified compensation; reject high-risk probes and irreversible crafting as autonomous experiments.
- Feed successful inspection results into bounded mechanism profiles even outside an explicitly armed experiment.
- Return registered menu type IDs and empty container-side slot structure from `inspect_gui` while retaining a strict output bound.
- Recall at most three sufficiently relevant mechanism rules in the dynamic prompt and automatically expose controlled exploration when a known-unverified object is relevant to the owner goal.
- Increment the memory format to version 9; v1-v8 files remain readable and no in-flight body ownership is restored.

### Fixed

- Prevent an old or heartbeat-refreshed semantic fact from proving a causal mechanism transition.
- Prevent one successful probe or repeated evidence from the same setup from creating a reusable rule.
- Remove generated adapters immediately after contradictory verified evidence or an environment fingerprint change.
- Prevent adapter reconstruction during memory load from inflating learned-skill invocation statistics.
- Prevent a single common query word from injecting unrelated mechanism rules into prompts.

## [0.2.6] - 2026-08-06

### Added

- Add goal-conditioned long-term recall across owner intent, strategic goal, active plan step, verified experiences, semantic outcomes, cognitive-map POIs, and place-event memory.
- Persist verified physical-action outcomes and owner goals while keeping live scans, inventory projections, and routine observations volatile.
- Add regression coverage for repeated blocked heartbeats, durable semantic recall, asset persistence boundaries, goal-conditioned spatial memory, and learned-skill validation.

### Changed

- Coalesce duplicate cognition, scheduler, and rolling-planner events before they can interrupt an in-flight LLM request.
- Keep specialized tool schemas exposed for the active owner goal and return only tool names from discovery results instead of duplicating complete schemas.
- Use a compact stable core tool surface, deterministic intent-based tool routing, and a 4K cap for routine follow-up generations while preserving full output budgets for initial strategy and large structured builds.
- Retrieve only a small query-relevant subset of learned skills and require every learned trace to contain at least one executable world action.
- Bound the persisted world-asset index and prioritize durable storage, workstations, beds, portals, modded block entities, and verified placements over ordinary ore, logs, and water scans.
- Increment the memory format to version 8; v1-v7 files remain readable and are cleaned during migration without deleting user memory.

### Fixed

- Prevent identical blocked progress heartbeats from incrementing plan revisions or repeatedly waking and cancelling LLM requests.
- Prevent elapsed stall ticks from changing the replan deduplication signature every server tick.
- Prevent normal body narration from entering the reasoning inbox and implicitly starting extra turns.
- Prevent history/tool discovery churn from repeatedly dropping provider prompt-cache reuse within one owner objective.
- Prevent old plan nodes from overriding a newer owner instruction during experience retrieval and skill naming.
- Remove legacy `asset:*`, query-only, malformed, obsolete-tool, and `general_task` pollution from persisted semantic and skill memory.

## [0.2.5] - 2026-08-06

### Added

- Add a closed-loop learned-skill runtime with sequential dispatch, semantic preconditions and postconditions, authoritative asynchronous task verification, bounded timeouts, cancellation, and structured replan outcomes.
- Add an event-sourced semantic world model with provenance, confidence, temporal expiry, out-of-order observation protection, durable projections, inventory removals, actor identity tracking, and action/result correlation.
- Add a hierarchical rolling planner spanning strategic goals, a bounded tactical window, and live executor progress, with deduplicated replanning for stalls, blocked windows, repeated failures, and relevant world changes.
- Add the `execute_skill` tool for verified autonomous replay with per-step argument overrides.
- Add the `explore_mechanism` tool and persistent risk-bounded hypothesis experiments for unfamiliar blocks, items, GUIs, recipes, machines, and mod rules.
- Add regression tests for skill sequencing/effect verification, semantic expiry and temporal ordering, rolling-plan stall/block detection, controlled exploration, and synchronous tool evidence.

### Changed

- Consolidate verified actions across multiple model turns into one reusable skill only after the full plan succeeds; failed episodes discard their partial trace without overwriting prior verified skills.
- Allow synchronous world-changing tools such as crafting, equipment, and inventory transfer to verify plan nodes with grounded tool results.
- Feed task, inventory, asset, actor, action, and outcome events into one semantic evidence substrate consumed by planning, skill verification, exploration, persistence, and the live prompt.
- Increment the memory format to version 7 for semantic-world, rolling-plan, and mechanism-experiment persistence.

### Fixed

- Prevent an inner task in a multi-step skill from prematurely verifying the parent plan node.
- Prevent late observations from rolling the semantic projection back to an older state.
- Prevent unchanged invalid planning states from repeatedly waking the LLM and wasting tokens.
- Preserve the actual originating tool name through asynchronous task completion so postcondition and exploration evidence is attributed correctly.

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
