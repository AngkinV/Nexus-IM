# 设置界面：全部功能实现方案

上一版方案把 9 项列为「不做」。用户要求全部实现。这份文档给出每一项的
真实技术落点、数据流和验收标准。

前提不变：**界面上显示的必须是真实生效的值**，不能是摆设。

---

## 总览

| # | 设置项 | 技术落点 | 数据流 | 难度 |
|---|---|---|---|---|
| 1 | 超感毛玻璃透明度 | CSS 变量 + backdrop-filter | 渲染进程，CSS 变量覆盖 | 低 |
| 2 | 视窗缩放与字体微调 | Electron `webContents.setZoomFactor` | IPC → 主进程 → 全窗口 | 低 |
| 3 | 渲染帧率上限 | rAF 节流（统一 time gate） | 渲染进程，3D 循环 | 中 |
| 4 | 视口渲染分辨率 | `renderer.setPixelRatio` / `setSize` | 渲染进程，3D 组件 | 中 |
| 5 | 后台失焦智能挂起 | `browser-window-blur/focus` + rAF 暂停 | IPC 广播 → 3D 循环 | 中 |
| 6 | 触感与极简音效 | Web Audio 合成（无需素材） | 渲染进程 | 中 |
| 7 | 程序坞/托盘角标 | `app.dock.setBadge` + `app.setBadgeCount` | IPC → 主进程 | 低 |
| 8 | 本地端加密存储 | Web Crypto AES-GCM 包一层 storage | 渲染进程 | 中 |
| 9 | 清空缓存 | `session.clearCache` + 统计占用 | IPC → 主进程 | 低 |

---

## 1. 超感毛玻璃透明度

**现状**：24 处 `backdrop-filter`，散落在各组件，值写死（`blur(28px)`、`blur(36px)` 等）。

**做法**：
- 在 `settings` store 增加 `glassIntensity: 0–100`（默认 100）
- 全局 CSS 变量 `--glass-blur-scale`，由 App 根节点动态设置
- 把 24 处 `backdrop-filter` 改成 `backdrop-filter: blur(calc(28px * var(--glass-blur-scale)))`
- 值为 0 时完全关闭 backdrop-filter（`@supports` 不支持的降级也一并处理）

**真实生效**：拖动滑块，毛玻璃强度立刻变化，可见可测。

**验收**：设 0 → header 无模糊；设 100 → 模糊恢复；截图对比像素差异。

---

## 2. 视窗缩放与字体微调

**现状**：无。

**做法**：
- `settings.zoomFactor: 50–200`（默认 100）
- 新增 IPC `window:set-zoom`，主进程调 `mainWindow.webContents.setZoomFactor(v/100)`
- 启动时读 localStorage 并应用（避免每次打开都要重设）
- 变更后广播给所有窗口

**真实生效**：整个应用界面按比例缩放，包括字体、间距、图标。

**验收**：设 150% → 测 `window.devicePixelRatio` 或元素尺寸变化；设 100% 恢复。

**注意**：`setZoomFactor` 会影响布局，设置页自身也会缩放。要在设置页顶部给个「重置为 100%」的快捷入口，防止用户调到 200% 后找不到路。

---

## 3. 渲染帧率上限

**现状**：7 个 `requestAnimationFrame` 循环，全部不限帧。

**做法**：
- `settings.fpsCap: 30 | 60`（默认 60）
- 新建 `src/utils/renderLoop.js`：统一节流的 rAF 包装器
  ```js
  export function createFrameLoop(callback, getCap) {
    let last = 0
    const tick = (now) => {
      const interval = 1000 / getCap()
      if (now - last >= interval) { last = now; callback(now) }
      raf = requestAnimationFrame(tick)
    }
  }
  ```
- 3D 组件（`CompanionAvatar3D`、`tavernStage`、`abyssStage`、`ComputerEye`、`tavernEntranceScene`）改用这个包装器
- 30 FPS 时每帧间隔 33.3ms，60 FPS 时 16.7ms

**真实生效**：可以用 `performance.now()` 采样帧间隔验证。

**验收**：设 30 → 实测帧间隔 ≥33ms；设 60 → ≈16.7ms。

---

## 4. 视口渲染分辨率

**现状**：`renderer.setPixelRatio(Math.min(devicePixelRatio, 2))`，写死。

**做法**：
- `settings.renderScale: 'native' | '1080p' | '720p'`
- 3D 组件读这个值：
  - `native` → `Math.min(devicePixelRatio, 2)`
  - `1080p` → 目标高度 1080，`pixelRatio = 1080 / canvasHeight`
  - `720p` → 同理，目标 720
- 调用 `renderer.setPixelRatio(v)` 后必须 `setSize(w, h, false)` 重新应用

**真实生效**：`renderer.getPixelRatio()` 返回新值，画布实际像素数变化。

**验收**：三种模式分别读 `renderer.getPixelRatio()`，值不同且符合预期。

---

## 5. 后台失焦智能挂起

**现状**：无。

**做法**：
- `settings.suspendOnBlur: boolean`（默认 true）
- 主进程监听 `browser-window-blur` / `browser-window-focus`，通过 IPC 广播 `window:focus-changed`
- 渲染进程一个全局 composable `useWindowFocus()` 提供 `isFocused`
- 3D 循环检测 `isFocused === false` 时跳过渲染（仍保留 rAF 但不执行 draw）
- 失焦超过 `suspendDelay`（默认 30 秒）才挂起，避免轻微切换就停

**真实生效**：切到其他应用 → 3D 停止渲染（CPU 占用下降）；切回 → 恢复。

**验收**：失焦 35 秒后读 3D 组件的帧计数，应停止增长；聚焦后恢复增长。

---

## 6. 触感与极简音效

**现状**：无音频素材。

