# v1.0.0 本地验证记录

日期：2026-11-03。以下是提交 `134bab4` 对应实现的本地开发验收记录；后续发布文档合并不改变该实现。这里记录本机实际执行结果，GitHub 发布构建另以 Release 中的源码提交和 CI 链接为准。

## 环境与结果

Windows / PowerShell，Temurin Java 21.0.13+9，Gradle 8.14.3，Fabric Loom 1.13.6，Minecraft 1.21.1，Fabric Loader 0.20.1，Fabric API 0.116.17+1.21.1；NeoForge 21.1.72 构建。

| 验证层 | 通过结果 | 证据位置（仓库根目录起） |
| --- | --- | --- |
| 组件回归 | **268 项 / 45 个测试类，零失败、零跳过** | `fabric/build/test-results/test/TEST-*.xml` |
| 无外部模组的原生基线 | 5 个场景 | `fabric/build/gametest-results.xml` |
| storage 环境 | 6 个场景：5 个基线 + 1 个真实抽屉场景 | `fabric/build/modpack-storage-results.xml` |
| industry 环境 | 7 个场景：5 个基线 + 2 个真实工业场景 | `fabric/build/modpack-industry-results.xml` |
| combined 环境 | 12 个场景：5 个基线 + 7 个真实模组场景 | `fabric/build/modpack-combined-results.xml` |
| 发行包验收 | Fabric、NeoForge 构建、版本/必需类/许可证检查、测试及第三方代码排除、SHA-256 检查通过 | `build/release-artifacts.sha256` |
| 自动汇总 | XML 场景完整性、源码新鲜度、无模型传输尝试和产物哈希检查通过 | `build/validation-1.0.0.json` |

原生场景共执行 **30 次**，去重为 **12 个场景**。不能把在不同环境重复的基线算成 30 个独立能力任务。组件数较 v0.6.0 的 216 项增加 52 项。

用于核对的源码摘要覆盖 381 个实现、测试、资源与构建文件，文本换行归一化为 LF：

```text
source_sha256_normalized = a878bb76d6f2f8f3b66cad5896c4f1756bba3af89c66bbf458389ace19a31cc1
modpack_lock_sha256      = 24834b7660a8f7c7a3d7a068086a7a2e4caf7874a05b5b4b9226dfb7809187d7
```

摘要用于绑定本地证据，不是整个 Git 仓库或源代码压缩包的哈希；文档和 Python 辅助脚本不在该源码摘要范围内。源文件变化后，汇总器会拒绝早于源码的原生报告，要求重新执行。

## 原生验证的实际动作

- **真实抽屉**：在两台 Extended Drawers 实例上使用生产观察和受预算约束的 `transfer_port` 实验；真实数量变化支持规则，精确反向补偿还原原有物品。之后执行无关任务 B，将经验序列化并恢复到新的 AgentLoop，再迁移到第三台未参与学习的抽屉 A。缺少新目标观察或观察面不一致时，迁移被拒绝；重新观察上表面后执行和效果验证成功。
- **工业炉回退**：Tech Reborn 未配置自动化端口拒绝插入且不消耗输入。通过普通游戏交互打开真实菜单，读取公开槽位接收性与索引，移动矿石和燃料，等待真实游戏 tick，观察并取出一个实际冶炼产物。输入已消耗，玩家库存得到产出。
- **电能观察**：真实电炉通过 Team Reborn Energy 公开接口报告非零容量、实际零储量及 `team_reborn_energy` 单位。没有把未知能力或 Fabric 单位强行解释为 FE。
- **烹饪**：真实 Farmer's Delight 锅放置于热源上，用实际大米和碗，通过公开菜单索引交互，等待真实配方加工，读取并取出实际熟食。匿名碗槽通过公开布局定位；物品接收性不被当成配方角色证明。
- **跨模组搬运**：Farmer's Delight 大米进入 Extended Drawers 后分批取出，总数守恒。
- **双伙伴交接**：同一主人的两个真实库存和 AgentLoop 协同；前驱完成前无法认领后继，持有租约时另一个伙伴的端口写入被拒绝，供给完成由真实抽屉数量验证，接收完成由接收方实际库存验证。
- **既有基线**：继续验证测试专用陌生方块的物理学习、迁移、规则变化、角色 UUID、物品说明、生产观察链和真实数据重载。测试专用方块不被表述为外部真实模组。

