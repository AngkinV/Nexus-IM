# Nexus Chat 前端项目文档

**概览**
Nexus Chat 是一个同时支持 Web 与 Electron 桌面端的现代化实时聊天应用前端。核心能力包括实时消息、群组与联系人管理、文件传输、语音/视频通话、离线缓存与增量同步，以及 3D AI Companion 伙伴面板与模型管理。

**技术栈**
- `Vue 3` + Composition API
- `Vue Router 4`
- `Pinia`
- `Element Plus`
- `Vite 5`
- `Electron 28`
- `STOMP.js` + `SockJS`
- `Axios`
- `Dexie`（IndexedDB）
- `WebRTC`
- `three.js` + `@pixiv/three-vrm`
- `vue-i18n`

**项目结构**
| 路径 | 说明 |
| --- | --- |
| `src/` | 前端主代码目录 |
| `src/main.js` | Vue 应用入口 |
| `src/App.vue` | 根组件，启动时载入用户信息 |
| `src/router/index.js` | 路由与鉴权守卫 |
| `src/views/` | 页面视图 |
| `src/components/` | 组件库 |
| `src/stores/` | Pinia 状态管理 |
| `src/services/` | API、WebSocket、WebRTC、离线与同步服务 |
| `src/styles/` | 主题与全局样式 |
| `src/locales/` | 国际化资源 |
| `electron/` | Electron 主进程与预加载 |
| `public/` | 静态资源（图标、3D 模型、动作） |
| `dist/` | Web 构建产物 |
| `dist-electron/` | Electron 构建产物 |

**入口与运行时流程**
- 应用入口在 `src/main.js`，挂载 `Pinia`、`Router`、`Element Plus`、`i18n`，并注册所有 Element Plus 图标。
- 根组件 `src/App.vue` 在 `onMounted` 时读取 `localStorage` 的 `token` 并恢复用户态。
- 路由配置在 `src/router/index.js`。
- Electron 环境自动使用 Hash 路由，Web 环境使用 History 路由。
- `meta.requiresAuth` 的路由会在导航守卫中检查 `token`。

**核心启动流程（Main 页面）**
入口视图 `src/views/Main.vue` 实现了四阶段启动：
1. 从 IndexedDB 读取缓存（聊天与联系人），保证 UI 立即可用。
2. 连接 WebSocket 并开启统一消息订阅，减少消息丢失窗口。
3. 并行执行网络同步：拉取聊天、联系人、好友申请，执行 Delta Sync。
4. 发送离线待发送消息（outbox flush）。

**页面视图**
- `src/views/Login.vue`：登录/注册合一，邮箱验证码注册，支持上传头像。
- `src/views/Setup.vue`：本地初始化页面，写入本地用户与 `token`（偏离真实后端时仍可演示）。
- `src/views/Main.vue`：三栏聊天主界面 + 通话组件 + Companion 3D。
- `src/views/Settings.vue`：语言切换、通知、隐私设置、Electron 更新检查、登出。
- `src/views/Profile.vue`：个人资料总览、社交模块、安全模块。
- `src/views/UserProfile.vue`：他人资料页（含隐私控制、互相关系、发起聊天）。

**状态管理（Pinia Stores）**
| Store | 文件 | 主要职责 |
| --- | --- | --- |
| User | `src/stores/user.js` | 登录注册、资料更新、隐私设置、社交链接管理、背景图、持久化 |
| Chat | `src/stores/chat.js` | 聊天列表、活跃会话、置顶/静音、群聊管理、在线状态同步 |
| Message | `src/stores/message.js` | 消息集合、去重（server id + clientMsgId）、已读与状态更新 |
| Contact | `src/stores/contact.js` | 联系人、在线状态、好友申请、增量合并 |
| Call | `src/stores/call.js` | 通话状态机、呼叫信令、音视频控制、铃声与超时 |
| Companion | `src/stores/companion.js` | 伙伴角色、对话、记忆、成长、模型状态与绑定 |

