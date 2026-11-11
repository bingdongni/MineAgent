# MineAgent v1.0.1 新用户上手与测试

[English](getting-started.en.md) · [下载](https://github.com/bingdongni/MineAgent/releases/tag/v1.0.1) · [验证范围](research/validation-1.0.1.md)

## 1. 准备独立实例

先创建独立游戏实例和测试世界，暂时只安装下表需要的模组。确认基础流程成功后再逐个加入其他模组。已有世界升级前，关闭游戏或服务器，备份完整世界及配置，然后替换旧 JAR；不要同时保留多个 MineAgent 版本。

| 项目 | Fabric（推荐） | NeoForge（实验性） |
| --- | --- | --- |
| Minecraft Java 版 | 1.21.1 | 1.21.1 |
| 实际运行的 Java | 21 | 21 |
| 加载器 | Fabric Loader >= 0.20.1 | NeoForge 21.1.x；本次回归基线 21.1.72 |
| 附加依赖 | Fabric API，验证基线 0.116.17+1.21.1 | 不需要 Fabric API |
| 安装文件 | `mineagent-fabric-1.0.1.jar` | `mineagent-neoforge-1.0.1.jar` |

安装到启动器中**当前实例**的 `mods` 目录；它可能不是默认 `.minecraft/mods`。多人游戏的客户端和服务器需要相同 MineAgent 版本和加载器，Fabric 两端也需要 Fabric API。客户端按键和界面要求客户端安装本模组。Forge、NeoForge、Fabric 的 JAR 不能混用。

源码 ZIP / TAR.GZ 用于开发和审查，不放入 `mods`。在启动器的实例 Java 设置或游戏日志中确认实际 Java 版本；终端的 `java -version` 不一定是启动器使用的 Java。

## 2. 核验下载

从同一 Release 下载 JAR 和 `release-artifacts.sha256`。PowerShell 示例：

```powershell
Get-FileHash .\mineagent-fabric-1.0.1.jar -Algorithm SHA256
Get-Content .\release-artifacts.sha256
```

将结果与清单中**同名文件**那一行比较，忽略十六进制大小写。NeoForge 改用其文件名。Linux 可以运行 `sha256sum <文件名>`；macOS 可以运行 `shasum -a 256 <文件名>`。只有下载清单中全部文件后，才使用 `sha256sum --check release-artifacts.sha256`，否则未下载的附件会报缺失。不要与其他版本或本地构建的清单比较。

## 3. 配置并创建第一个伙伴

1. 进入测试世界，站在有足够空间、地面安全的地方。按 `M`；按键冲突时到 Minecraft“选项 → 控制”重新绑定。
2. 打开“配置并创建 AI 伙伴”，给第一个伙伴取一个独立名字，例如 `TestBuddy`，选择生存或创造模式。先避开永久死亡的极限模式。
3. 选择服务**实际支持的接口协议**，填写准确模型 ID、接口地址和你自己的密钥。模型展示列表不是完整白名单，允许手动输入端点支持的模型 ID。
4. 点击创建，再执行 `/mineagent list`。世界中应出现一个伙伴，列表只新增一项。没有出现时先检查聊天中的错误和本指南的故障表，再重试。

创建伙伴即可唤醒模型，自动恢复的伙伴也可能继续调用；不需要先发送聊天。官方 API 或中转可能收费，先在服务端设置自己可接受的预算。`maxTokens` 是每次请求的输出上限，不是整个游玩的费用上限。MineAgent 不提供模型额度。

| 服务情形 | 协议 / provider | 地址与密钥 |
| --- | --- | --- |
| OpenAI Chat Completions 兼容的官方 API 或中转 | `openai-compatible` | 使用服务商提供的 API 根地址或 `/v1` 根地址；按其要求填写密钥 |
| Responses、Anthropic Messages、Gemini 等其他协议 | `/mineagent providers` 列出的对应适配器 | 选择匹配协议，不因模型名字相似就使用 Chat Completions |
| 本地 Ollama 原生接口 | `ollama` | 默认 `http://127.0.0.1:11434`，免认证时密钥留空；模型须已安装并支持工具调用 |
| 其他本地 Chat Completions 兼容服务 | `openai-compatible` | 指向实际服务；仅在服务确实免认证时留空密钥 |

地址由**运行 Minecraft 服务器的机器**访问。单人游戏是本机；远程多人服中的 `127.0.0.1` 指服务器本身，不是玩家电脑。必须使用你信任的服务器和模型服务；服务器管理员能够接触伙伴的连接配置。模型应支持结构化工具调用，否则可能只能聊天而无法可靠执行任务。

首次运行会生成 `config/mineagent.json`。也可以关闭游戏后手动编辑它；多人服要编辑服务器实例中的文件。下面是仅覆盖 LLM 部分的格式示例，其他部分会保留默认值。占位符必须替换，不是可用凭证或可用模型：

```json
{
  "llm": {
    "provider": "openai-compatible",
    "apiKey": "REPLACE_LOCALLY",
    "model": "replace-with-exact-model-id",
    "baseUrl": "https://your-provider.example/v1",
    "temperature": 0.7,
    "maxTokens": 4096
  }
}
```

文件必须是合法 JSON，不要加注释或末尾逗号。v1.0.1 读取损坏配置时保留原文件并在内存中回退到默认值；修好文件后重新加载，不要把默认值误认为原配置已生效。在游戏内手动改文件后运行：

```text
/mineagent reload
/mineagent config
/mineagent quick TestBuddy
```

`reload` 只重读全局配置，供之后创建的伙伴使用；已有伙伴及其恢复记录仍保留原连接参数。需要替换某个伙伴的服务时，先备份并主动移除该伙伴，再用新设置创建；不要把这个操作当作保留原会话的热切换。不要在聊天命令里填写 API Key，以免进入历史或服务器日志。

## 4. 完成并核对一个小任务

按 `C`，确认选中 `TestBuddy`，先发送“报告你现在的位置和背包，不要改变世界”。确认它能回复且观察与游戏相符，再发送“在安全的附近采集 3 个泥土，然后报告背包数量”。生存模式需要合适的地形、距离和可交互权限；创造模式有不同的物品规则，记录测试所用模式。

观察真实移动、方块变化和背包数量。模型声称完成不能替代游戏中的核验。遇到卡住、反复请求或费用异常，结束测试并从 `C` 聊天界面移除选中的伙伴；之后按故障表排查。一次只创建一个伙伴，有助于定位问题和控制调用。

## 5. 区分保存恢复与主动移除

| 操作 | 预期 |
| --- | --- |
| 正常退出世界 / 主人断开连接 | 保存伙伴状态并卸载身体；同一主人返回同一世界时自动恢复 |
| 退出并再次进入同一测试世界 | 不重复手动创建，先用 `/mineagent list` 检查是否恢复且只有一个同名伙伴 |
| `C` 中选中伙伴后移除 | 主动删除该伙伴的恢复记录；重新进入世界不会自动重建它 |
| `/mineagent remove` | 移除自己的主伙伴；多个伙伴时优先在 `C` 中明确选择目标 |
| `/mineagent respawn` | 复活普通模式中死亡的主伙伴；极限模式永久死亡不能复活 |

不要用 `remove` 来测试保存恢复。升级、回退或提交问题前先备份；恢复依赖同一主人 UUID 与同一世界。更换账号或服务器认证模式可能改变 UUID。v1.0.1 沿用 v1.0.0 的记忆格式 15。

## 6. 常见故障

| 现象 | 首先检查 |
| --- | --- |
| 加载前报依赖或 Java 错误 | MC 是否为 1.21.1、实际 Java 是否为 21、Loader >= 0.20.1、Fabric API 是否为 1.21.1 版本 |
| 找不到 `M` 菜单 / 命令 | 是否安装在当前实例；客户端与服务器是否都加载了正确 JAR；是否有按键冲突 |
| NeoForge 创建报 `UnsupportedOperationException` | 是否仍在使用 1.0.0 或重复旧 JAR；升级到 1.0.1 并完整重启，记录 NeoForge 精确版本 |
| 缺密钥 / 模型错误 / 401、403、404 | 核对协议、准确模型 ID、API 地址、认证和服务权限；默认配置不会自动获得模型服务 |
| 本地连接拒绝 / 超时 | 模型服务是否已启动；地址是否能从服务器机器访问；本地模型是否有足够运行资源 |
| 429 / 费用超出预期 | 检查提供者限流和账单，移除伙伴结束测试；不要反复创建重试 |
| reload 后仍使用旧模型 | 现有伙伴保存独立连接参数；重新创建才使用新的全局配置 |
| JSON 读取失败 | 保留原文件，修正 JSON 语法，再 reload；不要公开上传含密钥的配置 |
| 没有安全生成位置 / 重名 | 站到开阔安全地面，检查列表，使用不同名字；先确认伙伴是否已经存在 |
| 只聊天、不行动或动作失败 | 模型是否支持工具调用，世界是否允许交互，目标是否可见可达；从最小任务复现 |

## 7. 反馈一次真实测试

使用[真实用户测试反馈](https://github.com/bingdongni/MineAgent/issues/new?template=user_test.yml)记录成功、失败和未尝试的步骤；可稳定复现的缺陷使用 [Bug report](https://github.com/bingdongni/MineAgent/issues/new?template=bug_report.yml)。填写版本、加载器精确版本、Java、最小模组列表、单人或多人、游戏模式、协议与模型 ID、任务原文、预期及实际结果。分别核对安装、创建、小任务、退出恢复和主动移除，不把自动下载事件当作独立用户。

附最短脱敏日志或录屏，并说明能否复现。不提供 API Key、认证头、完整配置或完整世界。`config/mineagent.json`、`<世界>/data/mineagent_companions.json` 和世界内的 `mineagent_memory` 内容都可能含凭证或私人上下文。

自动化原生场景验证了创建、回滚和生产组件行为；这不代表已验证你的实际模型、自主任务成功率、任意整合包兼容或账单节省。请将真实模型测试结果与无模型工程回归分开记录。
