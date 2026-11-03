# MineAgent

> **v1.0.0 development**: real-mod learning and execution, bounded retention across tasks, public ports and unfamiliar menus, dependency jobs and guarded team learning. See the [design and usage](docs/research/lifelong-learning-1.0.0.md) and [validation record](docs/research/validation-1.0.0.md) (Chinese). The published release remains [v0.6.0](https://github.com/bingdongni/MineAgent/releases/tag/v0.6.0); v1.0.0 has not been published.

English | [简体中文](README.md)

[![Build](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml/badge.svg)](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml)
[![License: LGPL-3.0-only](https://img.shields.io/badge/License-LGPL--3.0--only-blue.svg)](LICENSE)

MineAgent is an LLM-driven AI companion mod for Minecraft 1.21.1. It creates a server-side fake player with no real client connection and lets an AI interact with the world through movement, gathering, mining, building, combat, crafting, inventory management, and survival instincts.

## v1.0.0: Real mods, retention and team learning

- Exercise production learning and actions against pinned Extended Drawers, Tech Reborn / RebornCore and Farmer's Delight Refabricated: compensation, transfer, real smelting, heated cooking and a two-agent item handoff.
- Keep bounded historical rules and task timelines separate from active execution. Recover after unrelated tasks and a JSON/runtime restart; require current target observations before replay.
- Use public item ports with face, dimension, input and exact reverse-compensation guards. Inspect a carried item's public menu-slot acceptance and layout without moving it.
- Select backward-relevant operators before applying planning budgets, recall archived evidence, and preserve completed history and final acceptance conditions.
- Coordinate dependency jobs with expiring resource leases and observation-backed completion. Teammate experience starts with zero local supports and no execution authority.
- Place documented explicit cache breakpoints on durable prefixes for known official models. Preserve relay/local compatibility and separate cache-write accounting. Actual model autonomy, cache hit rates and monetary savings require separate evaluation.

Native validation targets Minecraft 1.21.1, Java 21 and Fabric Loader 0.20.1. NeoForge remains experimental. A pinned combination is not evidence of universal modpack compatibility or AGI.

## v0.6.0: Observe, learn and adapt to unfamiliar content

- Inspect a visible actor's registered type, UUID and public state, a carried item's durability, tags and bounded normal tooltip, and public offers in an already opened trading menu.
- Observe side-aware public item/fluid capabilities and NeoForge energy capabilities. Preserve units and unsupported states; private NBT and hidden rule answers are excluded.
- Freeze observed prerequisites and before-state, require two physically independent supporting contexts, and bind both execution and verification to an observed transfer target. Recheck conditions before dispatch.
- Revoke learned execution on counterexamples and grant one bounded recovery window. Data reload aborts old probes and invalidates rules and predictions while retaining raw experience for reflection.
- Search verified experience for bounded plan repairs while preserving achievements. Append stable experience references only when changed, and distinguish missing cache reports from explicit zero counts.
- Gate release artifacts on component regressions, headless native Minecraft GameTests and both loader builds. External modpacks, extended gameplay and monetary savings require separate measurement.

## v0.4.0: Stages 0–9 cognition, execution, adaptation, and teamwork

This release closes stages 0–9 as one testable engineering loop:

- **Stages 0/1 — Provider and Responses negotiation**: unified `api/llm` contracts, native Responses/function-call/opaque reasoning/compaction state, capability negotiation, previous-response recovery, 404/405 Chat Completions fallback, and per-companion sessions.
- **Stage 2 — Cognitive state and persistence**: `CognitiveState` journal/projection/checkpoint, layered memory, migration, and quarantine of corrupt files; only verified evidence is restored, never cross-restart body ownership.
- **Stage 3 — World facts and action transactions**: semantic world model, transaction ledger, action pre/postconditions, and exactly-once callbacks; movement, mining, placement, equipment, transfer, complex use, and combat terminate from authoritative server state.
- **Stage 4 — Planning and recovery**: strategic acceptance conditions, rolling plans, suffix repair, resource reservations, asset reuse, fallback plans, and stall detection; reset/restart cannot resurrect a ghost plan.
- **Stage 5 — Unfamiliar-mechanism adaptation**: environment/registry fingerprints, risk- and compensation-bounded probes, competing hypotheses, counterexample invalidation, and executable adaptation skills.
- **Stage 6 — Layered retrieval**: working, episodic, semantic, procedural, and team memory are recalled by goal relevance, reliability, decay, and environment scope; repeated heartbeats do not create fake evidence.
- **Stage 7 — Context and efficiency**: stable prompt prefixes, incremental Responses history, cache keys, dynamic tool surfaces, output budgets, deadlines/circuit breakers, and local token/cache/action metrics.
- **Stage 8 — Teamwork**: owner-scoped blackboard, objective commitments, roles, support requests, TTL leases, disconnect/death/despawn reclamation, and duplicate-objective detection.
- **Stage 9 — Verification and release**: Fabric regression tests, API/memory/planning/skill/transaction/team/telemetry coverage, and JDK 21 Fabric+NeoForge builds.

These are verifiable code-level capabilities, not proof of human-level AGI, 100% compatibility with arbitrary mods, or guaranteed completion. Fabric is the supported loader and NeoForge remains experimental; from-scratch Ender Dragon completion, long survival, and modpack completion rates require long-running tests in a real world.

## v0.3.6: independent game-mode control

- Configure & Create now offers Survival, Creative, Adventure, and Hardcore. Omitted, blank, and legacy setup requests explicitly default to Survival.
- Companion Chat now includes a companion selector and a live game-mode selector. Up to three companions keep independently persisted modes across screen closes and world rejoins.
- Survival, Creative, and Adventure use vanilla `GameType`, ability synchronization, and interaction restrictions. The previous fake-player override that always reported Survival has been removed.
- Vanilla Hardcore is a world rule rather than a per-player game type. MineAgent implements its per-companion equivalent as Survival plus permanent death. A Hardcore death permanently locks that body: neither mode changes nor `/mineagent respawn` can reuse it. The owner must create a new companion instead.
- Mode changes are accepted only through an owner-authorized server request. No LLM tool exposes this operation: the AI can perceive its current mode but cannot change it.

## v0.3.5: unified setup and open model connectivity

- Create Companion and Model Connection are now one screen. Name, protocol adapter, arbitrary model ID, API key, base URL, and reasoning effort can be entered once and submitted with Save & Create; the duplicate main-menu entry is gone.
- The vendor button grid is now one compact optional preset selector. Presets only fill fields and never form a model allow-list; model IDs, endpoints, and registered adapter IDs remain editable.
- Stable `openai-compatible`, `anthropic-compatible`, and `gemini-compatible` protocol IDs are built in. The previous nine vendor IDs remain compatibility aliases, so old configuration and stored companions continue to work.
- Setup uses a dedicated atomic network payload instead of slash commands containing credentials. The server returns only a non-secret summary, failed creation cannot leave a partially updated tuple, and a saved key is not reused after the connection target changes.
- The OpenAI-compatible adapter supports unauthenticated local Ollama, LM Studio, and vLLM endpoints. Official and relay endpoints still reject missing/invalid credentials according to their own auth rules, while native Anthropic and Gemini adapters validate the key before a request. Host roots, `/v1` roots, and complete endpoints are normalized without duplicated paths.
- Anthropic Messages defaults to the official `x-api-key` header and accepts explicit `bearer:` or `api-key:` prefixes for relays; Gemini sends both Google's supported `x-goog-api-key` header and the compatible query key.
- Add the `aws-bedrock` adapter (alias `bedrock`) for AWS Bedrock Runtime Converse's unified messages and tool use. Official endpoints use SigV4. The API-key field accepts JSON AWS credentials (`accessKeyId`, `secretAccessKey`, optional `sessionToken`, `region`) or `accessKeyId|secretAccessKey|sessionToken|region`. Custom relays must explicitly use a `bearer:` or `api-key:` token; secrets are never logged. Bedrock model IDs remain open, while model-specific `InvokeModel` bodies are intentionally outside this adapter.

### Model and API compatibility

| Adapter ID | Built-in protocol | Connection scope |
| --- | --- | --- |
| `openai-compatible` | Chat Completions + function calling | Compatible official APIs, relays, aggregators, and local servers |
| `anthropic-compatible` | Anthropic Messages + tools | Anthropic and relays preserving its protocol/auth semantics |
| `gemini-compatible` | Gemini `generateContent` + function calling | Google and relays preserving its protocol semantics |
| `aws-bedrock` (`bedrock`) | AWS Runtime Converse + tool use | Official Bedrock endpoints (SigV4) or relays explicitly supporting Converse |
| `aws-bedrock-invoke` | Common Bedrock `InvokeModel` profiles (Anthropic, Nova, Mistral, Llama, Cohere, Titan) | Matching official Bedrock endpoints (SigV4) or relays implementing that profile |
| `azure-openai` | Azure OpenAI deployment + `api-version` Chat Completions | Azure official endpoints (API key or Entra bearer) and compatible relays |
| `vertex-ai` | Vertex AI Gemini `generateContent` + OAuth/service account | Official Google Vertex endpoints (OAuth) and compatible relays |
| `cohere` | Cohere Chat API v2 | Official Cohere `/v2/chat` and compatible relays |
| `ai21` | AI21 Jamba Studio Chat Completions | Official AI21 Studio endpoint and compatible relays |
| `baidu-qianfan` | Qianfan ERNIE RPC chat + OAuth | Official Baidu Qianfan endpoint (access token or AK/SK) |
| `tencent-hunyuan` | Tencent Hunyuan ChatCompletions + TC3 signing | Official Tencent endpoint (SecretId/SecretKey) and compatible relays |
| `bytedance-ark` | Volcengine Ark v3 Chat Completions | Official Ark endpoint and compatible relays |
| `ollama` | Native Ollama `/api/chat` (non-NDJSON) | Local or self-hosted Ollama servers |
| Custom registered ID | `LLMProviderRegistry` extension | Private or new protocols supplied by another mod or a later release |

MineAgent does not maintain a model-name allow-list, so a compatible endpoint's new model normally works by entering its new ID. `InvokeModel` is deliberately limited to the stable profiles listed above; other Bedrock private bodies, new vendor fields, and unpublished future protocols require a new profile or an `LLMProvider` adapter. This is a protocol boundary, not a vendor-name restriction.

## v0.3.1: complex single-player task closure

- Long-horizon plans now distinguish tactical completion from strategic acceptance. `goal_conditions` verify inventory, state, or other observable postconditions against the live semantic world model; without final evidence the plan remains `verifying` instead of declaring the owner's goal complete after one successful action.
- Rolling replanning uses `repair` to replace only the failed suffix while retaining executor-backed checkpoints, dependency identity, hard constraints, and goal acceptance. Recovery actions bind to their original milestone, and restart never restores false body ownership or a stale failure window.
- The new `plan_acquisition` tool recursively expands an item target into a bounded dependency DAG from live inventory, actually observed storage/drops, and server-registered recipes. It accounts for recipe yield, batches, alternatives, and planned surplus while leaving unknown machines and external station requirements as explicit observation leaves.
- The new asynchronous `use_item` primitive covers throws, drinking/eating, charged release, either hand, and 3D aim through vanilla `ServerPlayerGameMode`. Survival preemption reconstructs continuous use instead of reporting an interrupted action as success.
- The new `wait_for` task waits with bounded timeouts and stable observations for time, inventory, semantic facts, GUI slots, dimensions, blocks, and entity presence/absence. Machine processing and dimension transitions now have authoritative scheduler outcomes instead of ending with narrative waiting.
- Plan, wait, and item-use arguments and lifecycles are strictly guarded. A live body task or skill cannot have its plan bindings rewritten. Memory format 10 remains compatible with v1-v9 plan files.

These changes provide general long-horizon mechanisms for survival, creative, adventure, and modded tasks rather than a hard-coded Ender Dragon script. Completion rates still depend on the selected LLM, world conditions, observable mod contracts, and server rules; release builds do not replace long-duration in-game validation.

## v0.3.0: L4 unfamiliar-environment and mod-mechanism adaptation

- MineAgent now builds bounded profiles for unfamiliar registered blocks, items, menus, recipes, entities, state properties, and GUI slot layouts from real inspection results. It does not require or pretend to access a mod's private implementation API.
- Controlled experiments freeze a pre-action semantic baseline, execute one scheduler-admitted probe, and require a newer correlated state delta. Failed execution remains inconclusive, and an unchanged post-state cannot be mislearned as causal success.
- Probe choice balances expected information gain, resource/time cost, risk, reversibility, and a persistent per-subject budget. High-risk probes are rejected; medium-risk probes require an explicit verified compensation, and irreversible crafting is not used for autonomous experimentation.
- Competing hypotheses accumulate support and counterexamples. A rule needs at least two independent contexts before confirmation; contradictions lower confidence and immediately invalidate its generated adapter.
- Confirmed rules are scoped to a Minecraft/loader/mod-version/registry fingerprint. Environment changes make them stale until revalidated, while compatible knowledge survives restarts without restoring an in-flight experiment or body ownership.
- Confirmed action rules compile into ordinary skills with declared postconditions. Reuse still runs through `SkillRuntime`, the task scheduler, survival priorities, and owner-safety constraints. Only a small goal-relevant rule set enters each prompt.

This is black-box adaptation to observable game contracts, not a claim of universal support for every mod, competitive server, or arbitrary private mod API. Fabric is the supported loader; NeoForge remains experimental pending broader in-game coverage.

## v0.2.6: long-term memory, realtime decisions, and token efficiency

- Owner intent, strategic goals, and the active plan step now form one retrieval query, so prompts include only relevant semantic outcomes, verified experiences, cognitive-map POIs, and place-event memories.
- Owner goals and verified world-action outcomes persist across restarts. Routine scans, live inventory projections, and derived asset facts remain volatile, and legacy `asset:*` pollution is filtered during migration.
- Identical blocked heartbeats, cognition events, and rolling replans trigger one decision. Routine body narration no longer starts implicit LLM requests or repeatedly interrupts HTTP calls.
- Dynamic tools remain exposed for the active owner goal, discovery no longer repeats complete schemas, and routine follow-up generations use a bounded output budget while initial strategy and large builds keep the configured budget.
- Learned skills must contain a real action and pass JSON, tool-availability, and executability validation. Skill queries return only a small objective-relevant subset.

## v0.2.5: L3 closed-loop intelligence architecture

- The closed-loop skill runtime executes learned skills one step at a time, checks preconditions, waits for authoritative task outcomes, verifies semantic postconditions, and requests replanning instead of blindly replaying a failed suffix.
- The event-sourced semantic world model unifies items, facilities, actors, actions, and outcomes with time, provenance, confidence, expiry, and correlation IDs; expired observations do not become permanent facts.
- The hierarchical rolling planner maintains a strategic goal, bounded tactical window, and live execution horizon while preserving verified work and repairing only an invalid suffix.
- The unfamiliar-mechanism explorer learns unknown blocks, items, GUIs, recipes, and mod rules through one low/medium-risk falsifiable experiment; ambiguous outcomes remain inconclusive.
- Verified actions across multiple turns are consolidated into a reusable skill only after the complete plan succeeds. Failed episodes cannot pollute or overwrite a verified trace.

> [!WARNING]
> MineAgent is currently alpha software. Back up important worlds and expect issues in AI behavior, model output, and experimental platform support.

## Platform support

| Loader | Status | Notes |
| --- | --- | --- |
| Fabric | Supported | Built and run in-game; includes the complete client menu, chat, status HUD, and debug views. |
| NeoForge | Experimental | Built and shipped for testing with the shared complete menu, chat, HUD, debug views, and networking; in-game validation coverage is still lower than Fabric. |

Both loader-specific JARs are attached to each GitHub release. Install only the JAR matching your loader; never install both in one game instance.

## Features

- LLM-driven conversation, tool calls, and persistent task planning
- Multi-rate cognition: 20 Hz body/survival control, 5 Hz tactical frames, and LLM calls only for open-ended replanning
- Receding-horizon plans with hard constraints, executor evidence, explicit dependencies, and cycle validation
- Fake-player movement, pathfinding, obstacle clearing, bridging, mining, placement, and combat
- Survival chains for food, air, MLG water, defense, item pickup, following, and unsticking
- Cognitive maps, place/event memory, learned importance, relevant experience retrieval, and parameterized skill traces
- A live multi-companion team blackboard for roles, duplicate-work detection, and targeted support without waking every AI on ordinary chat
- A unified world-asset index for inventory, equipment, inspected storage, drops, and known facilities, supporting reuse/retrieve/produce decisions by item or capability
- Live registry-backed recipe discovery for vanilla and modded items instead of a fixed vanilla item list
- Multi-companion management, skins, status HUD, and path/vision debugging
- Arbitrary model IDs through OpenAI Chat Completions, Anthropic Messages, Gemini `generateContent`, and registrable third-party protocol adapters

## Installation

### Fabric (recommended)

1. Install Java 21, Minecraft 1.21.1, and Fabric Loader 0.15 or later.
2. Install Fabric API for Minecraft 1.21.1.
3. Download `mineagent-fabric-<version>.jar` from [Releases](https://github.com/bingdongni/MineAgent/releases).
4. Place it in the Minecraft `mods` directory and launch the game.

### NeoForge (experimental)

1. Install Java 21, Minecraft 1.21.1, and NeoForge 21.1.x.
2. Download `mineagent-neoforge-<version>.jar` and place it in `mods`.
3. Do not install Fabric API or the Fabric MineAgent JAR.
4. Use a test world and include the loader version and sanitized logs in feedback.

## Quick start

The first launch creates `config/mineagent.json`. Press `M`, open Configure & Create, and enter protocol, model, endpoint, key, and game mode once. Press `C` to select any online companion and change its independent game mode at any time. You can also edit the file first and then run:

```text
/mineagent quick
```

Fabric and NeoForge users can press `M` to configure and create companions. Useful commands include:

```text
/mineagent help
/mineagent providers
/mineagent models <provider>
/mineagent quick [name] [effort]
/mineagent list
/mineagent remove
/mineagent config
/mineagent reload
```

Default key bindings on Fabric and NeoForge:

| Key | Action |
| --- | --- |
| `M` | Open the MineAgent menu |
| `C` | Open companion chat |
| `H` | Toggle the status HUD |
| `P` | Toggle path debugging |
| `V` | Toggle the vision overlay |
| `N` | Toggle companion labels |

Keys can be rebound in Minecraft's Controls settings.

## Privacy, secrets, and cost

MineAgent stores API keys as plain text in `config/mineagent.json` and `<world>/data/mineagent_companions.json` so companions can be restored. Never commit or share these files. Revoke and rotate a key immediately if it is exposed.

Conversation and game context are sent to the selected LLM provider. Requests may incur charges and are governed by that provider's terms, privacy policy, and rate limits. MineAgent does not provide API credits and cannot guarantee that model output is correct or safe.

## Building

JDK 21 is required. The repository includes the Gradle Wrapper.

```bash
./gradlew verifyReleaseArtifacts
```

Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk-21'
.\gradlew.bat verifyReleaseArtifacts
```

Outputs:

- `fabric/build/libs/mineagent-fabric-0.6.0.jar`
- `neoforge/build/libs/mineagent-neoforge-0.6.0.jar`

The command starts an isolated headless test server and produces `fabric/build/gametest-results.xml` and `build/release-artifacts.sha256`. The test-only mod is excluded from release JARs. NeoForge remains experimental; native runtime scenarios currently run on Fabric.

## Modules

| Module | Responsibility |
| --- | --- |
| `api` | Loader-independent tool, task, configuration, LLM, and network contracts |
| `engine` | Agent loop, fake player, pathfinding, survival chains, tasks, and memory |
| `tools` | LLM-callable tools and built-in skill documents |
| `fabric` | Fabric bootstrap, networking, mixins, and shared client UI integration |
| `neoforge` | Experimental NeoForge bootstrap, lifecycle, networking, and shared client UI integration |

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License and disclaimer

Copyright (c) 2026 bingdongni.

MineAgent is licensed under [GNU LGPL v3.0 only](LICENSE), SPDX `LGPL-3.0-only`.

This is an unofficial community project and is not affiliated with, endorsed by, or approved by Mojang Studios or Microsoft. Minecraft is a trademark of its respective owner. Third-party LLM names and trademarks belong to their respective owners.