**服务层**
| 服务 | 文件 | 职责 |
| --- | --- | --- |
| API | `src/services/api.js` | 统一 Axios 客户端与业务 API（auth/user/chat/message/contact/group/sync/companion/file） |
| WebSocket | `src/services/websocket.js` | STOMP + SockJS 连接、统一消息通道、ACK 追踪、重连与心跳 |
| WebRTC | `src/services/webrtc.js` | 本地流、RTCPeerConnection、ICE、权限检查、统计 |
| Dexie DB | `src/services/db.js` | IndexedDB Schema（messages/chats/contacts/syncMeta/pendingMessages） |
| Offline Store | `src/services/offlineStore.js` | 缓存读写与离线 outbox |
| Sync Service | `src/services/syncService.js` | Delta Sync + 离线消息补发 |

**实时通信与消息流**
- WebSocket 统一订阅通道：`/topic/user.{userId}.messages`。
- 关键事件类型（示例）：`CHAT_MESSAGE`、`MESSAGE_ACK`、`MESSAGE_DELIVERED`、`TYPING`、`MESSAGE_READ`、群组事件与通话事件。
- 消息发送：`websocket.sendMessage()` 会附带 `clientMsgId` 用于 ACK 去重。
- ACK 机制：`MESSAGE_ACK` 触发 `messageStore.updateMessageByClientMsgId`，更新临时消息。
- 未读计数在 `chatStore` 中聚合，`totalUnreadCount` 用于左侧 Tab 提示。

**离线与增量同步**
- IndexedDB 由 `Dexie` 驱动，缓存 `messages`、`chats`、`contacts`。
- `syncService.performDeltaSync()` 通过 `/sync/delta` 拉取变更，并合并至 IndexedDB 与 Pinia。
- `offlineStore.pendingMessages` 支持离线发送队列，`syncService.flushPendingMessages()` 会在重连后补发。

**聊天与消息组件**
- `src/components/chat/ChatList.vue`：聊天列表、滑动操作（置顶/删除）。
- `src/components/chat/MessageList.vue`：多类型消息渲染（文本/图/视频/音频/文件）。
- `src/components/chat/MessageInput.vue`：输入框 + 文件上传联动。
- `src/components/chat/CreateGroupModal.vue`：群组创建流程（含头像上传）。
- `src/components/chat/AddGroupMemberModal.vue`：群成员添加。
- `src/components/chat/SearchMessagesModal.vue`：本地消息搜索高亮。
- `src/components/chat/GroupList.vue`：群列表与状态展示。

**联系人组件**
- `src/components/contact/ContactList.vue`：联系人与好友申请展示。
- `src/components/contact/AddContactModal.vue`：搜索用户、直接添加、推荐列表。
- `src/components/contact/ContactRequestList.vue`：好友申请对话框（收件与发件）。

**布局组件**
- `src/components/layout/LeftPanel.vue`：左栏导航、搜索、聊天/联系人/群 Tab、系统按钮。
- `src/components/layout/MiddlePanel.vue`：聊天主区域、消息列表、发送与通话入口。
- `src/components/layout/RightPanel.vue`：会话详情、群成员管理、置顶/静音、群操作。

**通话模块**
- 入口组件：`IncomingCallModal.vue`、`OutgoingCallModal.vue`、`CallView.vue`、`CallEndModal.vue`。
- 状态机：`CallStatus`（`idle/ringing/calling/connecting/connected/ended`）。
- 信令事件：`CALL_INVITE`、`CALL_ACCEPT`、`CALL_REJECT`、`CALL_CANCEL`、`CALL_BUSY`、`CALL_TIMEOUT`、`CALL_END`、`CALL_OFFER`、`CALL_ANSWER`、`CALL_ICE_CANDIDATE`。
- WebRTC：使用 Google STUN，建议生产引入 TURN（代码中已有提示位）。

**Companion 3D 模块**
- 入口组件：`src/components/companion/CompanionAvatar3D.vue`、`CompanionPanel.vue`、`CompanionWidget.vue`。
- 3D：基于 `three.js` + `@pixiv/three-vrm` 进行模型加载、表情与动作。
- 动作资源：`public/motions/motions.json` 定义动作清单，FBX 文件放在 `public/motions/`。
- 角色模型：`public/models/` 放置 VRM；`CompanionPanel` 支持上传与切换。
- 模型绑定与 API Key：`CompanionPanel` 支持保存模型凭据与绑定模型。

