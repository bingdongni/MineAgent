# MineAgent

[English](README.en.md) | 简体中文

[![Build](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml/badge.svg)](https://github.com/bingdongni/MineAgent/actions/workflows/build.yml)
[![License: LGPL-3.0-only](https://img.shields.io/badge/License-LGPL--3.0--only-blue.svg)](LICENSE)

MineAgent 是面向 Minecraft 1.21.1 的 LLM 驱动 AI 伴游模组。它在服务端创建无真实网络连接的假玩家，并让 AI 通过移动、采集、挖掘、建造、战斗、合成、物品管理和生存本能与世界交互。

## v0.2.5：L3 闭环智能架构

- 闭环技能运行时逐步执行已学习技能，并在每一步检查前置条件、等待权威任务终态、验证语义后置条件；失败时停止并请求重规划，不会盲目重放剩余动作。
- 事件化语义世界模型统一记录物品、设施、实体、动作和结果，保留时间、来源、置信度、有效期及关联 ID；过期观察不会被当作永久事实。
- 分层滚动规划器维护战略目标、有限战术窗口和当前执行状态，只修复失效的计划后缀，并保留已有执行证据的步骤。
- 陌生机制探索器通过单个低风险或中风险、可证伪的实验学习未知方块、物品、GUI、配方和模组规则；模糊结果只记为“证据不足”。
- 同一计划中的多轮已验证动作会在整个计划成功后合成为可复用技能，失败轨迹不会污染或覆盖已验证技能。

> [!WARNING]
> 项目目前处于 Alpha 阶段。请先备份重要世界，并预期 AI 行为、模型输出和实验性平台支持仍可能出现问题。

## 平台支持

| 加载器 | 状态 | 说明 |
| --- | --- | --- |
| Fabric | 正式支持 | 已实际构建并在游戏中运行；包含完整客户端菜单、聊天、状态 HUD 和调试视图。 |
| NeoForge | 实验性 | 可构建并随 Release 提供；已共享完整客户端菜单、聊天、HUD、调试视图和网络功能，但实机覆盖仍少于 Fabric。 |

两个 JAR 会附在同一个 GitHub Release 中。一次游戏实例只能安装与其加载器对应的一个 JAR，不能同时安装 Fabric 和 NeoForge 版本。

## 主要能力

- LLM 驱动的聊天、工具调用和持续任务规划
- 多频率实时认知：20 Hz 身体与生存反射、5 Hz 战术情境刷新，只有开放式重规划才请求 LLM
- 带硬约束、执行证据、显式依赖和环检测的滚动计划图
- 假玩家移动、路径规划、清障、搭桥、挖掘、放置和战斗
- 饥饿、呼吸、落地水桶、怪物防御、拾取、跟随和解卡等生存链
- 认知地图、位置事件、重要性学习、相关经验检索和带真实参数的技能轨迹
- 多伴游实时团队黑板：角色承诺、重复任务检测和按需支援，不再让普通对话唤醒所有 AI
- 统一世界资产索引：追踪背包、装备、已检查容器、掉落物和已知设施，并按物品或能力决定复用、取回或制造
- 基于游戏实时注册表的配方发现与模组物品兼容，不依赖固定的原版物品清单
- 多伴游管理、皮肤、状态 HUD、路径与视觉调试
- OpenAI、DeepSeek、Qwen、GLM、Moonshot、Grok、MiniMax、Anthropic 和 Gemini

## 安装

### Fabric（推荐）

1. 安装 Java 21、Minecraft 1.21.1、Fabric Loader 0.15 或更高版本。
2. 安装适用于 Minecraft 1.21.1 的 Fabric API。
3. 从 [Releases](https://github.com/bingdongni/MineAgent/releases) 下载 `mineagent-fabric-<版本>.jar`。
4. 将 JAR 放入 Minecraft 的 `mods` 目录并启动游戏。

### NeoForge（实验性）

1. 安装 Java 21、Minecraft 1.21.1 和 NeoForge 21.1.x。
2. 下载 `mineagent-neoforge-<版本>.jar` 并放入 `mods` 目录。
3. 不要安装 Fabric API 或 Fabric 版本的 MineAgent。
4. 建议使用测试世界，并在反馈中注明 NeoForge、加载器版本和日志。

## 快速开始

首次启动会生成 `config/mineagent.json`。填写 LLM 提供者、模型和 API Key 后，可在游戏内运行：

```text
/mineagent quick
```

Fabric 和 NeoForge 用户都可以按 `M` 打开主菜单完成配置和创建。常用命令：

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

默认按键（Fabric 与 NeoForge）：

| 按键 | 功能 |
| --- | --- |
| `M` | 打开 MineAgent 主菜单 |
| `C` | 打开伴游聊天 |
| `H` | 显示或隐藏状态 HUD |
| `P` | 显示或隐藏路径调试 |
| `V` | 显示或隐藏视觉范围 |
| `N` | 显示或隐藏伴游标签 |

按键可在 Minecraft 控制设置中重新绑定。

## 隐私、密钥与费用

MineAgent 会把 API Key 以明文保存在以下位置，以便配置和恢复伴游：

- `config/mineagent.json`
- `<世界目录>/data/mineagent_companions.json`

不要提交、分享或上传这些文件，也不要在公开 Issue 中粘贴未检查的完整配置、世界存档或日志。密钥泄漏后应立即在对应 LLM 服务商处撤销并重新生成。

对话和游戏上下文会发送到你选择的 LLM 提供者。API 请求可能产生费用，并受该服务商的隐私政策、速率限制和使用条款约束。MineAgent 不提供免费额度，也无法保证模型输出始终正确或安全。

## 从源码构建

要求：JDK 21。仓库自带 Gradle Wrapper，无需单独安装 Gradle。

Windows PowerShell：

```powershell
cd MineAgent
$env:JAVA_HOME = 'C:\Path\To\jdk-21'
.\gradlew.bat :fabric:build :neoforge:build
```

Linux/macOS：

```bash
./gradlew :fabric:build :neoforge:build
```

构建产物：

- `fabric/build/libs/mineagent-fabric-0.2.5.jar`
- `neoforge/build/libs/mineagent-neoforge-0.2.5.jar`

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `api` | 平台无关的工具、任务、配置、LLM 和网络契约 |
| `engine` | Agent 循环、假玩家、路径规划、生存链、任务和记忆 |
| `tools` | LLM 可调用工具和内置技能资料 |
| `fabric` | Fabric 启动、网络、Mixin 与共享客户端界面的平台接入 |
| `neoforge` | NeoForge 实验性启动、生命周期、网络与共享客户端界面的平台接入 |

## 参与贡献

提交问题前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并使用 Issue 模板提供可复现步骤和已脱敏日志。安全漏洞请按 [SECURITY.md](SECURITY.md) 私下报告，不要创建公开 Issue。

## 许可证与声明

Copyright (c) 2026 bingdongni。

本项目以 [GNU Lesser General Public License v3.0 only](LICENSE) 发布，SPDX 标识符为 `LGPL-3.0-only`。

MineAgent 是非官方社区项目，与 Mojang Studios 或 Microsoft 无关联、授权或认可。“Minecraft”是其各自权利人的商标。第三方 LLM 服务的名称和商标归其各自所有者。
