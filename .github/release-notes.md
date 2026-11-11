MineAgent @VERSION@ targets Minecraft 1.21.1 and Java 21.

## 更新摘要

- 发布 @timesonchange 在 [#1](https://github.com/bingdongni/MineAgent/pull/1) 贡献的 NeoForge 创建修复：注册、移除和回滚使用实际可变玩家列表，避免对只读公开视图操作导致异常。
- 修复重复创建被拒绝时错误撤下原有伙伴客户端资料的问题；失败回滚保留原有实体及 UUID 索引。
- 损坏或非对象 JSON 配置保留在磁盘上供修正，内存回退到默认值，不再用默认配置覆盖用户文件。
- 修正 Fabric Loader 最低要求为 0.20.1，写明 Fabric API / NeoForge 验证基线，并补齐双语安装、配置、创建、小任务核验、退出恢复、主动移除与故障排查流程。
- 将双加载器原生创建/回滚测试纳入发布门禁，保留现有组件回归和 Fabric 真实模组组合矩阵。

[中文上手指南](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/docs/getting-started.md) · [English guide](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/docs/getting-started.en.md) · [验证记录 / Validation](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/docs/research/validation-1.0.1.md) · [Changelog](https://github.com/bingdongni/MineAgent/blob/v@VERSION@/CHANGELOG.md)

## Changes

This patch includes the NeoForge PlayerList fix from @timesonchange (#1). Native regression scenarios now create, remove and recreate the same player profile on both loaders, verify that a rejected duplicate cannot remove the existing player's client profile, and force world-registration failure to check list/UUID/client rollback without deleting the unrelated entity occupying that UUID.

Malformed configuration is preserved for repair instead of being overwritten with defaults. Regressions cover clean first launch, partial/legacy configuration, invalid limits and damaged JSON. Existing connection tuples and memory format 15 remain compatible with v1.0.0.

The Chinese and English guides identify the correct loader requirements, active instance directories and matching client/server installations. They distinguish provider protocols, local server addresses, new-companion settings from existing sessions, normal rejoin restoration from deliberate removal, and per-call output limits from total API budgets. Creating or restoring a companion can immediately start model requests.

## Validation and scope

Release gates run the component suite, native Fabric learning/lifecycle scenarios, native NeoForge lifecycle scenarios, and checksum-locked Fabric storage, industry and combined real-mod environments. Both JARs are checked for version metadata, required production classes, license text and exclusion of test/third-party classes.

These are engineering regressions without paid model evaluation. They do not establish autonomous task success rates, arbitrary modpack compatibility, zero defects or billing savings. Fabric is supported; NeoForge remains experimental and does not have the same real-mod matrix. Real users should begin with a backed-up test world and verify actual game results.

## Install / Upgrade

Stop the game/server, back up the world and configuration, remove the previous MineAgent JAR, and install the matching loader artifact on both multiplayer sides. Do not install both loader JARs together.

- Fabric: Minecraft 1.21.1, Java 21, Fabric Loader **0.20.1 or later**, Fabric API for 1.21.1 (validated baseline **0.116.17+1.21.1**).
- NeoForge: Minecraft 1.21.1, Java 21, NeoForge **21.1.x** (native regression baseline **21.1.72**); no Fabric API. Other 21.1.x versions need their own runtime testing.

## Downloads

- `mineagent-fabric-@VERSION@.jar`: Fabric mod.
- `mineagent-neoforge-@VERSION@.jar`: experimental NeoForge mod.
- `mineagent-source-@VERSION@.zip` and `.tar.gz`: complete tracked source including wrapper, tests and documentation; do not put these archives in `mods`.
- `release-notes.md`: these notes plus the source commit and CI provenance.
- `release-artifacts.sha256`: checksums for both JARs, both source archives and release notes. Compare the exact matching filename from this Release; local builds may differ.

API requests may cost money. Do not share `config/mineagent.json`, companion save files, private memory or unsanitized logs. See SECURITY.md and the first-run guides before reporting issues.
