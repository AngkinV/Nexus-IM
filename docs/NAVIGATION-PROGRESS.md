# 返回导航：实现、验证与合并边界

> 用户请求发送于 2026-11-21 23:51（UTC+8），修补跨日继续。
> 当前工作区：`illegal-marlin @ 76dc7bf`，未提交。
> 目标：返回实际来源页/模块，而不是按目录取父级、固定跳主界面或重置成聊天。
> 状态：源码和隔离回归已完成；原项目尚未应用，不等于真实 Web/Electron 已验收。
> 原项目 `/Users/anglv/Nexus` 有另一线程的设置硬化暂存修改。仅只读参考，生成定向增量补丁，不整文件覆盖、不修改其服务。

## 1. 根因与修补

| 编号 | 原因 | 本轮处理 |
|---|---|---|
| NAV-01 | Main 的模块只存 `ref('chats')`；重建后丢来源 | 既有 `/main` 用 query 表达五个原模块，`activeView` 从路由派生 |
| NAV-02 | 酒馆来源模块/原聊天只在组件内存 | query 保留 `returnView/returnChat`；返回消费历史或使用明确模块兜底 |
| NAV-03 | 页面直接 `router.back()`，无历史可能不动或走错 | 统一安全 helper，应用内历史返回、冷打开兜底、排除认证/外部目标 |
| NAV-04 | 去联系人/创建聊天都固定 `/main` | 显式 `view=contacts` / `view=chats`，不依赖默认值 |
| NAV-05 | 登录/登出/Setup 用 push 导致认证历史回跳 | 登录完成、登出、Setup 完成改 replace，保留安全的请求目标 |
| NAV-06 | Setup 伪造用户和登录状态 | 删除假身份/假 token 写入；Setup 要求认证，只用现有真实资料接口 |
| NAV-07 | Esc 穿透弹窗/编辑，搜索框返回语义丢失 | 可见弹层优先，输入/IME 隔离；搜索先清空、再失焦、再返回 |
| NAV-08 | 延迟转场可能覆盖更新后的导航意图 | 主界面意图代次；改选其他模块/离开 Main 时取消旧转场意图 |
| NAV-09 | 他人资料旧请求失败会后退新资料页 | 请求代次、目标 ID、viewer、路径、卸载检查；只处理当前请求 |

没有新增页面、路径或 store，没有修改后端和转场实现。Setup 保存真实昵称/头像使用原有接口，不新建账户系统。

## 2. 文件与职责

相对于 `nexus-chat-frontend/`：

- `src/router/navigation.js`：安全目标、历史位置跟踪、返回合并、模块 query、认证 guard、键盘/弹层判断、意图代次。
- `src/router/index.js`：保持七个原有基础记录；Setup 增加 requiresAuth；接入公共 guard/helper。
- `src/views/Main.vue`：路由驱动模块、酒馆来源和聊天恢复、联系人/群组选择 query、异步转场保护、等待模块导航后再打开原有对话框。
- `src/views/Settings.vue`：安全返回、通用“返回”标签、搜索/输入/弹层 Esc、登出 replace。
- `src/views/Profile.vue`：安全返回，保留原有 tab query。
- `src/views/UserProfile.vue`：安全返回与旧请求隔离，明确去聊天模块；自身 ID 比较兼容数字/字符串。
- `src/views/Login.vue`：真实登录后替换到安全请求目标。
- `src/views/Setup.vue`：不生成身份或登录状态；以原账号调用资料/头像接口，保存后替换到主界面。
- `src/components/profile/SocialModule.vue`：明确去联系人模块。
- `src/router/navigation.test.mjs`：42 条导航和事件处理回归。

## 3. 路由状态契约

既有 Main query 字段：

| 字段 | 含义 | 校验 |
|---|---|---|
| `view` | chats/contacts/groups/ai/companion | 非法、数组等回退 chats |
| `returnView` | 酒馆来源模块 | 合法原模块且不是 companion，否则忽略 |
| `returnChat` | 原聊天 ID | 只恢复当前账号已有列表中的对象，不凭 ID 创建/获取权限 |
| `contactId/groupId` | 原目录选择 | 正安全整数，非法值忽略 |

主导航通常 push，选择/视图上下文 replace；返回用历史 go 或安全 replace，不 push 一个反复往返的新入口。

这些只是导航状态，不是账号设置，不包含姓名、消息、凭据或新增持久化 store。头像、模型、人设和后端会话不由 query 改写。