**个人资料与社交模块**
- `src/views/Profile.vue`：个人总览与统计。
- `src/components/profile/SocialModule.vue`：社交链接管理、在线好友与动态。
- `src/components/profile/AccountSecurityModule.vue`：安全与登录会话示例（含演示数据）。
- `src/components/common/EditProfileModal.vue`：资料编辑与背景图设置。

**公共组件**
- `src/components/common/FileUpload.vue`：文件上传、分片上传、进度弹窗。
- `src/components/common/TitleBar.vue`：Electron 自定义标题栏。

**国际化**
- 配置入口：`src/locales/i18n.js`。
- 语言包：`src/locales/en.json`、`src/locales/zh.json`。
- 语言选择：`src/views/Settings.vue`。

**主题与样式**
- 默认主题变量：`src/styles/nexus-theme.css`。
- 另一套主题变量：`src/styles/telegram-theme.css`。
- 主题以 CSS 变量驱动，支持 `[data-theme="dark"]`。
- `index.html` 引入 `Material Icons Round` 字体。

**Electron 集成**
- 主进程：`electron/main.js`。
- 预加载：`electron/preload.js`，向 `window.electronAPI` 暴露窗口控制、通知、更新检测、媒体权限。
- 更新检测：读取 GitHub Releases 的 `latest` 信息。
- 通知、托盘、窗口控制均由 Electron 提供。

**环境变量与构建**
- API 基础地址：`VITE_API_BASE_URL`（默认 `http://localhost:8080/api`）。
- WebSocket 地址：`VITE_WS_URL`（默认 `http://localhost:8080/ws`）。
- Electron 模式：`vite.config.js` 通过 `__IS_ELECTRON__` 控制路由模式与 `base`。

**脚本与构建命令**
- `npm run dev`：Web + Electron 联调。
- `npm run dev:web`：仅 Web。
- `npm run build`：Web 生产构建。
- `npm run electron:build`：Electron 构建。
- `npm run electron:build:mac`：macOS 打包。
- `npm run electron:build:win`：Windows 打包。

**Docker 部署**
- `Dockerfile` 使用 `node:20-alpine` 构建、`nginx:alpine` 运行。
- 支持通过 `ARG` 设置 `VITE_API_BASE_URL` 与 `VITE_WS_URL`。

**静态资源**
- 应用图标：`public/icons/`。
- 3D 模型：`public/models/`。
- 动作文件：`public/motions/`。

**本地存储键**
| Key | 用途 |
| --- | --- |
| `token` | 登录态令牌 |
| `user` | 用户资料缓存 |
| `mutedChats` | 静音聊天 ID 列表 |
| `pinnedChats` | 置顶聊天 ID 列表 |
| `locale` | 语言选择 |
| `companionPos` | 3D 伙伴位置 |
| `companionModelOverrides` | 伙伴模型本地覆盖 |

**消息类型与文件类型**
- 消息类型支持 `TEXT`、`IMAGE`、`VIDEO`、`AUDIO`、`FILE`。
- 文件上传支持小文件直传与大文件分片（默认 5MB 切片）。

**主要 API 端点（示例）**
- 认证：`/auth/*`
- 用户：`/users/*`、`/users/{id}/profile`、`/users/{id}/social-links`
- 聊天：`/chats/*`、`/groups/*`
- 消息：`/messages/*`
- 联系人：`/contacts/*` 与申请相关 `/contacts/requests/*`
- 同步：`/sync/delta`
- Companion：`/companion/*`、`/companion/assets/*`
- 文件：`/files/*`

**可关注的实现细节**
- `Main.vue` 采用“先缓存后同步”的启动策略。
- `websocket.js` 统一通道处理并维护 ACK pending 映射。
- `messageStore` 支持 `clientMsgId` 去重与替换。
- `webrtc.js` 支持权限检查与 Electron 系统权限请求。

**说明与限制**
- 部分模块仍包含演示数据或占位逻辑（如安全模块会话、Setup 本地流程）。
- 离线 outbox 在当前代码中提供接口与同步流程，实际入队逻辑可继续补齐。