这些动作运行在真实 Minecraft 无界面服务器和作者原始模组代码中。测试脚本给出确定的实验/行动顺序，并暂停模型规划轮次；观察、移动、规则学习、条件检查、适配技能和团队状态使用生产模块。没有把脚本制定的路线算作模型自主发现路线。

## 组件覆盖及整体复核

新增和扩展的回归覆盖：历史规则压力和稀有任务保持、JSON 恢复、旧条件降级、指纹变化/重载不能复活冷规则、严格检索预算、任务动作与最终验收区分、任务开始前的旧事实不能验收、队友候选零支持及两次本地独立验证、已有反例不能被分享覆盖、共享失效与清理、工作依赖/租约/接管/资源排他、完成任务的有界退役，以及无关规则不能挤掉需要的规划算子。

端口回归检查动作与效果的坐标一致性、面与维度绑定、实际输入、完整反向补偿、非整数参数和非法方向。缓存回归检查真实 Responses 序列化后的消息边界、Chat 内容块格式、动态尾部排除、未知中转/本地/旧模型兼容及无额外预热；既有缓存统计与缺失上报回归继续通过。

还复核了记忆格式 15 的读写和重置、生命周期回收、默认无外部模组构建、两种加载器产物、工作流依赖及源码改动。新版 Fabric API 消除了旧组合中缺失 `c:eggs` / `c:mushrooms` 的数据标签错误。依赖和 Gradle 仍有既有的弃用/开发告警；没有隐藏测试失败来获取通过结果。

## 本地构建产物

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| `mineagent-fabric-1.0.0.jar` | 1,605,962 | `9519192988351b401e812bc7ad2611e5186c7271cbdc34ff34feafe357a31e10` |
| `mineagent-neoforge-1.0.0.jar` | 1,612,159 | `90873a8b4a2c6b94b376655bc760cc32261d972bfa5b5705601d99985ae88ee0` |

构建时间、工具链或后续 CI 重建可能改变 JAR 的字节；下载时应核对同一批附件随附的校验清单。真实测试模组及其内嵌依赖没有打包进这两个 JAR。

## 复现命令

首次运行需要联网下载公开依赖和 Gradle 工具链。依赖已经存在时，可加 `--offline` 复跑；以下不含密钥或付费调用。

```powershell
py -3 scripts/provision-modpack-tests.py --profile storage
py -3 scripts/provision-modpack-tests.py --profile industry
py -3 scripts/provision-modpack-tests.py --profile combined
.\gradlew.bat --no-daemon -Pmodpack_profile=storage :fabric:test :fabric:verifyModpackGameTests
.\gradlew.bat --no-daemon -Pmodpack_profile=industry :fabric:test :fabric:verifyModpackGameTests
.\gradlew.bat --no-daemon -Pmodpack_profile=combined :fabric:test :fabric:verifyModpackGameTests
.\gradlew.bat --no-daemon prepareReleaseNotes verifyReleaseArtifacts
py -3 scripts/summarize-validation.py
```

CI 包含三个固定环境的可复用工作流；发布必须先通过该矩阵，再构建源码和双加载器附件。本报告记录本地结果，云端构建结果与最终附件校验值参见对应 Release 的 GitHub Actions 链接和随附清单。

## 结论的边界

本轮按用户选择，没有真实模型端点或付费预算：**真实模型自主探索/创新/长程任务成功率、服务端缓存命中率、响应延迟改善比例和实际账单节省均未测量**。测试证明的是执行和学习基础设施在所列条件下的行为。

只验证上述固定版本和有限代表场景；没有完成任意大型整合包、多天持续生存、未知模组 NPC 的整套玩法或完整科技树通关。NeoForge 已构建但没有同等的真实模组运行矩阵，继续标为实验性。通过当前回归不能保证任何环境零缺陷，也不能据此证明 AGI；后续应使用固定模型与预算开展独立留出任务评测。
