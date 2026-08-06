MineAgent 0.3.0 is an alpha release for Minecraft 1.21.1 and requires Java 21.

This release implements the L4 unfamiliar-environment and mod-mechanism adaptation loop on top of the v0.2.6 event-sourced world model and verified skill runtime. Successful inspections build bounded profiles for registered blocks, items, menus, recipes, entities, state properties, and GUI slots. Controlled experiments compare hypotheses under a persistent resource budget, freeze a pre-action baseline, require a newer correlated observation, and keep execution failures inconclusive.

State-changing experiments require an explicit verified compensation. High-risk probes and irreversible autonomous crafting experiments are rejected. A rule needs support from at least two independent contexts before confirmation; counterexamples lower confidence and invalidate generated adapters. Confirmed action rules compile into ordinary postcondition-checked skills, so reuse remains subject to SkillRuntime, scheduler admission, survival priorities, and owner-safety constraints.

Rules are scoped to a fingerprint of Minecraft, loader, loaded mod versions, and observable registries. A changed environment marks old rules stale until revalidated. Version 9 memory remains backward-compatible with v1-v8 files and restores compatible knowledge without restoring an in-flight experiment or body ownership. Goal-conditioned recall exposes only a small relevant rule subset to limit prompt tokens.

The release includes 59 passing Fabric regression tests. These cover causal baselines, independent support, contradictions, stale-environment revalidation, compensation requirements, structured GUI transitions, restart behavior, probe selection, planning, memory, skills, and realtime cognition. This is black-box adaptation to observable game contracts; it does not claim universal support for arbitrary private mod APIs, all modpacks, competitive servers, or autonomous full-game completion.

## Downloads

- `mineagent-fabric-0.3.0.jar`: supported build. Requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-0.3.0.jar`: experimental build. Requires NeoForge 21.1.x and should be tested in a backed-up world.

Install exactly one JAR matching your mod loader. Do not install the Fabric and NeoForge artifacts together.

The NeoForge build uses the same complete client UI, key bindings, HUD/debug views, and loader-neutral network contract as Fabric. It remains experimental because its in-game compatibility coverage is smaller. Include the loader, exact versions, reproduction steps, and sanitized logs in bug reports.

API requests may cost money. MineAgent stores API keys as plain text in its configuration and companion save file; review `SECURITY.md` before sharing logs, configuration, or worlds.
