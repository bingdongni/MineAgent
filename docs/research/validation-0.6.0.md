# MineAgent v0.6.0 验证记录

验证日期：2026-11-03。对象为 `codex/v0.6.0-mod-adaptation` 分支的 v0.6.0 改动。本文记录本地构建与测试证据。GitHub Release 工作流另行测试、构建并校验发布包；下方本地校验和仅对应本轮本地文件，在线附件以 Release 附带的 `release-artifacts.sha256` 为准。

## 环境和复现

| 项目 | 本次环境 |
| --- | --- |
| 系统 | Windows / PowerShell |
| Java | JDK 21.0.13+9 |
| Gradle Wrapper | 8.10.2 |
| Minecraft | 1.21.1 |
| Fabric | Loader 0.15.11、Fabric API 0.102.0+1.21.1 |
| NeoForge | 21.1.72，实验性加载器 |
| 模型连接 | 组件测试使用本地 HTTP 服务；原生调度测试断言模型调用为零 |

最终验证命令：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
.\gradlew.bat --offline --no-daemon clean verifyReleaseTag prepareReleaseNotes verifyReleaseArtifacts '-Prelease_tag=v0.6.0'
```

`--offline` 使用本机已缓存依赖。首次构建需联网取得 Minecraft、加载器及 Gradle 依赖，可以去掉该选项。Linux/macOS 使用 `./gradlew`，无需 PowerShell 环境设置。

`verifyReleaseArtifacts` 依赖组件测试、双加载器构建以及 `verifyGameTests`。原生测试任务限时五分钟，每次启动前移除旧 XML；校验器要求所有指定场景存在且没有失败、错误或跳过。CI 的 Build 与 Release 工作流均运行同一门禁并保留测试报告。

## 组件回归

40 个套件、216 项测试；失败 0、错误 0、跳过 0。包含 v0.5.0 原有 156 项回归与本版新增 60 项，不把原生游戏场景混入这个计数。

| 范围 | 本版新增或强化的验证 |
| --- | --- |
| 条件学习 | 缺失和矛盾前提、派发前条件变化、参数/条件签名隔离、连续状态组合、重复物理证据、不同物品产出隔离 |
| 新角色和物品 | 类型与 UUID 约束、运行 ID 校验、错误类型拒绝、短期实例状态、不可用字段撤销、有界物品说明持久化和替换 |
| 重学与反思 | 确认规则的反例只补充一次预算、执行失败不续杯、配额跨检查点保存、替代结果学习、支持新假设时撤销冲突旧规则、接口缺口与机制反例分开 |
| 数据重载 | 无活动状态、多次重载、待执行实验取消、迟到任务/工具回调、预测清空、规则资格失效、预算重置、时钟回退 |
| 规划和执行 | 目标与效果共同绑定、执行前重检、反例撤销准备中的技能、修复保留历史/其他分支/顶层目标、不制造缺失资源、跨阶段组合 |
| 兼容与恢复 | 旧上下文格式降级、新格式往返、重启后需新观察、显式清空学习同时撤销旧技能资格 |
| 缓存和用量 | 经验引用只在变化时追加、历史压缩后重发、伙伴隔离、未报告与明确零值、报告覆盖率、畸形指标不丢回答、整数溢出、不同协议计数归一 |
| 工具边界 | 攻击不能伪装成可补偿实验、状态改变探针不能低报成本、观察到的原生类型不能改名新建预算、提案次数用尽时不再推荐 |

组件测试使用受控语义状态或本地 HTTP。它们验证实现行为，不能单独证明接入 LLM 会自发选对下一步。

## 真实 Minecraft 服务器场景

测试源码位于 `fabric/src/gametest`，由 Fabric GameTest 框架启动实际 Minecraft 1.21.1 无界面服务器。五项均为必需测试：

| 原生场景 | 验证内容 |
| --- | --- |
| `learnsTransfersAndStopsAfterRuleChange` | 测试专用陌生机器隐藏触发规则；通过生产观察工具读取公开状态，在两台物理设备上获得支持，生成技能并迁移到第三台；改变隐藏规则后撤销旧技能，使用仅一次恢复配额在两个独立设置中学到新结果，预测器恢复为两份新证据且预算归零 |
| `observesPublicActorAndRejectsWrongUuid` | 真实村民的注册类型和 UUID 观察；不公开任务/NBT，错误 UUID 在任务派发前拒绝 |
| `observesNewItemWithoutDumpingPrivateComponents` | 实际携带测试模组的新物品，验证剩余耐久、有界正常说明及截断标记，不输出任意数据组件 |
| `nativeDataReloadInvalidatesLearnedRules` | 调用真实 `MinecraftServer.reloadResources`，经过 Fabric 生命周期回调；旧适配技能移除，旧预测清空；异步等待不阻塞服务器线程 |
| `runsObservationsViaTheAgentLoopAndReadsPublicStorage` | 直接经过真实 `AgentLoop.runMechanismProbe`，读取公开箱子接口；16 个视图限制与截断、不写入容器、观察结果不要求身体动作后条件标记、失败读取立即结束为证据不足、模型调用为零 |

规则变化测试使用生产学习器、`InspectBlockTool`、`SkillRuntime` 和原生方块交互。评估器负责布置设备、改变隐藏规则和验收，学习器不读取隐藏映射。箱子测试由评估器改变物品数量，验证的是只读观察调度与数据投影，不把读操作描述成导致物品增加的因果干预。

本轮整体复核修复了实际发现的问题：条件证据未进入探索评分、不同新结果的重学窗口未重建、竞争结果规则未及时撤销、不同产出 ID 混用、清空学习残留旧技能资格，以及 `run` 对只读探针错误套用动作后条件验收。最后一项由新增原生调度场景覆盖。测试夹具同时改用合法可堆叠物品验证库存变化。

## 产物和发布检查

- `mineagent-fabric-0.6.0.jar` 与 `mineagent-neoforge-0.6.0.jar` 均由 JDK 21 编译并打包。
- 校验两个 JAR 的实际存在、加载器身份/版本、核心 API/学习/规划/观察类与许可证；拒绝未展开的版本占位符。
- 断言测试模组的 `LearningGameTests.class` 不进入任一生产 JAR。
- 渲染发布说明，要求 `v0.6.0` 标签与 `gradle.properties` 完全一致，生成 SHA-256 清单。
- 构建日志存在原有 Java API 和 Gradle 弃用警告；本记录不宣称零警告或 Gradle 9 兼容。

最终清理构建成功，用时 2 分 39 秒；31 个可执行任务中 25 个执行、6 个复用缓存。耗时只记录本机本轮构建，不是游戏反应速度基准。

| 本轮产物 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| `mineagent-fabric-0.6.0.jar` | 1,559,001 | `802bd922fb96d7803b0c090d33db607a676897a37579d144c9ca2c7861f2e176` |
| `mineagent-neoforge-0.6.0.jar` | 1,565,788 | `c41b04357e1f6a261fe48c04f1d81e67fbc8ae36fa349a9f79a6d2db042baf5f` |

校验和对应本轮实际构建文件；重新打包后的文件应以新生成清单为准。

发布工作流额外从精确的标签提交生成带版本目录前缀的源码 ZIP 和 TAR.GZ，将更新说明作为附件，并在上传前逐项验证五个数据附件的 SHA-256。源码归档只包含 Git 跟踪文件；构建目录、世界、配置和密钥不进入源码包。Release 正文与更新说明附件记录源提交及本轮 CI 链接。

可检查的报告与文件：

```text
fabric/build/test-results/test/TEST-*.xml
fabric/build/reports/tests/test/index.html
fabric/build/gametest-results.xml
fabric/build/gametest/logs/latest.log
fabric/build/libs/mineagent-fabric-0.6.0.jar
neoforge/build/libs/mineagent-neoforge-0.6.0.jar
build/release-notes.md
build/release-artifacts.sha256
```

## 尚未由本轮测试建立的结论

NeoForge 已完成编译、打包与元数据检查；原生游戏场景在 Fabric 运行，不能写成两个加载器均完成同等实机验收。没有进行任意外部整合包的留出盲测、客户端专有 GUI 操作、长时间生存、旧任务保持统计、跨模型能力比较、付费 API 缓存/账单实验或危险反应延迟基准。

本版支持范围取决于公开观察与合法动作通道，不保证所有模组兼容。规则重载当前保守失效全部学习规则，尚无精确数据依赖索引。经验学习与条件规划不构成 AGI、算法首创、跨项目优越性或零缺陷证明。后续对照实验的方法见 [持续学习研究](continual-learning.md)。
