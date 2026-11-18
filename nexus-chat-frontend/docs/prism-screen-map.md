# Prism UI — 六屏对照表（已逐字节校验）

来源目录：`/tmp/prism/`（由 `stitch_ios_style_ai_messenger` 三个 code.html 拆出的 body/shell 片段）
拆分方式：`bodyN.html` = 第 N 屏正文，`shellN.html` = 第 N 屏外壳（侧边栏 + 顶栏），`cN.html` = shell + body 合并后的整屏。

| 序号 | 界面 | 文件 | 判定依据（硬证据） |
|---|---|---|---|
| 1 | 未选中对话（空状态） | `c0.html` | 只有左栏 `Secondary Sleek Conversation Panel`；主区无消息流，只有 `threejs-container-ANIMATION_11`（计算机之眼）+ `STANDBY MODE` + 三个 CTA（⌘N / ⌘G / ⌘J）。没有 `messageStream` / `composerInput`。 |
| 2 | 消息对话主视图 | `c1.html` | 左栏 `PANE 1: Chat Session List (w-80)` + 右栏 `PANE 2: Right Active Conversation Window`；存在 `id="messageStream"`、`id="composerInput"`、`id="sendBtn"`；占位符 `输入消息，Enter 发送，Shift + Enter 换行...` |
| 3 | 联系人通讯录 | `c2.html` | 左栏标题 `通讯录`；占位符 `搜索联系人、部门、职位...`；右栏 `Contact Detail Profile Panel`（林晓，Hero + Bento 双栏 + 共同群组 + 活跃度 sparkline）；存在 `id="contactDirectoryList"` |
| 4 | 群组管理界面 | `c3.html` | 左栏标题 `群组空间` + 计数 `28`；占位符 `筛选群聊名称、ID或成员...`；右栏群详情（`产品体验评审组` / `ID: G-9042-PX` / 成员矩阵 / 共享文件 / 公告存照）；存在 `group-list-container`、`create-group-modal` |
| 5 | AI 会话界面 | `c4.html` | 左栏标题 `会话历史` + `4k / 128k` 令牌计；按钮 `开启新会话 ⌘N`；右栏 `Prism AI 引擎` + `INFERENCE READY` + 模型切换 `GPT-4o 旗舰模型`；存在 `id="prompt-input"`、`id="btn-send-prompt"`、`id="chat-stream-viewport"` |
| 6 | 消息对话主视图的弹窗 | `c5.html` | 正文与 `c1.html` **完全一致**（校验：`c5` 去掉 `<!-- Interactive Command Palette Modal -->` 之后的前缀与 `c1` body 逐字符相同，唯一差异是搜索框加了 `id="triggerPaletteFromSearch"` 与 `cursor-pointer`）。多出的部分就是 `id="commandPaletteModal"` 命令面板：搜索框 + 分类胶囊（全部 / 快捷指令 / 常用快捷键）+ 4 条动作 + 6 条快捷键 + 空状态 + 底部提示条。 |

## 关键结论

- 第 6 屏不是独立页面，而是第 2 屏的叠加层。所以实现上：`c1` 是基础页，`c5` 只贡献 `CommandPalette` 组件，挂在全局（⌘K 唤起）。
- `c0` 与 `c1` 共用同一个左栏结构（`消息列表` + 搜索 + 四个筛选胶囊 + 会话行 + 底部状态），差别只在右主区：`c0` 是空状态眼睛动画，`c1` 是消息流。→ 左栏抽成一个组件，右主区按「有/无 activeChat」切换。
- `c3` 是唯一有双栏 + 右栏内部再分「成员矩阵 / 设置列」的屏；`c2` 右栏是 Hero + 12 栅格 Bento。两者右栏结构完全不同，不能复用。

## 设计系统（六屏一致，不可改）

- 纯单色：`primary #ffffff`、`surface #131316`、`surface-container-lowest #0e0e11`、`on-surface #e4e1e6`、`on-surface-variant #c4c7c8`、`outline-variant #444748`。
- 字体：Geist Sans（headline / label / body）、JetBrains Mono（`font-code`，所有数字、ID、时间、快捷键）、Material Symbols Outlined（图标）。
- 圆角：`DEFAULT .25rem`、`lg .5rem`、`xl .75rem`、`full 9999px`。
- 间距 token：`space-xs .25rem`、`space-sm .5rem`、`space-md 1rem`、`space-lg 1.5rem`、`space-xl 2.5rem`。
- 左栏固定 `w-80`（320px），`c3` 用 `w-80 lg:w-[350px]`；外壳侧栏 `w-64`，顶栏 `h-14 left-64`。
- 图片一律 `grayscale contrast-125`；无彩色渐变、无阴影色偏（仅 `shadow-lg/xl/2xl` 黑色投影）。
