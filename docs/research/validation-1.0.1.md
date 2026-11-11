# v1.0.1：新用户上手修复与发布验证

验证日期：2026-11-11。Minecraft 1.21.1，Temurin Java 21.0.13，Gradle 8.14.3。Fabric Loader 0.20.1、Fabric API 0.116.17+1.21.1；NeoForge 21.1.72。NeoForge 保持实验性，其他 21.1.x 版本需分别验证。

## 修复及回归证据

1. **NeoForge 创建、移除和回滚**：包含 @timesonchange 的 [PR #1](https://github.com/bingdongni/MineAgent/pull/1)。生产工厂使用 PlayerList 的可变底层列表，避免修改 NeoForge 的只读公开视图。原生 NeoForge 场景首先确认该视图确实不可变，再测试生存和创造模式下创建、移除及同一 profile 重建，检查玩家列表、UUID 索引、世界实体和假连接关闭。
2. **重复创建的资料归属**：新增场景在原生服务器复现：重复创建被拒绝后，原来的身体和 UUID 索引仍在，但观察者收到错误的 Player Info 撤下数据包。修复仅撤销本次创建实际注册过的 profile。另一个场景让非玩家实体先占用 UUID，强制后续实体注册失败，核验新列表/索引/客户端资料回滚且原实体不被删除。此场景中的 UUID 冲突警告是有意的故障注入。
3. **配置文件保护**：旧行为在 JSON 语法错误或根节点不是对象时，用默认值覆盖磁盘文件。修复保留原文件，内存仍回退到默认值。新增六项测试覆盖首次生成、部分配置、旧版空字段、安全默认值、非法限制和损坏文件保留；不启动模型服务。
4. **安装与操作说明**：中英文文档与构建属性和 Fabric 元数据核对，修正 Loader 0.15 的错误要求和旧 0.6.0 产物名；补齐实例目录、双端安装、协议与本地地址、即时调用费用、小任务核验、重读配置、退出恢复及主动移除的区别。

回归有效性也经过反向检查：临时将列表解析退回只读 getter 后，三个 NeoForge 原生场景均失败；反转资料回滚的归属判断后，两个相关场景失败。测试结束后恢复正确源码，重新通过完整验证。配置文件覆盖问题也先复现再修复。

## 本地验证结果

| 验证项 | 结果 |
| --- | --- |
| 组件回归 | 274 项，46 个测试类，失败/错误/跳过均为 0 |
| Fabric 基础原生环境 | 8 个场景通过 |
| Fabric storage 真实模组环境 | 9 个场景通过 |
| Fabric industry 真实模组环境 | 10 个场景通过 |
| Fabric combined 真实模组环境 | 15 个场景通过 |
| NeoForge 原生生命周期 | 3 个场景通过 |
| 双加载器构建及产物检查 | 通过 |

合计 **45 次原生场景执行、15 个不同场景名称**。基础和生命周期场景在不同环境重复运行；这些执行次数不是 45 个独立能力任务或真实用户。三个生命周期场景在两个加载器均运行。真实模组矩阵沿用 SHA-512 锁定的 Extended Drawers 3.0.4+mc.1.21.1、Tech Reborn / RebornCore 5.11.19、Farmer's Delight Refabricated 1.21.1-3.2.8，第三方模组仅用于测试，不包含在 MineAgent JAR 中。

`scripts/summarize-validation.py` 对 XML、场景名称、源文件新鲜度、未发生模型传输尝试及两个 JAR 的校验值进行检查。本次本地结果位于 `build/validation-1.0.1.json`。其归一化源集合（387 个源码、测试、锁文件和构建文件）的 SHA-256 为：

```text
818b177a48478ff899b8f1557fb6f6a38ae82711ad05bc2c1f5a95f2e24525d7
```

这个值只标识脚本列出的源码与构建输入集合，不是整个仓库或最终发布附件的哈希。Release 以标签对应的完整 Git 提交为准；最终附件请使用随同下载的 `release-artifacts.sha256`，不要拿本地构建哈希替代 CI 附件清单。

## 复现

Windows PowerShell（已安装 JDK 21）：

```powershell
.\gradlew.bat --no-daemon verifyReleaseTag prepareReleaseNotes verifyReleaseArtifacts '-Prelease_tag=v1.0.1'
foreach ($profile in @('storage', 'industry', 'combined')) {
    python scripts/provision-modpack-tests.py --profile $profile
    if ($LASTEXITCODE -ne 0) { throw "Provisioning failed: $profile" }
    .\gradlew.bat --no-daemon "-Pmodpack_profile=$profile" :fabric:verifyModpackGameTests
    if ($LASTEXITCODE -ne 0) { throw "Native tests failed: $profile" }
}
python scripts/summarize-validation.py
```

Linux/macOS 使用 `./gradlew`。首次运行需要网络下载构建依赖；NeoForge 开发运行还可能下载官方 Minecraft 资源。缓存齐全后可以添加 `--offline`。原生服务器只在各加载器的 `build` 目录创建测试世界。

## 发布门禁与证据位置

- `.github/workflows/build.yml` 构建双加载器并运行原生门禁；storage、industry、combined 三个环境由可复用工作流独立执行。
- `.github/workflows/release.yml` 等待真实模组矩阵成功，再从干净检出验证标签、组件测试、双加载器原生测试和构建；检查版本元数据、生产类、许可及测试/第三方代码排除。
- Fabric 报告：`fabric/build/test-results/test/TEST-*.xml`、`fabric/build/gametest-results.xml`、`fabric/build/modpack-<profile>-results.xml`。
- NeoForge 报告：`neoforge/build/gametest-results.xml` 和 `neoforge/build/gametest/logs/latest.log`。CI 保留这些原生证据。
- Release 包含 Fabric JAR、NeoForge JAR、完整跟踪源码 ZIP/TAR.GZ、更新说明和 SHA-256 清单。更新说明附源提交及对应 CI 链接；源码归档取自同一提交。

## 范围边界与真实用户测试

原生生命周期测试直接调用生产假玩家工厂，并捕获实际游戏资料数据包；同一 profile 重建验证注册清理，不等于已经覆盖真实账号断线恢复的所有网络、UI 和整合包组合。组件测试保留存档读取与兼容覆盖，记忆格式仍为 15。

本次没有使用付费模型，没有真实模型自主采集任务、缓存命中率或账单节省评测，也没有把脚本化场景当作 AGI 能力。NeoForge 没有与 Fabric 相同的真实模组矩阵，自动化不包含人类客户端界面逐项点击验证。不能由这些通过结果推出任意模组兼容或不存在缺陷。

真实用户应按[中文指南](../getting-started.md)或[English guide](../getting-started.en.md)，独立核对安装、创建、小任务的实际结果、退出重进恢复及主动移除，并使用[测试反馈表](https://github.com/bingdongni/MineAgent/issues/new?template=user_test.yml)分别记录成功、失败和未尝试步骤。不要分享密钥、完整配置、记忆或世界文件。

原生测试接入参考 [NeoForge 1.21.1 Game Tests 官方文档](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)。本仓库的 ModDevGradle 固定为 2.0.28-beta，运行配置按该版本的实际 DSL 核验，未直接套用新版本属性名。
