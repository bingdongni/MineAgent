# MineAgent UI Design System

## 1. Design Philosophy

MineAgent 的 UI 遵循 **"vanilla-first, subtle enhancement"** 原则：
- 以 Minecraft 原版风格为基础，不破坏沉浸感
- 通过细节增强（点阵纹理、轻微倾斜、气泡边框）提升辨识度
- 所有 UI 元素必须保证在屏幕内可见可操作

## 2. Color Palette

### 2.1 Primary Colors (与原版兼容)

| 用途 | 颜色 | RGB |
|---|---|---|
| 主面板背景 | 深灰半透明 | `0xE0202020` |
| 次级面板背景 | 中灰半透明 | `0xE0303030` |
| 边框（默认） | 浅灰 | `0xFF606060` |
| 边框（强调） | 金色 | `0xFFFFC000` |
| 标题文字 | 金色 | `0xFFFFC000` |
| 副标题 | 灰白 | `0xFFAAAAAA` |
| 正文 | 白 | `0xFFFFFFFF` |
| 成功状态 | 绿 | `0xFF55FF55` |
| 警告状态 | 黄 | `0xFFFFFF55` |
| 错误状态 | 红 | `0xFFFF5555` |

### 2.2 Chat Bubble Colors

| 角色 | 气泡背景 | 边框 | 文字 |
|---|---|---|---|
| 玩家 (Owner) | `0xE00A4A0A` (深绿) | `0xFF2AA02A` | `0xFFB0FFB0` |
| AI (Companion) | `0xE00A2A4A` (深蓝) | `0xFF2A6A9A` | `0xFFA0D0FF` |
| 系统 (System) | `0xE04A4A0A` (深黄) | `0xFFA0A02A` | `0xFFFFFFA0` |

## 3. Typography

- **字体**: Minecraft 原版 (`Minecraft.getInstance().font`)
- **标题**: §6§l (金色粗体) + 装饰边 `═══`
- **副标题**: §7 (灰色)
- **标签**: §7 (灰色小字)
- **正文**: 默认白色
- **状态**: 颜色编码（绿/黄/红）

## 4. Layout

### 4.1 Spacing System

| 用途 | 像素 |
|---|---|
| 屏幕边距 (MARGIN) | 10 |
| 组件间距 | 4 |
| 行间距 (ROW_SPACING) | 18 |
| 输入框高 | 18 |
| 按钮高 | 20 |
| 标准按钮宽 | 200 |
| 网格按钮宽 | 98 |

### 4.2 Boundary Safety Rules

**关键规则**：所有 UI 元素必须保证不超出屏幕边界。

1. **X 轴边界检查**: `x = clamp(x, MARGIN, width - elementWidth - MARGIN)`
2. **Y 轴边界检查**: `y = clamp(y, MARGIN, height - elementHeight - MARGIN)`
3. **面板尺寸自适应**: 面板宽度不超过 `width - 2*MARGIN`，高度不超过 `height - 2*MARGIN`
4. **动态适配**: 当屏幕尺寸变化时，所有元素重新计算位置（init 方法）

### 4.3 Grid Layout

- 主菜单使用 2 列网格布局
- Provider 选择使用 3 列网格布局
- 网格居中对齐：`gridStartX = centerX - gridTotalWidth / 2`

## 5. Visual Enhancements

### 5.1 Dot Grid Background (点阵网格)

在面板背景上叠加点阵纹理，增加层次感：
- 点大小: 1×1 像素
- 点间距: 6 像素
- 点颜色: `0x20FFFFFF` (低 alpha 白)
- 只在面板内部绘制，不超出边界

### 5.2 Tilted Decoration (倾斜装饰)

在标题角落或装饰元素上添加轻微倾斜：
- 倾斜角度: ±1° 到 ±2°（不影响可读性）
- 使用 `poseStack.mulPose(Quaternionf)` 实现
- 仅用于装饰元素（如标题前的图标），不用于文字本身
- 倾斜后必须重新检查边界

### 5.3 Chat Bubble (聊天气泡)

替代纯文本消息，使用气泡样式：
- 圆角矩形（用 fill 模拟，4 角留空）
- 半透明背景 + 实色边框
- 最大宽度: `screenWidth - 2*MARGIN - 16`
- 文字自动换行或截断
- 玩家消息靠左，AI 消息靠右（或用颜色区分）

### 5.4 Panel Border (面板边框)

增强面板边框设计：
- 外边框: 1px 深色 `0xFF404040`
- 内边框: 1px 亮色 `0xFF808080`
- 标题带: 顶部 12px 高的色带，背景 `0xFFFFC000` alpha 0x40
- 角落装饰: 4 个角各一个 3×3 的小方块强调

## 6. Component Library

所有可复用组件在 `MineAgentUiComponents` 类中：

| 方法 | 用途 |
|---|---|
| `drawPanel()` | 绘制带边框+点阵+标题带的面板 |
| `drawDotGrid()` | 在指定区域绘制点阵 |
| `drawChatBubble()` | 绘制聊天气泡 |
| `drawTiltedIcon()` | 绘制轻微倾斜的装饰图标 |
| `drawSectionHeader()` | 绘制分节标题 |
| `clamp()` | 边界检查工具 |

## 7. Application Scope

以下 Screen 应用新设计：
- `SpawnCompanionScreen` - 主面板 + 输入区
- `MineAgentMainMenuScreen` - 主菜单面板 + 按钮
- `CompanionChatScreen` - 聊天气泡 + 输入区
- `ConfigEditScreen` - 配置编辑面板
- `HelpScreen` - 帮助信息面板
- `ModelSelectScreen` / `SkinSelectScreen` - 选择列表面板

## 8. Do's and Don'ts

### Do
- 保持 vanilla 风格为基础
- 用半透明背景保证游戏画面可见
- 所有元素做边界检查
- 颜色对比度足够（WCAG AA）
- 装饰元素不干扰功能

### Don't
- 不要使用过厚的边框（>2px）
- 不要使用过大的倾斜（>2°）
- 不要让装饰元素遮挡功能控件
- 不要超出屏幕边界
- 不要使用抗锯齿（保持像素风格）