## 4. 安全返回与认证

- 跟踪应用内历史位置，使用 Vue Router 的原生 state 或内存位置。
- 同页面重复条目跳过；Profile 的 tab 仍作为来源完整路径保留。
- 有安全来源时回来源；直接打开无安全来源时去 Main。酒馆可以限定返回原 Main 模块。
- 外部 URL、协议相对地址、反斜线/控制字符、未匹配路径、Login/Setup 不作为普通返回来源。
- 未认证返回 Login；旧原型 `mock-token-` 被识别并清除，不当有效登录。
- 登录可继续安全的 redirect，包括需要认证的 Setup；不把外部 redirect 当目标。
- 连续返回点击共用一次请求。历史不可用时有有界替换兜底；其他导航/被拒绝的导航不强行覆盖。
- 前端不验证 JWT 签名，真实身份和权限仍由服务端负责；测试会话只是隔离 fixture。

## 5. Esc 与事件作用域

- defaultPrevented、IME、可见 dialog/listbox/遮罩先交给当前控件。
- 输入、textarea、select、contenteditable、slider/spinbutton 等编辑上下文不执行页面返回。
- 隐藏的 Element Plus DOM 不因节点存在而阻挡返回。
- 设置搜索框：有内容先清空，空内容先 blur；下一次普通 Esc 才可返回。
- 设置 Ctrl/⌘K、Main 全局快捷键不穿透可见弹层。
- 移动端聊天列表关闭、折叠、弹窗关闭等原逻辑没有盲目替换为页面后退。

## 6. 验证证据

### 6.1 当前工作区

- 最后前端相关串行回归：179 测试，0 失败/取消/跳过，退出码 0。
- 导航专用：42 测试，包括真实配置里的路径/meta、真实 guard、内存 Vue Router 导航、模拟原生 history state、hash 字符串、真实设置 onKeydown 函数隔离执行。
- 最后工作区生产构建成功，4.17 秒，退出码 0；原项目合并快照另为 3.54 秒。
- 既有大 chunk 警告仍在，未为该问题扩展全站拆包。

```bash
cd nexus-chat-frontend
node --test --test-concurrency=1 src/router/navigation.test.mjs
npm run build
```

导航测试使用实际 `router/index.js` 的路径/meta 声明和公共 guard，不手写另一套独立配置自证正确；组件被替换为 dummy，没有启动真实应用 UI。

### 6.2 原项目合并快照

增量补丁：`docs/NAVIGATION-FIXES.patch`，不是已应用状态。

验证汇总与原项目/合并文件 SHA-256：`docs/NAVIGATION-VALIDATION.json`。完整构建/测试临时产物在收尾清理，永久回归源码和验证汇总保留。

- 10 个文件，使用共同基线三方合并。
- 设置页三个重叠位置人工核对：保留原项目按 zoom 缩放的 macOS 间距、设置业务/隐私/会话验证/持久化，以及搜索 Esc 行为；只替换返回相关逻辑。
- `git -C /Users/anglv/Nexus apply --check <补丁>` 退出码 0；没有执行真正 apply。
- 基于原项目前端源代码加本补丁的隔离快照：42 导航用例通过，构建通过（3.54 秒）。
- 没有复制 .env 或本地凭据，没有操作原项目数据库或服务。

这是生成时快照的兼容性证明，不保证原项目后续继续改动后仍可直接应用。应用前必须重新执行 check，不要使用强制覆盖。

### 6.3 未覆盖

- 真正 Web/Hash 浏览器窗口、Electron 原生回退按钮和真实视觉布局。
- Setup 真实资料/头像服务流程、真实失效会话、所有网络/权限边界。
- 导航之外的新设置硬化功能全量验收；本补丁不替代其他线程测试。
- 浏览器刷新可恢复 URL 的模块/目录状态，但没有承诺所有瞬时 UI 状态、选中角色或服务端会话都自动恢复。

## 7. 交付与维护

1. 当前 worktree 和原项目不是同一树；不得把工作区构建当原项目已部署。
2. 本补丁只包含导航改动，不把酒馆与新用户资产隔离等不同任务强行合并。
3. 更新后记录真实测试数量、退出码、原项目快照兼容性和未覆盖项。
4. 不擅自提交、不启停服务。应用补丁需要目标目录授权和再次冲突检查。
5. 酒馆原范围的进度看 `TAVERN-PROGRESS.md`，两份文档的测试范围不混用。
