# v0.5.0-alpha.1 开发构建验证记录

验证日期：2026-11-02（北京时间）。功能版本目标为 v0.5.0；本记录描述本地 `codex/adaptive-learning-runtime` 工作区，基于 `701250a950154667212d3ae1be3b3197e9ce9470` 开发，尚未对应新的远端发布。研究、工具用法与边界见[持续学习说明](continual-learning.md)。

## 最终结果

- Temurin JDK / javac 21.0.13+9，Gradle Wrapper 8.10.2，Windows。
- 从干净构建目录编译 Fabric 与 NeoForge，均成功打包。
- Fabric 执行共享组件测试：33 个套件、156 项测试，失败 0、错误 0、跳过 0。相较上一轮增加 20 项测试；另一个输入边界测试内覆盖了 12 个真实工具入口案例。
- NeoForge 没有独立测试源；对它的验证是生产源码编译、打包与产物检查，不是完整游戏运行。
- `git diff --check` 通过。

最终执行命令：

```powershell
.\gradlew.bat --no-daemon clean verifyReleaseTag prepareReleaseNotes verifyReleaseArtifacts '-Prelease_tag=v0.5.0-alpha.1'
```

这条命令通过 `verifyReleaseArtifacts` 的依赖执行两个加载器的 `build`，包含 Fabric 测试。

## 新增验证范围

| 范围 | 实际检查 |
| --- | --- |
| 长程前提 | 区分未知、反例与满足；模型自报运行中不能绕过；材料消耗不抹掉已接纳执行和完成历史 |
| 后续修复 | 保留已完成节点，加入补充材料节点，恢复依赖后继续；独立就绪分支可以先执行 |
| 调度与持久化 | 显式优先级、单位耗时关键依赖链、过期观察、检查点不恢复旧就绪权限、新目标不借用旧证据、空白 ID 不分裂绑定 |
| 32 步受控场景 | 第 9 步之后导入检查点，第 17 步之前资源丢失；连续 50 次相同阻塞检查不重复发出修复信号；恢复材料后完成，每步只执行一次 |
| 事实与参数 | 完整库存观察为被关注的缺失物品提供零数量；未知外部对象不推断不存在；非法和非有限数字返回错误，避免自动拆箱空指针 |
| 缓存协议 | 明确持久历史边界；Anthropic 断点停在工具结果上，合并后的实时 user 块仍不进入该缓存前缀；旧调用方默认不启用额外断点 |

受控场景使用生产规划器与语义状态接口，不是模型自主生成并执行的 Minecraft 实机通关。HTTP 测试只连接本地测试服务器，不连接收费模型端点。

## 发布一致性检查

- `gradle.properties` 的版本、标签参数、生成的发布说明、精确 JAR 名称以及包内加载器版本相同。
- 向 `verifyReleaseTag` 传入 `v0.4.0` 时，任务按预期失败，要求 `v0.5.0-alpha.1`。没有创建或修改 Git 标签。
- 生成的说明不残留 `@VERSION@` 或旧的 `0.3.6` 文案。
- 两个 JAR 都包含 API Provider 接口、经验预测器、长程规划器、技能绑定器、许可证及对应加载器元数据。
- GitHub 工作流采用同一组校验任务，并按已核对的版本选择上传文件；工作流本身未在 GitHub Actions 上执行。

| 产物 | SHA-256 |
| --- | --- |
| `fabric/build/libs/mineagent-fabric-0.5.0-alpha.1.jar` | `1b256cc47187eea58b08d4d376ca17327fc5f44c57c10630259ba46c9ad40cee` |
| `neoforge/build/libs/mineagent-neoforge-0.5.0-alpha.1.jar` | `44355b7e990bfd0445a3361b7fa3e1f79469d932afccfc9265cfac09278ee834` |

生成的校验清单位于 `build/release-artifacts.sha256`，发布说明位于 `build/release-notes.md`。这些是当前本地构建的哈希，不表示跨平台构建必然得到相同字节。

## 验证边界

当前没有真实 Minecraft 长期运行、未知模组盲测、跨项目公平比较、视觉控制或服务端缓存账单实验。本记录不证明绝对零缺陷、AGI、实际命中率提升或成本下降百分比。步骤完成仍需执行器证据，长任务应声明顶层 `goal_conditions`；任意自然语言成功条件尚不能自动转换为完整的可观察验收。

升级会将记忆封装格式推进到 13。旧步骤没有 `prerequisites` 时维持原行为；旧机制规则若只有调用方命名上下文，则需重新取得物理独立支持才能自动复用。新格式尚不承诺由旧版引擎读取。
