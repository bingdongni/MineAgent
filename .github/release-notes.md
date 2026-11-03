MineAgent @VERSION@ is a feature release for Minecraft 1.21.1 and Java 21.

## 更新摘要

- 陌生模组的角色、物品和设备公开观察；多条件实验、结构化反思、记忆和类型约束迁移。
- 反例驱动旧经验失效、有界重学、数据重载保护，以及保留已完成历史的经验计划修复。
- 稳定经验上下文复用、缓存读写与上报覆盖率统计，兼容官方 API、中转和本地模型。
- 验收覆盖 **216 项组件测试、5 项真实 Minecraft 服务器场景，以及 Fabric/NeoForge 双加载器构建**。NeoForge 仍为实验性支持；未以这些结果宣称任意模组兼容或实际账单收益。

参见 [使用与设计](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/docs/research/mod-learning-0.6.0.md)、[本地验证记录](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/docs/research/validation-0.6.0.md) 与 [Changelog](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/CHANGELOG.md)。

## Changes

This release adds an observable learning loop for unfamiliar mod content: inspect public block capabilities, visible actor types/UUIDs, carried item tags/tooltips and opened merchant offers; propose a conditioned experiment; execute through normal admission; inspect the result; reflect; and transfer only after independent verification. Unsupported channels remain unknown, units remain explicit and private NBT is excluded.

Learned contracts include observed prerequisites, prior state, typed identity, parameters and environment. Contradictory observations revoke conflicting adapters and plan authority immediately, even when supporting a different hypothesis. One persisted recovery allowance permits two additional physical probes; two fresh independent supports are required before reuse. Successful server data reload stops pending learning and invalidates old rules and predictions. Raw audit experiences remain available for reflection.

Bounded verified-experience search can repair a blocked plan suffix while preserving achievements, unrelated branches, constraints and final goal conditions. Block and entity transfer bindings keep action and verification targets aligned, and runtime conditions are rechecked before execution. Attack variants cannot masquerade as compensated experiments.

Stable experience references are appended only when changed, retaining transient observations at the request tail. Cache accounting distinguishes missing reports from explicit zero, exposes coverage and parses malformed optional usage without discarding valid answers. Actual caching and prices depend on the official, relay or local endpoint; no universal cache control is forced on unknown services.

Memory envelopes advance to version 14 and conditioned contexts to version 2. Older rules retain audit history but require fresh verification before predictive or automatic execution reuse. This is external experience learning, not autonomous model-weight training.

Release verification now runs component regressions plus an isolated headless Minecraft test mod covering physical learning, transfer, changed-outcome relearning, actor/item observations and actual data reload. Both loader JARs must pass metadata, required-class and checksum checks; the fixture must be absent from production packages. The linked design and validation records describe the evidence and usage.

## Downloads

- `mineagent-fabric-@VERSION@.jar`: supported loader build; requires Fabric Loader and Fabric API for Minecraft 1.21.1.
- `mineagent-neoforge-@VERSION@.jar`: experimental loader build; requires NeoForge 21.1.x.
- `mineagent-source-@VERSION@.zip` and `mineagent-source-@VERSION@.tar.gz`: complete tracked source from the tagged commit, including the Gradle Wrapper, tests and documentation.
- `release-notes.md`: these release notes, including the source commit and CI run.
- `release-artifacts.sha256`: checksums for both loader JARs, both source archives and the release notes. Check this published manifest when verifying downloads; CI builds can differ from the earlier local build recorded in the repository.

Install the JAR matching your loader. Automated component tests and successful packaging do not establish AGI, zero defects, cross-project superiority, long-run gameplay success or measured monetary savings. See the repository's research and validation notes for the current evidence boundary.

API requests may cost money. MineAgent stores API keys in configuration and companion data; review `SECURITY.md` before sharing those files.