**做法**：**用 Web Audio API 合成**，不需要任何音频文件。
- `settings.soundEnabled: boolean`（默认 true）
- 新建 `src/utils/uiSound.js`：
  ```js
  // 消息提示：两个短促正弦音
  function playMessage() {
    const ctx = getAudioContext()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.frequency.setValueAtTime(880, ctx.currentTime)
    osc.frequency.exponentialRampToValueAtTime(1320, ctx.currentTime + 0.08)
    gain.gain.setValueAtTime(0.06, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.15)
    osc.connect(gain).connect(ctx.destination)
    osc.start(); osc.stop(ctx.currentTime + 0.15)
  }
  ```
- 三种音效：消息（双音上行）、发送（单音短促）、错误（双音下行）
- 在 `websocket.js` 收到消息、发送成功、错误提示处调用
- 提供「试听」按钮，点了就响

**真实生效**：点试听能听到声音；收到消息时有提示音。

**验收**：`AudioContext` 被创建且 `oscillator.start()` 被调用（可 mock 验证）。

**注意**：浏览器要求用户交互后才能播放音频。首次播放前要 `ctx.resume()`，在用户第一次点击时初始化。

---

## 7. 程序坞/托盘角标

**现状**：无。

**做法**：
- 新增 IPC `app:set-badge`，参数为数字
- 主进程：
  ```js
  if (process.platform === 'darwin' && app.dock) app.dock.setBadge(n > 0 ? String(n) : '')
  else app.setBadgeCount(n)  // Windows/Linux
  ```
- 渲染进程：未读总数变化时调用（已有 `unreadTotal` computed）
- `settings.badgeEnabled: boolean` 控制是否启用

**真实生效**：Dock 图标上出现红色数字。

**验收**：有未读时 `app.dock.setBadge` 被调用且参数正确；清零后传空字符串。

---

## 8. 本地端加密存储

**现状**：localStorage 明文。

**做法**：
- `settings.encryptedStorage: boolean`（默认 false，用户主动开启）
- 新建 `src/utils/secureStorage.js`：
  - 首次开启时生成 AES-GCM 256 位密钥，用 `crypto.subtle.generateKey`
  - 密钥本身存在 `IndexedDB`（非 localStorage），因为 CryptoKey 不能序列化到 localStorage
  - 写入：`crypto.subtle.encrypt({name:'AES-GCM', iv}, key, encode(value))` → base64 存 localStorage
  - 读取：反向解密
  - 存储格式：`{"__encrypted": true, "iv": "...", "data": "..."}`
- 开启时把现有明文数据迁移成密文；关闭时反向迁移
- 影响范围：`settings`、`companion.petVisible` 等本地偏好

**真实生效**：开启后 localStorage 里看到的是密文；应用功能不受影响。

**验收**：开启 → 读 localStorage 原始值，应无明文；重启后设置仍能正确读回。

**注意**：这是「本地静态数据加密」，不是端到端加密。密钥在同一台机器的 IndexedDB 里，
能访问这台机器的人仍可解密。界面上要如实说明，不能宣称「零知识」。

---

## 9. 清空缓存

**现状**：无。

**做法**：
- 新增 IPC `app:cache-info`（返回占用字节数）和 `app:clear-cache`
- 主进程：
  - `session.defaultSession.getCacheSize()` 拿大小
  - `session.defaultSession.clearCache()` 清理
  - 同时可选清 `clearStorageData({storages:['serviceworkers']})`
- 界面显示真实占用（如 `184 MB`），点击后清理并刷新数字
- 清理完弹确认提示

**真实生效**：按钮旁的数字来自 `getCacheSize()`，清理后变小。

**验收**：清理前后 `getCacheSize()` 返回值下降。

---

## 实施顺序

按依赖和风险从低到高：

1. **第 1 组（低风险，纯前端）**：① 毛玻璃、⑨ 清空缓存、⑦ 角标
2. **第 2 组（Electron IPC）**：② 缩放、⑤ 失焦挂起
3. **第 3 组（3D 渲染）**：③ 帧率、④ 分辨率
4. **第 4 组（新模块）**：⑥ 音效、⑧ 加密存储

每组做完立刻验证，再进下一组。

---

## 分区归属

设置页现在的 5 个分区不变，新增项填入：

- **通用与界面**：+ 超感毛玻璃透明度、+ 视窗缩放与字体微调
- **桌面宠物与 3D 渲染**：+ 渲染帧率上限、+ 视口渲染分辨率、+ 后台失焦智能挂起
- **消息与通知提醒**：+ 触感与极简音效、+ 程序坞/托盘角标
- **AI 角色与安全存储**：+ 本地端加密存储、+ 清空缓存

这样每个分区的卡片数量和设计稿一致（1-3 列网格）。

---

## 验收总表

| # | 项 | 验证方式 | 通过标准 |
|---|---|---|---|
| 1 | 毛玻璃 | 截图对比 + `getComputedStyle().backdropFilter` | 值随滑块变化 |
| 2 | 缩放 | 元素尺寸测量 | 150% 时尺寸 ×1.5 |
| 3 | 帧率 | `performance.now()` 采样帧间隔 | 30 档 ≥33ms，60 档 ≈16.7ms |
| 4 | 分辨率 | `renderer.getPixelRatio()` | 三档返回不同值 |
| 5 | 失焦挂起 | 帧计数 | 失焦后停止增长，聚焦后恢复 |
| 6 | 音效 | mock AudioContext | `start()` 被调用 |
| 7 | 角标 | 真实 Dock 观察 + IPC 调用记录 | 数字正确 |
| 8 | 加密 | 读 localStorage 原始值 | 无明文，能正确解密 |
| 9 | 清缓存 | `getCacheSize()` 前后对比 | 数值下降 |
