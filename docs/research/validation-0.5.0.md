# v0.5.0 发布构建验证记录

验证日期：2026-11-02（北京时间）。本记录对应 `gradle.properties` 中的 `0.5.0`，开发基线为 `701250a950154667212d3ae1be3b3197e9ce9470`。研究、工具用法与边界见[持续学习说明](continual-learning.md)。

## 本地构建结果

- Temurin JDK / javac 21.0.13+9，Gradle Wrapper 8.10.2，Windows。
- 清理输出后，Fabric 与 NeoForge 均完成生产源码编译和打包。
- Fabric 执行共享组件回归：33 个套件、156 项测试，失败 0、错误 0、跳过 0。
- NeoForge 没有独立测试源；其验证范围为编译、打包和产物结构检查。
- `git diff --check` 通过。

执行命令：

```powershell
.\gradlew.bat --no-daemon clean verifyReleaseTag prepareReleaseNotes verifyReleaseArtifacts '-Prelease_tag=v0.5.0'
```

`verifyReleaseArtifacts` 依赖两个加载器的 `build`，包含 Fabric 测试。测试覆盖与受控 32 步场景详见[开发构建记录](validation-0.5.0-alpha.1.md)；正式版使用相同生产组件重新执行了全部测试。

## 发布一致性

- 版本参数、标签参数、生成的发布说明、两个精确 JAR 文件名及包内加载器版本均为 `0.5.0`。
- 将标签参数改为 `v0.5.0-alpha.1` 时，`verifyReleaseTag` 按预期拒绝发布。
- 每个 JAR 均检查 Provider 接口、经验预测器、规划图、技能绑定器、许可证和对应加载器元数据。
- 发布说明由 `.github/release-notes.md` 中的 `@VERSION@` 渲染；校验清单从实际产物生成。
- 发布工作流重新进行干净构建和上述检查；普通版本发布为正式 Release，带 `-` 的版本才标为 prerelease。
- 远端执行状态以 [GitHub Actions](https://github.com/bingdongni/MineAgent/actions) 为准；发布附件以 [v0.5.0 Release](https://github.com/bingdongni/MineAgent/releases/tag/v0.5.0) 为准。

| 本地 Windows 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `mineagent-fabric-0.5.0.jar` | 1496662 | `c761d854cc1a77fdcecff10fbe3df7b270e8230b1940486f97b914866206e3ad` |
| `mineagent-neoforge-0.5.0.jar` | 1503241 | `fb0e59f5bbf41b11b9130439e6d5e3fa519b836a763a23fb15c4922da0b4c68a` |

本表只标识本次本地构建，不承诺跨平台得到相同字节。GitHub Actions 会从标签源码重新构建，下载附件后应使用同一 Release 中的 `release-artifacts.sha256` 校验。源码归档由 GitHub 根据发布标签提供。

## 验证边界

本次没有进行真实 Minecraft 长期运行、未知模组盲测、跨项目公平比较、视觉控制或收费模型缓存账单实验。组件回归通过不能证明绝对零缺陷、AGI、实机学习优势或成本下降百分比。长任务仍应声明顶层 `goal_conditions`，不能用一次动作成功替代整项任务的验收。

记忆封装格式为 13。旧步骤缺少 `prerequisites` 时维持原行为；只有调用方命名上下文的旧机制规则，需要新的物理独立证据才能自动复用。新格式尚不承诺由旧版引擎读取。
