# MineAgent

English | [简体中文](README.md)

[![Build](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml/badge.svg)](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml)
[![License: LGPL-3.0-only](https://img.shields.io/badge/License-LGPL--3.0--only-blue.svg)](LICENSE)

MineAgent is an LLM-driven AI companion mod for Minecraft 1.21.1. It creates a server-side fake player with no real client connection and lets an AI interact with the world through movement, gathering, mining, building, combat, crafting, inventory management, and survival instincts.

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
- OpenAI, DeepSeek, Qwen, GLM, Moonshot, Grok, MiniMax, Anthropic, and Gemini providers

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

The first launch creates `config/mineagent.json`. Set the LLM provider, model, and API key, then run:

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
./gradlew :fabric:build :neoforge:build
```

Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk-21'
.\gradlew.bat :fabric:build :neoforge:build
```

Outputs:

- `fabric/build/libs/mineagent-fabric-0.2.6.jar`
- `neoforge/build/libs/mineagent-neoforge-0.2.6.jar`

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
