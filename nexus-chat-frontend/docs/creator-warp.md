# 造物主之眼 · 冥渊吞噬 —— 进入 AI 会话的转场

设计来源：`stitch_ios_style_ai_messenger (8)`（独立 shader 舞台）与 `(9)`（消息主视图 + 转场编排）。

## 文件

| 文件 | 作用 |
|---|---|
| `src/composables/useCreatorWarp.js` | 转场状态机（全窗口单例）：`idle → gaze → collapse → arrived → leaving → idle` |
| `src/components/warp/CreatorEyeWarp.vue` | 全屏舞台：WebGL 眼睛、碎片、激光/激波、HUD、抵达卡片。Teleport 到 `<body>` |
| `src/components/warp/creatorEyeShader.js` | 设计稿 GLSL（逐字未改）+ 渲染器（只在转场打开时绘制） |
| `src/components/warp/warpMessages.js` | 转场文案（zh / en），组件内局部 i18n，不占用全局 locale 文件 |
| `src/views/dev/WarpPreview.vue` | 仅开发环境的预览页 `/dev/warp`，不需要登录和后端；生产构建不打包 |

## 用法

```js
import { useCreatorWarp } from '@/composables/useCreatorWarp'

const warp = useCreatorWarp()
warp.trigger({
  onEnter: () => { /* 用户在抵达卡片点「进入」后才执行：真正切到 AI 会话 */ },
  onCancel: () => { /* 可选：ESC / 返回工作台 */ },
  target: '角色名' // 可选，覆盖默认目的地名
})
```

页面上挂一个 `<CreatorEyeWarp />` 即可。没有挂载舞台时 `trigger` 会直接执行 `onEnter`，调用方不会被卡住。

时间线（来自设计稿）：0ms 凝视 → 820ms 眨眼坍缩（碎片、激光、激波、画面抖动）→ 1800ms 抵达卡片。
`skip()` 跳过凝视直接眨眼；`cancel()` 任意时刻中断；`confirm()` 仅在抵达后有效。

## 接入点（Prism 外壳）

- `views/Main.vue`：挂载 `<CreatorEyeWarp />`；新增 `enterAi(commit)` 并 `provide('enterAi')`。`setActiveView('ai')` 和 `openAiAssistant()` 都经过它，两者原来的函数体没有改（`setActiveView` 的原实现改名为 `applyActiveView`）。来电时自动 `cancel()`。
- `components/chat/ChatList.vue`：点击 AI 助手行走 `enterAi`（已打开时再点仍是原来的收起行为）。
- `components/layout/MiddlePanel.vue`：空状态的「唤醒 AI」按钮走 `enterAi`。
- `router/index.js`：仅开发环境的 `/dev/warp` 路由。
- 没有改动：`NavRail.vue`、`CommandPalette.vue`、`TopBar.vue`、`AgentSessionList.vue`。导航的 AI 项、命令面板、⌘J、⌘4 调用的都是 Main 里那两个函数，自动生效。

规则：

- 从 AI 外面进来才播转场；已在 AI 视图里，或 AI 会话已经打开时，直接执行、不重播。
- 各入口确认后的结果和接入前一致：导航 / ⌘J / ⌘4 / 命令面板 / 「唤醒 AI」切到 AI 视图并打开助手（手机宽度下只切到会话列表）；会话列表里的 AI 行原地打开助手、列表留在「聊天」。
- 转场打开期间，应用自己的快捷键（⌘K、⌘J、⌘G、⌘1~4 等）不生效，键盘归舞台所有。

新增入口时：`inject('enterAi', null)`，然后 `enterAi(() => { /* 原来打开 AI 的代码 */ })`。

## 与设计稿不同的地方

- **抵达后的按钮**：设计稿是 `alert`，这里「进入酒馆角色对话」真正打开 AI 助手会话。
- **「深渊跃迁」**：设计稿里这个按钮实际不起作用（转场进行中再次触发会被忽略），这里实现为跳过凝视。
- **激光/激波层级**：设计稿用了 `z-25`，Tailwind 默认没有这个类，所以原型里这一层其实压在眼睛下面；这里按意图放在碎片之上、HUD 之下。
- **碎片**：去掉了 drop-shadow（原型里被关键帧的 `filter: blur()` 覆盖，本来也不生效），110 个节点更轻。
- **shader 时钟**：每次转场从 0 开始，保证出现时眼睛是睁开的；关闭后停止绘制并清空画布。画布像素比上限 1.5。
- **图标**：抵达卡片的 `blur_on` 用内联 SVG 点阵绘制，不依赖图标字体。
- **字体**：使用外壳已加载的 Geist Sans 和等宽字体。
- **未接入的触发点**：设计稿顶栏的「造物主之眼」演示按钮、输入框的「AI 润色优化」按钮，以及导航项上的「造物主」徽标，Prism 外壳里没有，未添加。
- **底部标题区加了一块暗底**：设计稿里标题、副标题和进度条直接压在眼睑的亮纹上，实际截帧读不清，加了一圈径向渐变的黑底。
- **压暗强度随身后界面亮度变化**：深色外壳用设计稿的 `brightness(0.2)`；浅色外壳下同样的值会变成中灰（瞳孔发灰、界面文字透出来），改用 `0.04`。打开时对界面上 5 个点实际绘制的背景色取样，取中位数判断。
- **新增**：`prefers-reduced-motion` 直接到抵达卡片；无 WebGL 时退化为暗场 + HUD；焦点陷阱与焦点归还；来电打断。
- **目的地名**：默认值取自设计稿文案，没有绑定真实角色数据，可通过 `trigger({ target })` 传入。
