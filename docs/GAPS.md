# Nexus 项目缺口清单（供 Agent 使用）

> 生成日期：2026-11-20
> 代码基线：`feature-branch @ 3d5248e`（PR #12 合并点，含 VRM 手势、语音唇同步、会话清空）。PR #11 之后的 `ad74131`（feat/companion VRM gestures, lip-sync, conversation clear）未纳入本清单的逐条核对，涉及该提交的新增/改动缺口需另行排查。
> 调查方式：只读静态代码审查。没有启动任何服务，没有运行构建、测试或 Docker，没有打开浏览器。
> 凭据约定：本文只写「哪个文件哪一行的哪个配置项是写死的」，不记录任何密钥、口令、令牌的值。

## 1. 文档说明

### 1.1 用途

这份文档回答一个问题：**当前仓库里，哪些功能是没做完、做了一半、前后端接不上、或者代码与规划/文档不一致的。** 每条缺口都给出可复查的 `路径:行号`，供后续 Agent 直接定位并安排工作。

它不替代 `docs/ROADMAP.md`：ROADMAP 写的是阶段目标，本文写的是「按当前代码逐条核对后，目标与现实的差距」。

### 1.2 核对口径

- 证据全部来自当前工作区的实际文件内容。行号是本次读取时的行号，后续改动会使行号漂移，复查时请以符号名（类名、函数名、接口路径）为准。
- 前端、Java、Python 三端都按「调用方 → 服务端 → 数据落点」的顺序核对。涉及前后端契约的缺口，尽量同时给出两端证据。
- 「已读代码确认」= 我打开过相关文件并读到支撑结论的代码；「部分确认」= 只确认了链路的一段，另一段没有读到或无法静态确认；「未核对」会直接写出。
- 没有做任何运行时验证。凡是「实际运行会怎样」的结论，本文只写代码推导，不写「已实测」。

### 1.3 如何更新

1. 修掉一条缺口后，把该条状态改为「已解决」，补上修复提交号；不要删编号，编号保持稳定。
2. 新增缺口按前缀续号：`BE-`（Java 后端）、`AG-`（Python Agent）、`FE-`（前端/Electron）、`X-`（跨端契约）、`OPS-`（部署/CI）、`DOC-`（文档）。
3. 每次更新先重跑一遍 `git rev-parse --short HEAD`，把基线写到本节和文首。
4. 复查一条缺口时，优先验证「完成标准」里写的行为，而不是只看代码是否长得像修好了。

### 1.4 范围

- 包含：`nexus-chat-backend`、`nexus-agent-backend`、`nexus-chat-frontend`（含 Electron）、根目录部署/CI/文档。
- 不包含：`nexus-chat-app`（Flutter 手机端，已从 git 移除，仅本机残留目录，见 3.5）。

## 2. 项目速览

### 2.1 定位

`docs/ROADMAP.md:7`：带 AI 助手的即时通讯；Java 后端是唯一数据源，Python Agent 只在内网，Web 与 Electron 是唯一客户端。

### 2.2 三个子项目

| 子项目 | 职责 | 关键入口 |
|---|---|---|
| `nexus-chat-backend` | 唯一业务数据源：用户/鉴权、私聊与群聊、消息、联系人、文件、同步、WebSocket 推送、Agent 网关、知识库编排、Companion（酒馆角色） | `controller/*`、`service/*`、`websocket/WebSocketController.java` |
| `nexus-agent-backend` | 内网 Agent：意图编排、模型调用、只读工具、短期/长期记忆、知识库入库与检索；通过 HMAC 接收 Java 调用，通过 Bearer 经 MCP 调用 Java 的工具 | `app/routes.py`、`app/mcp_tools.py`、`app/orchestrator.py` |
| `nexus-chat-frontend` | Web + Electron 客户端：聊天 UI、STOMP 实时、IndexedDB 缓存、Agent 会话、Companion/3D、设置 | `src/views/Main.vue`、`src/services/websocket.js`、`electron/main.js` |

### 2.3 技术栈（按声明核对）

- Java：Spring Boot 3.2.0、Java 17、Spring Web/WebSocket/Security/Data JPA/Data Redis/Mail、JJWT 0.12.3、MySQL、Lombok（`nexus-chat-backend/pom.xml`）。
- Python：FastAPI、pydantic-settings、httpx、LangChain/ChromaDB（可选）、LangGraph（唯一编排引擎，`nexus-agent-backend/app/orchestrator.py`）。
- 前端：Vue 3、Vite 5、Pinia、Element Plus、Tailwind、vue-i18n、Dexie(IndexedDB)、@stomp/stompjs + SockJS、three + @pixiv/three-vrm、Electron 28 + electron-builder（`nexus-chat-frontend/package.json`）。

### 2.4 通信与数据落点

- 前端 ↔ Java：REST 前缀 `/api`；STOMP over SockJS 端点 `/ws`；服务端统一向 `/topic/user.{userId}.messages` 与 `/topic/user.{userId}.contacts` 投递，新建会话走 `/user/queue/chats`（`src/services/websocket.js:153,218,224`；`SecurityConfig`、`WebSocketConfig`）。
- Java ↔ Python：Java 以 HMAC 头调用 Python（`service/agent/AgentGatewayService.java`）；Python 以 Bearer 令牌 + `X-Actor-User-Id` 经 MCP 调用 `/internal/agent/mcp`（`app/mcp_tools.py`、`controller/agent/InternalAgentController.java`）。
- MySQL：用户、会话、成员、消息、已读/送达状态、联系人、文件元数据、Agent 会话索引、Companion 数据等；`ddl-auto=update` 自动建表（`application.properties:17`）。
- Redis：在线状态、离线消息队列、输入中、未读数缓存、消息序号（`chat:seq:{chatId}`）、跨实例转发；Agent 短期记忆使用独立 Redis DB（`REDIS_URL=redis://redis:6379/2`，`docker-compose.yml:94`）。
- 向量库：Chroma，容器内 `/app/data/chroma`（`docker-compose.yml:95`，volume `agent_data`）。
- 文件：上传目录相对路径 `uploads/`（`FileUploadController.java:39`），容器映射 `/app/uploads`（`docker-compose.yml:75`）；Companion 3D 资源在开发环境写入前端 `public/`（`application.properties:99`），Docker 环境写入 `/data/companion-assets`（`application-docker.properties:85-88`）。
- 前端缓存：IndexedDB（聊天、消息、联系人、发件箱、同步时间戳），见 `src/services/offlineStore.js`。

### 2.5 本机开发与检查命令

来自 `docs/DEVELOPMENT.md:45-61`（本次只核对了文件存在与命令文本，**没有执行**）：

```bash
# backend
mvn -B -ntp test -Dtest='!NexusChatApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
# agent
.venv/bin/python -m pytest -q
# frontend
npm run build
```

- 后端测试排除了 `NexusChatApplicationTests`（需要真实 MySQL/Redis），CI 同样排除（`.github/workflows/ci.yml` backend job）。
- 整套服务用根目录 `docker-compose.yml` 启动（mysql、redis、backend、agent、frontend、cloudflared）。compose 是否能在本机一次启动成功未验证。
- 端口：backend 8080、agent 8100、frontend 80、MySQL/Redis 仅在 compose 内网。

### 2.6 细节阅读入口

README 的分节（`README.md`）覆盖了更细的目录结构、数据模型与接口清单。注意其中若干描述已过期，见 `DOC-01` 与第 6 节。

## 3. 范围与非目标

`docs/ROADMAP.md:9-19` 定义的范围与代码现状对照：

| 规划处理 | 模块 | 代码现状 | 关联缺口 |
|---|---|---|---|
| 保留 | 聊天核心、用户鉴权、Agent、知识库 | 主链路齐全，但已读/未读、离线、群实时事件存在断点 | X-01、X-02、X-03、X-04 |
| 合并 | Companion 并入 Agent，作为一个人设 | **未合并**：Java 仍有独立的 Companion 会话/提示词/模型直连管线；Agent 也有自己的会话与 Provider 体系 | BE-16 |
| 保留但默认关 | 3D 形象：打开后才加载资源 | 默认关闭已做到（`petVisible` 默认 false），但 Three.js/VRM 是静态 import，打开与否都会进首屏包 | FE-07、FE-06 |
| 冻结 | 社区帖子、LangGraph 备选引擎、音视频通话 | 代码都在；通话 UI 仍常驻挂载且可发起；帖子后端接口仍对外；LangGraph 默认不启用 | FE-10、BE-17 |
| 停止 | 手机端 `nexus-chat-app` | git 已无文件（`git ls-files nexus-chat-app` 为 0，最后提交 `88b0d0e`）；本机目录仍在且被 `.gitignore:90` 忽略；手机端专用后端接口与 nginx 反代仍保留 | BE-17 |

非目标（本文不展开）：Flutter 手机端的任何功能、UI 视觉还原度、设计稿评审结论、未提交/已提交代码的归属。

## 4. 缺口总表

| 编号 | 模块 | 标题 | 优先级 | 类型 | 状态 |
|---|---|---|---|---|---|
| BE-01 | 文件 | 下载/预览接口匿名可读 | P0 | 安全 | 未解决 |
| BE-02 | 3D 资源 | 上传/改名/删除无归属或管理员校验 | P0 | 安全 | 未解决 |
| BE-03 | 配置 | JWT、加密密钥、内部令牌存在写死默认值 | P0 | 安全 | 未解决 |
| BE-04 | 消息 | 整聊已读无成员校验 | P1 | 安全 | 未解决 |
| BE-05 | 鉴权 | 开启 2FA 后登录不校验 | P1 | 安全 | 未解决 |
| BE-06 | 联系人 | 拉黑不生效 | P1 | 功能/隐私 | 未解决 |
| BE-07 | 鉴权 | 忘记密码/重置密码链路缺失 | P1 | 功能 | 未解决 |
| BE-08 | 邮件 | 验证码邮件失败被异步吞掉 | P2 | 可靠性 | 未解决 |
| BE-09 | 鉴权 | 登录无失败锁定/限流 | P2 | 安全 | 未解决 |
| X-01 | 消息 | 已读回执与未读计数断链 | P1 | 契约 | 未解决 |
| X-02 | 消息 | 离线消息没有持久化发件箱 | P1 | 可靠性 | 未解决 |
| X-03 | 同步 | 增量同步首次永远不会启动 | P1 | 可靠性 | 未解决 |
| X-04 | 群聊 | 群事件发到无人订阅的 topic | P1 | 契约 | 未解决 |
| BE-10 | 消息 | 消息序号只依赖 Redis | P1 | 可靠性 | 未解决 |
| BE-11 | Agent | reply-publish 绕过正常发送管线 | P1 | 契约 | 未解决 |
| BE-12 | 群聊 | 前端创建的群，创建者角色是 member | P1 | 功能 | 未解决 |
| FE-01 | 搜索 | 消息搜索只搜本地 50 条且存在正则注入 | P1 | 功能/安全 | 未解决 |
| FE-02 | 群聊 | 群资料/公告编辑缺失 | P2 | 功能 | 未解决 |
| BE-13 | 群聊 | 两套群创建路径，行为不一致 | P2 | 一致性 | 未解决 |
| BE-14 | 群聊 | 群 DTO 全量加载消息、成员逐条查库 | P2 | 性能 | 未解决 |
| FE-03 | 消息 | 无分页/虚拟滚动，每条消息内嵌完整表情选择器 | P2 | 性能 | 未解决 |
| FE-04 | 消息 | 语音消息只有占位 | P2 | 功能 | 未解决 |
| FE-05 | 消息 | 转发能力缺失 | P2 | 功能 | 未解决 |
| BE-15 | 文件 | 后端返回的下载/预览 URL 缺 `/api` 前缀 | P2 | 契约 | 未解决 |
| X-05 | 会话 | 前端有 DELETE 聊天方法，后端无端点 | P3 | 死码 | 未解决 |
| AG-01 | Agent | 总结/待办/回复建议/发布没有 UI 入口 | P2 | 功能 | 未解决 |
| AG-02 | Agent | 只有只读工具，`/messages/publish` 无调用方 | P2 | 功能 | 未解决 |
| AG-03 | Agent | 没有 MCP 双向集成 | P2 | 功能 | 未解决 |
| AG-04 | Agent | 出处只拼进提示词，没有结构化引用 | P2 | 功能 | 未解决 |
| AG-05 | Agent | Agent 不是聊天成员、无 @助手、无未读追赶 | P2 | 功能 | 未解决 |
| AG-06 | Agent | 会话历史仅存 Redis，7 天/50 条 | P3 | 可靠性 | 未解决 |
| AG-07 | Agent | nonce 重放缓存是单进程内存 | P2 | 安全/运维 | 未解决 |
| BE-16 | Companion | Companion 仍在 Java 直连 LLM | P2 | 架构 | 未解决 |
| BE-17 | 范围 | 手机端专用接口与 nginx 反代未删 | P2 | 范围 | 未解决 |
| OPS-01 | 部署 | 生产构建把 API 地址解析成 localhost | P0 | 部署 | 未解决 |
| OPS-02 | 部署 | Docker 下 3D 资源目录与 nginx 未打通 | P1 | 部署 | 未解决 |
| OPS-03 | 数据 | `ddl-auto=update`，迁移脚本不自动执行 | P2 | 运维 | 未解决 |
| OPS-04 | 质量 | CI 与主链路测试覆盖不足 | P2 | 质量 | 未解决 |
| OPS-05 | 部署 | `.env.example` 缺 compose 必需变量 | P3 | 部署 | 未解决 |
| OPS-06 | 部署 | 生产域名、CORS、Electron 绕过逻辑硬编码 | P2 | 部署 | 未解决 |
| FE-06 | 性能 | 路由/Element Plus/Three.js 未按需加载 | P2 | 性能 | 未解决 |
| FE-07 | 3D | 默认关但资源未懒加载 | P2 | 性能 | 部分实现 |
| FE-08 | 缓存 | IndexedDB 消息缓存无上限 | P2 | 性能 | 未解决 |
| FE-09 | Electron | 无托盘/置顶，更新只检查不安装 | P2 | 功能 | 未解决 |
| FE-10 | 范围 | 冻结的通话 UI 仍暴露 | P2 | 范围 | 未解决 |
| FE-11 | 会话 | 置顶/免打扰/隐藏只存本机 | P3 | 功能 | 未解决 |
| FE-12 | 设计 | 设计稿字段与视觉件缺失 | P3 | 产品 | 未解决 |
| FE-13 | 会话 | `chatStore.createGroup` 是假实现 | P3 | 死码 | 未解决 |
| FE-14 | 国际化 | 少量硬编码中文 | P3 | 国际化 | 未解决 |
| DOC-01 | 文档 | README 多处与代码不符 | P2 | 文档 | 未解决 |
| DOC-02 | 文档 | DEVELOPMENT 环境表过期 | P3 | 文档 | 未解决 |
| DOC-03 | 文档 | ROADMAP 勾选与代码不一致 | P2 | 文档 | 未解决 |

## 5. 缺口明细

### 5.1 安全与账号

#### BE-01 文件下载/预览接口匿名可读

- **现状**：`/api/files/download/{fileId}`、`/api/files/preview/{fileId}` 在安全配置里 `permitAll`，控制器方法里也没有任何身份或会话校验；知道 `fileId` 就能取文件。
- **缺什么**：至少校验登录与「该用户是否属于文件所属会话」；公开头像/群头像类文件需要单独的公开标记与访问策略。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/config/SecurityConfig.java:49`；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/FileUploadController.java:220-221`、`264-265`。
- **建议做法**：在 `FileUploadController` 引入 `CurrentUser.require()` + 文件元数据里的 owner/chatId 校验；把 `permitAll` 收窄到真正公开的目录。公开图片与聊天附件分两类 URL，避免同一接口既匿名又私有。
- **完成标准**：未登录请求下载/预览返回 401；非会话成员请求返回 403；公开头像仍可匿名访问且有测试覆盖。
- **依赖/影响面**：`SecurityConfig`、`FileUploadController`、`MessageList`/`MessageInput` 的 URL 使用、nginx 代理规则。会与 BE-15 的 URL 前缀调整一起改。
- **优先级**：P0（ROADMAP M0 未完成项）
- **核对程度**：已读代码确认。

#### BE-02 3D 资源上传/改名/删除无权限校验

- **现状**：`CompanionAssetController` 的所有变更接口都没有取当前用户、没有 owner 概念、没有管理员校验；`/models/**`、`/motions/**` 又是匿名可读。任何已登录用户都能覆盖全局模型、重命名或删除动作文件。
- **缺什么**：上传/改名/删除需要管理员（或资源归属人）权限；默认资源与用户资源要隔离；删除模型没有接口。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/controller/CompanionAssetController.java:47`、`81`、`147`、`186`、`267`；`config/SecurityConfig.java:51`；`config/WebMvcConfig.java:39-44`。
- **建议做法**：加 `CurrentUser.require()` 与角色判断；资源元数据记录 uploader；默认资源只读；上传文件名做白名单与冲突处理。前端设置页按权限隐藏入口。
- **完成标准**：普通用户调用 5 个变更接口均返回 403；管理员操作成功；默认资源不可被普通用户覆盖。
- **依赖/影响面**：`CompanionAssetController`、`CompanionAvatar3D.vue`、设置页、`SecurityConfig`、`application(-docker).properties` 的资源根目录。注意与 OPS-02 一起改。
- **优先级**：P0（ROADMAP M0 未完成项）
- **核对程度**：已读代码确认。

#### BE-03 默认密钥与内部令牌写死

- **现状**：`application.properties` 多处使用 `${ENV:默认值}` 形式，未设置环境变量时会静默启用开发默认值；Python 侧同样有默认内部令牌与签名密钥。生产 compose 对 Agent 内部密钥做了「无默认值、缺失即失败」的处理，但 JWT 与 Companion 加密密钥仍有默认值。
- **缺什么**：生产 profile 去掉所有可用的默认密钥；CI 增加密钥扫描；已进入过公开仓库的密钥轮换无法从代码确认是否完成。
- **证据**：`nexus-chat-backend/src/main/resources/application.properties:37`（`jwt.secret`）、`88`（`companion.api-key.secret`）、`108`（`agent.internal.token`）、`110`（`agent.internal.signing-secret`）；`nexus-agent-backend/app/config.py:43`、`46`；`docker-compose.yml:71-72`。
- **建议做法**：在 `application-docker.properties` 中把 JWT、Companion 密钥也改成无默认值；启动时做一次「默认值检测」并拒绝启动；CI 加 gitleaks/trufflehog 一类扫描；密钥轮换作为运维动作单独记录。
- **完成标准**：不设置任何环境变量时 Docker profile 启动失败；仓库扫描无命中；四个密钥均已在部署环境替换（后者需运维确认）。
- **依赖/影响面**：所有 profile、compose、`.env.example`、CI；会影响本地开发（需要 `.env` 或本地 profile）。
- **优先级**：P0（ROADMAP M0 未完成项）
- **核对程度**：已读代码确认；密钥是否已轮换：未核对。

#### BE-04 整聊已读没有成员校验

- **现状**：`markChatMessagesAsRead(chatId, userId)` 直接批量更新已读状态并清零未读数，没有校验 `userId` 是否属于 `chatId`。单条已读依赖「已存在的 read-status 行」，边界更窄，但整聊接口是敞开的。
- **缺什么**：与 `getChatMessages` 一致的成员校验；幂等与越权测试。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/MessageService.java:298-305`；`controller/MessageController.java:221-231`。
- **建议做法**：在 service 入口调用 `chatMemberRepository.existsByChatIdAndUserId`，不通过则抛 `error.chat.not.member`。
- **完成标准**：非成员调用 `/api/messages/chat/{id}/read` 返回业务错误；成员调用后 DB 未读数归零。
- **依赖/影响面**：`MessageService`、`MessageController`、前端未来接入已读上报（X-01）。
- **优先级**：P1（ROADMAP M0 未完成项）
- **核对程度**：已读代码确认。

#### BE-05 开启 2FA 后登录不校验

- **现状**：设置页可以完成 TOTP 设置、验证并启用两步验证；但登录接口只校验密码，成功后直接签发 JWT，没有任何「需要二次验证」的分支。
- **缺什么**：登录响应区分「密码正确但需要 TOTP」，前端进入验证码步骤；服务端在验证通过前不得签发正式 token；备份恢复码流程。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/AuthService.java:87-108`；`service/SecurityService.java:260-315`；`nexus-chat-frontend/src/components/profile/AccountSecurityModule.vue:91-112`；`src/services/api.js:44-45`。
- **建议做法**：`LoginRequest` 增加可选 `totpCode`；首次响应返回 `requiresTwoFactor` 与短期票据；前端在 `Login.vue` 增加第二步；失败计入登录历史。
- **完成标准**：启用 2FA 的账号，仅凭密码无法拿到 JWT；输入正确 TOTP 后才返回 token；错误码有测试。
- **依赖/影响面**：`AuthService`、`AuthController`、`SecurityService`、`Login.vue`、`api.js`、`AuthResponse`。
- **优先级**：P1（安全功能是「假启用」）
- **核对程度**：已读代码确认。

#### BE-06 拉黑不生效

- **现状**：联系人有拉黑/取消拉黑/查询接口，前端个人资料页也能管理黑名单；但发消息、建私聊、会话列表、搜索都没有消费黑名单。被拉黑的人仍可发消息、建会话。
- **缺什么**：发送消息前判断双向拉黑；建私聊前判断；拉黑后是否隐藏历史会话需产品决定。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/ContactService.java:567-595`；发消息链路 `service/MessageService.java:67-130` 无黑名单调用；`service/ChatService.java:78-149`（建群）与私聊创建同样未调用。
- **建议做法**：在 `MessageService.sendMessage` 与 `ChatService.createDirectChat` 调用 `isBlocked`（双向），返回明确错误码；群聊里拉黑是否屏蔽消息需产品决定。
- **完成标准**：A 拉黑 B 后，B 发消息被拒且 A 收不到；A 无法新建与 B 的私聊；有服务层测试。
- **依赖/影响面**：`ContactService`、`MessageService`、`ChatService`、`MessageController`、`ChatController`、前端错误提示文案。
- **优先级**：P1
- **核对程度**：已读代码确认（黑名单只在联系人模块内使用）。

#### BE-07 忘记密码/重置密码链路缺失

- **现状**：验证码类型枚举里已经有 `RESET_PASSWORD`、`CHANGE_EMAIL`，发码接口也能接收这些类型；但没有任何「校验重置码 + 设置新密码」的接口，登录页也只有「登录/注册」切换，没有忘记密码入口。验证码邮件正文仍是注册文案。
- **缺什么**：`/auth/reset-password`（或等价）端点、前端入口与表单、重置码邮件模板、成功后吊销已有会话。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/model/EmailVerificationCode.java:42-46`；`controller/AuthController.java:30-105`（只有 send-code/verify-code/register/login/logout/change-password）；`service/VerificationCodeService.java:38`（REGISTER 拒绝已注册邮箱）；`service/EmailService.java:32`、`71`（固定注册文案）；`nexus-chat-frontend/src/views/Login.vue:180`。
- **建议做法**：补重置端点（校验码 → 更新密码 → 吊销该用户全部会话 → 清理验证码）；邮件按 code type 选模板；前端加「忘记密码」。需要产品确认是否允许邮箱+验证码重置。
- **完成标准**：未登录用户可通过邮箱验证码完成重置；旧密码失效、旧会话失效；注册与重置邮件文案不同。
- **依赖/影响面**：`AuthController`、`AuthService`、`VerificationCodeService`、`EmailService`、`Login.vue`、i18n。
- **优先级**：P1（账号主链路缺口）
- **核对程度**：已读代码确认。

#### BE-08 验证码邮件发送失败被异步吞掉

- **现状**：`EmailService.sendVerificationCode` 标了 `@Async`，内部捕获异常后又抛出 `RuntimeException`。异步线程里的异常不会回到调用方，发码接口仍返回成功；验证码已经落库，用户却收不到邮件。
- **缺什么**：失败可观测、可重试、对用户有明确反馈；或在开发环境提供可配置的「不发信、回显验证码」模式。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/EmailService.java:24-42`；`service/VerificationCodeService.java:71`；`NexusChatApplication.java:9`（`@EnableAsync`）。
- **建议做法**：把「发送」拆成同步落库 + 异步发送任务表，失败记录状态并允许重发；或发送失败时回滚验证码并返回错误。开发环境提供 mock sender。
- **完成标准**：SMTP 不可用时接口返回可识别的失败；验证码表里不残留「已发但未发」的记录；有重试或人工重发入口。
- **依赖/影响面**：`EmailService`、`VerificationCodeService`、`AuthController`、运维监控。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### BE-09 登录无失败锁定/限流

- **现状**：失败登录会写入历史，但没有计数阈值、锁定窗口或 IP/账号限流；密码可无限尝试。
- **缺什么**：账号/IP 维度的失败计数与锁定；验证码接口限流（目前只有同一邮箱的重发间隔）。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/AuthService.java:87-98`；`service/SecurityService.java:101-114`。
- **建议做法**：Redis 计数 + 指数退避/锁定；返回统一错误但记录不同原因；对 `/auth/send-code` 增加 IP 限流。
- **完成标准**：连续失败达到阈值后短时间拒绝；锁定自动解除；有测试。
- **依赖/影响面**：`AuthService`、`SecurityService`、Redis、前端错误文案。
- **优先级**：P2
- **核对程度**：已读代码确认。

### 5.2 消息、会话与同步

#### X-01 已读回执与未读计数断链

- **现状**：后端会维护 `message_read_status` 和 `chat_members.unread_count`，也提供了单条/整聊已读接口和 `MESSAGE_READ` 推送；前端只「处理收到的 READ 事件」，从不调用已读接口、也不发布已读事件。打开会话时只是把本地 `unreadCount` 置 0，服务端未读数永远不清零，消息的「已读」状态也不会变成已读。
- **缺什么**：进入会话/滚动到可见时批量上报；STOMP 或 REST 二选一；服务端清零后向发送方推送已读；多端一致性。
- **证据**：`nexus-chat-frontend/src/stores/chat.js:302-310`（只改本地）；`src/services/api.js:187`（有方法但全仓无调用）；`src/services/websocket.js:445`（只处理入站 READ）；`nexus-chat-backend/src/main/java/com/nexus/chat/service/MessageService.java:127`（未读 +1）、`304`（只有被调用才清零）。
- **建议做法**：前端在 `setActiveChat` 与窗口重新可见时调用 `PUT /api/messages/chat/{chatId}/read`；后端清零后给发送方推 `MESSAGE_READ`；补「非成员不可上报」校验（BE-04）。
- **完成标准**：A 打开与 B 的会话后刷新页面，未读数仍为 0；B 的单聊消息回执变「已读」；重新登录后状态一致。
- **依赖/影响面**：`chat.js`、`message.js`、`MessageController`、`MessageService`、`MessageList.vue`（已读图标）。
- **优先级**：P1（用户可见的主链路）
- **核对程度**：已读代码确认。

#### X-02 离线消息没有持久化发件箱

- **现状**：IndexedDB 里有 `pendingMessages` 表与 `queuePendingMessage()`，但全仓没有任何地方调用它；WebSocket 未连接时 `sendMessage` 直接 reject，不写队列；消息发送失败只把气泡标成 failed。刷新页面后，离线期间输入/失败的消息全部丢失。启动时的 flush 永远面对空队列。
- **缺什么**：发送前/失败时写入 IndexedDB 发件箱；重连后按顺序重放并处理重复；清理已发送记录。
- **证据**：`nexus-chat-frontend/src/services/websocket.js:783-785`；`src/services/offlineStore.js:229`（只有定义）；`src/views/Main.vue:446-455`（启动即 flush）；`src/components/layout/MiddlePanel.vue:527-531`（失败只标状态）。
- **建议做法**：在 `handleSendMessage` 失败分支与「未连接」分支调用 `queuePendingMessage`；重连成功事件触发 flush；发送使用同一 `clientMsgId` 保证幂等（后端已有 `existsByClientMessageId`）。
- **完成标准**：断网时发送消息 → 刷新页面后消息仍在 → 恢复网络自动补发且不重复。
- **依赖/影响面**：`websocket.js`、`offlineStore.js`、`syncService.js`、`Main.vue`、`MiddlePanel.vue`；与 X-03、BE-10 相关。
- **优先级**：P1（ROADMAP M2）
- **核对程度**：已读代码确认。

#### X-03 增量同步首次永远不会启动

- **现状**：`performDeltaSync` 只有在 IndexedDB 里已有 `lastSyncTime` 时才请求 `/sync/delta`；而 `setLastSyncTime` 只在一次成功 delta 之后才写入。首次运行 `since` 为 null → 直接返回空结果 → 永远不写时间戳 → 之后每次都走空返回。整套增量同步实际上是死循环，只有 `fetchChats/fetchContacts` 起作用，历史消息不会补拉。
- **缺什么**：首次同步的初始化（写入当前时间或做一次全量）与空结果也要推进时间戳；失败重试策略。
- **证据**：`nexus-chat-frontend/src/services/syncService.js:33-36`、`83-85`；`src/views/Main.vue:435-441`；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/SyncController.java:82-90`（`since` 为空时返回空消息）。
- **建议做法**：首次登录写入 `lastSyncTime=now` 并依赖正常的历史消息接口；delta 成功/失败都按服务端时间推进游标；补同步主链路测试。
- **完成标准**：新设备登录后能看到最近历史；隔一段时间再登录能拉到增量消息；清空 IndexedDB 后能自愈。
- **依赖/影响面**：`syncService.js`、`offlineStore.js`、`Main.vue`、`SyncController`。
- **优先级**：P1（ROADMAP M2，主链路）
- **核对程度**：已读代码确认。

#### X-04 群事件发到无人订阅的 topic

- **现状**：群创建/更新/解散/成员进出/管理员变更/转让都通过 `messagingTemplate.convertAndSend("/topic/group/{groupId}", ...)` 广播；前端只订阅 `/topic/user.{id}.messages`、`/topic/user.{id}.contacts`、`/user/queue/chats`；并且 STOMP 订阅白名单只允许 `/topic/user.{自己的id}.` 前缀，即使前端改订阅 `/topic/group/**` 也会被服务端拒绝。结果：群变更事件全部落空。
- **缺什么**：要么按成员逐个投递到 `user` 频道（与消息一致），要么放开群 topic 的按成员订阅校验；前端补订阅。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/GroupService.java:446-451`；`config/WebSocketAuthChannelInterceptor.java:90`；`nexus-chat-frontend/src/services/websocket.js:153`、`218`、`224`。
- **建议做法**：推荐复用现有用户频道（`sendToUserChannel`），避免再引入一个需要鉴权的 topic；删除或保留 `GROUP_*` 消息类型均可。前端已有的 GROUP 处理器即可生效。
- **完成标准**：A 修改群名/踢人后，B 不刷新页面也能看到变化；越权订阅他人频道仍被拒绝。
- **依赖/影响面**：`GroupService`、`ChatService`（建群通知已用 `/queue/chats`，可参照）、`WebSocketAuthChannelInterceptor`、`websocket.js`、`RightPanel.vue`。
- **优先级**：P1
- **核对程度**：已读代码确认。

#### BE-10 消息序号只依赖 Redis

- **现状**：每个会话的 `sequenceNumber` 由 Redis `INCR chat:seq:{chatId}` 生成，没有任何从数据库最大值回填的逻辑。Redis 清空/换实例后序号从 1 重新开始，与历史消息重复，破坏 ACK 补洞与增量排序。
- **缺什么**：Redis 不可用或计数器缺失时从 `MAX(sequence_number)` 初始化；并发安全；M2 要求的「清空 Redis 序号不重复」。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/MessageSequenceService.java:22-24`；`service/RedisCacheService.java:278-281`；`repository/MessageRepository.java:67` 的按序号查询没有被用来初始化。
- **建议做法**：用 Lua 脚本实现「不存在则 SET 为 DB max，再 INCR」；启动或首次发消息时回填；补并发测试。
- **完成标准**：清空 Redis 后新消息序号大于历史最大序号；多实例并发不重复。
- **依赖/影响面**：`MessageSequenceService`、`RedisCacheService`、`MessageRepository`、消息发送链路。
- **优先级**：P1（ROADMAP M2）
- **核对程度**：已读代码确认。

#### BE-11 reply-publish 绕过正常发送管线

- **现状**：AI 回复「发布」直接 `messageRepository.save(message)`，没有生成序号、没有 `clientMsgId`、没有写已读/送达状态、没有 WebSocket 推送、没有「AI 代写」标记，也没有更新会话最后消息时间。前端发布后只能靠下次拉取看到消息。
- **缺什么**：走 `MessageService.sendMessage`（或抽取共享方法），补齐序号、幂等、推送、标记；前端展示 AI 标记。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/controller/agent/AgentController.java:305-326`（保存发生在 `319`）。
- **建议做法**：复用 `sendMessage`，增加 `senderType=AI`/`aiAssisted=true` 字段与 DTO 透出；前端消息气泡显示标记。
- **完成标准**：发布的 AI 消息有序号、实时到达、刷新后仍在、带标记；重复发布可幂等。
- **依赖/影响面**：`AgentController`、`MessageService`、`Message`/`MessageDTO`、`MessageList.vue`、schema。
- **优先级**：P1（ROADMAP M3）
- **核对程度**：已读代码确认。

#### BE-12 前端创建的群，创建者角色是 member

- **现状**：前端建群走 `/api/chats/group` → `ChatService.createGroupChat`，只把创建者 `isAdmin` 设为 true，**没有把 `role` 设为 `owner`**（实体默认 `member`）。`GroupService.createGroup` 才设置 owner，但那条路径前端不调用。前端群管理面板完全按 `role` 字符串判断 `isOwner/isAdmin`，于是创建者看不到「转让/解散」等群主操作，却能看到「退出群聊」——退出会被后端以「群主不能退出」拒绝。
- **缺什么**：建群时写入 owner；历史数据迁移/兜底（按 `chat.createdBy` 推断 owner）；前端同时参考 `isAdmin`/`createdBy`，不要只看 role。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/ChatService.java:122-126`；`model/ChatMember.java:31`（默认 `member`）；`nexus-chat-frontend/src/components/layout/RightPanel.vue:262-268`、`156`、`165`。
- **建议做法**：`createGroupChat` 设置 `Role.owner`；对已有群执行一次性修复；前端判断改为「role==='owner' || chat.createdBy===me」。
- **完成标准**：新建群的创建者显示群主并有全部管理操作；历史群同样可用；无「群主退出被拒」路径。
- **依赖/影响面**：`ChatService`、`GroupService`、`ChatMember`、`RightPanel.vue`、数据修复脚本。
- **优先级**：P1（群管理主链路）
- **核对程度**：已读代码确认（对新建群确定成立；历史数据是否已修复：未核对）。

#### FE-01 消息搜索只搜本地 50 条且存在正则注入

- **现状**：搜索弹窗从 Pinia 里取当前会话已加载的消息（最多 50 条）做客户端过滤，不调用后端搜索接口；只用 `includes` 粗筛后 `new RegExp("(" + query + ")", "gi")` 高亮，特殊字符会让正则报错或错配；结果用 `v-html` 渲染；点击结果不会跳转到原消息。
- **缺什么**：调用 `GET /api/messages/chat/{chatId}/search`（支持分页/发送人/日期）；转义正则或不用正则；结果可定位；必要时跨会话搜索。
- **证据**：`nexus-chat-frontend/src/components/chat/SearchMessagesModal.vue:131`、`143`、`44`；`src/services/api.js:166`（方法存在但无调用）；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/MessageController.java:146-153`。
- **建议做法**：改为服务端分页搜索；高亮用安全的分段渲染；增加「跳转到上下文」接口/滚动定位。
- **完成标准**：能搜到 50 条以外的历史；输入 `(`、`*` 不报错；点击结果能定位；后端搜索接口有调用。
- **依赖/影响面**：`SearchMessagesModal.vue`、`api.js`、`MessageController`、`MessageService`；若做全文索引会牵涉 OPS-03。
- **优先级**：P1（用户可见功能名不副实）
- **核对程度**：已读代码确认。

#### FE-02 群资料/公告编辑缺失

- **现状**：建群时可以填名称/描述/头像；建完之后没有修改入口。`groupAPI.updateGroup` 已定义但全仓无调用；`GroupDetail.vue` 把 `description` 当作「群公告」展示，却没有编辑能力，也没有公告历史。
- **缺什么**：群主/管理员可编辑名称、头像、描述/公告；变更实时同步（依赖 X-04）。
- **证据**：`nexus-chat-frontend/src/services/api.js:286`；`src/components/chat/GroupDetail.vue:4-5`（注释明确「无后端数据的区块留空」）；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/GroupController.java:52-60`（后端已支持）。
- **建议做法**：在 `RightPanel.vue` 增加「群资料」编辑表单，复用上传头像；公告如果需要历史，另加表与接口。
- **完成标准**：群主修改名称/头像/公告后刷新与其它成员端都生效；普通成员看不到入口。
- **依赖/影响面**：`RightPanel.vue`、`GroupDetail.vue`、`api.js`、`GroupController`、X-04。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### BE-13 两套群创建路径，行为不一致

- **现状**：`POST /api/chats/group`（前端使用）有名称/成员数量/用户存在性校验；`POST /api/groups`（无前端调用）没有同等校验，且创建者角色设置与前者不同。两套逻辑都写成员、成员数、通知，后续容易改漏一边。
- **缺什么**：收敛为一个入口；或让 `/api/groups` 委托同一 service 方法。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/controller/ChatController.java:38-52`；`controller/GroupController.java:28-35`；`service/ChatService.java:78-149`；`service/GroupService.java:37-81`；`nexus-chat-frontend/src/services/api.js:146`。
- **建议做法**：保留 `/api/chats/group`，删除或内联 `GroupService.createGroup`；统一 owner 与校验。
- **完成标准**：只剩一个建群入口；两套 DTO 行为一致；有测试。
- **依赖/影响面**：两个 Controller、两个 Service、`CreateGroupRequest`、与 BE-12 一起改。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### BE-14 群 DTO 全量加载消息、成员逐条查库

- **现状**：`mapToGroupDTO` 为拿最后一条消息调用 `findByChatIdOrderByCreatedAtDesc` 然后把整个会话消息列表读出来取第 0 条；成员映射对每个成员单独 `userRepository.findById`。群成员多、历史长时是明显的 N+1 与全表扫描式读放大。
- **缺什么**：`findFirstByChatIdOrderByCreatedAtDesc`；成员与用户批量查询（`ChatService.getUserChats` 已有批量写法可复用）。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/GroupService.java:400-409`、`277-289`；对比 `service/ChatService.java:156-187`。
- **建议做法**：换成已有的 `findFirstByChatIdOrderByCreatedAtDesc` 与 `findAllByIdIn` 批量映射。
- **完成标准**：群详情查询的 SQL 次数与会话成员数无关；有基准或至少代码评审确认。
- **依赖/影响面**：`GroupService`、`GroupDTO`；`getUserGroups` 会逐个群触发，影响面更广。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### FE-03 消息列表无分页/虚拟滚动，每条消息内嵌完整表情选择器

- **现状**：进入会话固定拉第 0 页 50 条并整体替换 store，没有「加载更早」；列表一次性渲染全部消息；每条消息的响应/表情操作区里都挂了一个完整 `EmojiPicker`（通过 `el-popover` 触发），消息量大时 DOM 节点数量级膨胀。
- **缺什么**：向上分页/游标加载；虚拟滚动；表情选择器改为只在打开时渲染单个实例。
- **证据**：`nexus-chat-frontend/src/components/layout/MiddlePanel.vue:407-419`；`src/components/chat/MessageList.vue:64-77`、`195`。
- **建议做法**：先做「按需渲染表情选择器 + 分页加载」，再做虚拟滚动；缓存上限见 FE-08。
- **完成标准**：1000 条消息的会话滚动流畅；能加载更早历史；页面 DOM 不随消息数线性增长到不可用。
- **依赖/影响面**：`MessageList.vue`、`MiddlePanel.vue`、`message.js`、后端分页参数。
- **优先级**：P2（ROADMAP M5）
- **核对程度**：已读代码确认；具体 DOM 数量未实测（`.alma/handoff-mwjzqivhjk38qc628d6.md:35` 给过经验值）。

#### FE-04 语音消息只有占位

- **现状**：输入框有麦克风按钮，点击只弹「即将上线」提示；没有录音、上传、时长、波形或发送逻辑。已有的 AUDIO 类型只能靠上传音频文件产生，播放器是原生 `<audio controls>`。
- **缺什么**：录音权限/编码/上传/时长与播放气泡；移动端浏览器兼容策略。
- **证据**：`nexus-chat-frontend/src/components/chat/MessageInput.vue:44`、`93`；`src/components/chat/MessageList.vue:139-140`。
- **建议做法**：用 MediaRecorder 录 webm/ogg，上传后按 AUDIO 发送；播放器替换成统一的语音气泡（时长、播放态）。
- **完成标准**：按住/点击录音 → 发送 → 对方可播放；取消与权限拒绝有提示。
- **依赖/影响面**：`MessageInput.vue`、`MessageList.vue`、`fileAPI`、Electron 媒体权限（已有 `system:request-media-permissions`）。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### FE-05 消息转发缺失

- **现状**：前后端都没有转发字段、接口或 UI；消息操作菜单没有转发项。全仓 `forward` 只出现在 CSS 动画与注释里。
- **缺什么**：转发到会话/多选、来源标记、权限与去重；是否允许转发文件/图片需产品决定。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/model/Message.java:63-65`（消息类型无转发语义）；`nexus-chat-frontend/src/components/chat/MessageList.vue` 操作区无转发入口。
- **建议做法**：新增 `forwardedFrom` 元数据与会话选择弹窗，复用发送接口。
- **完成标准**：可把一条消息转发到另一个会话，接收方看到来源标记。
- **依赖/影响面**：`Message`、`MessageDTO`、`MessageService`、`MessageList.vue`、会话选择组件。
- **优先级**：P2
- **核对程度**：已读代码确认（按「全仓无实现」判断）。

#### BE-15 文件 URL 缺 `/api` 前缀

- **现状**：上传响应里的 `downloadUrl`/`previewUrl` 是 `/files/download/{id}`、`/files/preview/{id}`，而控制器实际挂在 `/api/files` 下。前端目前自己用 `API_BASE_URL` 拼 URL 绕过了这个字段，但任何直接使用后端字段的调用方都会 404。
- **缺什么**：统一 URL 约定；或返回相对路径并由客户端统一拼接。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/controller/FileUploadController.java:302-307`；`nexus-chat-frontend/src/services/api.js:441-443`。
- **建议做法**：后端返回带 `/api` 的完整路径，或只返回 `fileId` 由客户端构造；补契约测试。
- **完成标准**：后端返回的 URL 直接访问可用；前端不再需要特判。
- **依赖/影响面**：`FileUploadController`、`api.js`、`MessageList.vue`、`MessageInput.vue`；与 BE-01 一起改。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### X-05 前端有 DELETE 聊天方法，后端无端点

- **现状**：`chatAPI.deleteChat` 定义了 `DELETE /chats/{chatId}`，后端 `ChatController` 只有创建/查询，没有删除；前端 `chatStore.deleteChat` 只做本地隐藏，所以这个方法目前是死码，一旦被调用就会 404/405。
- **缺什么**：要么实现「退出/删除会话」语义，要么删掉死方法。
- **证据**：`nexus-chat-frontend/src/services/api.js:154-155`；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/ChatController.java:22-75`（无 DELETE）。
- **建议做法**：明确产品语义：私聊删除是隐藏还是清空历史；群聊用现有的 leave/delete。决定后补端点或删方法。
- **完成标准**：不再存在调用即 404 的方法；行为有文档。
- **依赖/影响面**：`api.js`、`ChatController`、`ChatService`、`chat.js`。
- **优先级**：P3
- **核对程度**：已读代码确认。

### 5.3 Agent 与 Companion

#### AG-01 总结/待办/回复建议/发布没有 UI 入口

- **现状**：前端 API 层定义了 `summarize`、`todoExtract`、`replySuggest`、`replyPublish`，后端也有对应端点；但 UI 里没有任何按钮或菜单调用它们。Agent 聊天页只用 `ASSISTANT_CHAT` 模式，Mode B 能力实际不可达。
- **缺什么**：会话内的「总结/待办/帮我回」入口与结果展示、发布确认；与「AI 代写」标记联动（BE-11）。
- **证据**：`nexus-chat-frontend/src/services/agentApi.js:58-61`；`src/stores/agent.js` 只使用 `AGENT_OP.ASSISTANT_CHAT`；`nexus-chat-backend/src/main/java/com/nexus/chat/controller/agent/AgentController.java:212-326`。
- **建议做法**：先在消息列表的工具菜单里接 `summarize`/`replySuggest`；发布必须二次确认并走正常发送流程。
- **完成标准**：用户能在会话内触发四类操作并看到结果；发布后消息出现在会话里。
- **依赖/影响面**：`AgentChatView.vue`、`MessageList.vue`/`MiddlePanel.vue`、`agent.js`、BE-11。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### AG-02 只有只读工具，`/messages/publish` 无调用方

- **现状**：Python 工具集只有 7 个只读查询（最近消息、会话资料、用户资料、按 id 取消息、按用户名找人、我的会话、找私聊）；没有发消息、建群等写操作，也没有「逐次用户确认」机制。Java 暴露的 `/internal/agent/messages/publish` 在 Python 侧没有调用。
- **缺什么**：写工具 + 确认协议 + 审计；`/messages/publish` 要么接入要么删除。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/agent/AgentToolService.java`（7 个 `@AgentTool` 方法）；`InternalAgentController` 的 `/messages/publish`。
- **建议做法**：写工具采用「计划 → 用户确认 → 执行」两阶段；确认走 Java 的聊天内卡片；补权限与幂等。
- **完成标准**：Agent 发起写操作前必须得到用户确认；未确认不落库。
- **依赖/影响面**：`AgentToolService`、`orchestrator.py`（需要 `interrupt()` 式的确认节点）、`InternalAgentController`、前端确认 UI。
- **优先级**：P2（ROADMAP M4）
- **核对程度**：已读代码确认。

#### AG-03 没有 MCP 双向集成

- **现状**（2026-11-21 更新）：内部链路已走 MCP —— Java 在 `/internal/agent/mcp` 提供只读工具，Python 作为客户端发现并调用。外部 MCP 服务只有接入机制（运维白名单 `MCP_EXTERNAL_SERVERS`，默认空），没有接入任何实际服务。
- **缺什么**：把工具提供给 Nexus 之外的 Agent（现在的端点只认内部令牌，且不经 nginx 对外）；接入并验证至少一个实际的外部 MCP 服务；外部工具的确认策略。
- **证据**：`nexus-chat-backend/.../config/AgentMcpConfig.java`、`nexus-agent-backend/app/mcp_tools.py`。
- **建议做法**：先做只读 MCP 暴露，再评估写工具；鉴权沿用内部令牌体系。
- **完成标准**：外部 Agent 能列出并调用只读工具；内部能接入至少一个外部工具。
- **依赖/影响面**：Agent 路由、工具注册、安全模型。
- **优先级**：P2（ROADMAP M4）
- **核对程度**：已读代码确认。

#### AG-04 出处只拼进提示词，没有结构化引用

- **现状**：知识库检索结果被格式化成 `[1] 文件名#chunkIndex (score=...)` 的文本块拼进 system prompt；接口不返回结构化 sources，前端也没有「查看出处/跳回原消息」的 UI。
- **缺什么**：响应里返回文档、页码/片段、消息 id；前端引用列表与跳转。
- **证据**：`nexus-agent-backend/app/knowledge/qa.py:22-41`；`app/knowledge/loaders.py:53-56`（PDF 有 page 元数据，但未透出）。
- **建议做法**：检索层保留 metadata，编排器把 sources 随最终答案返回；Java DTO 透传；前端渲染引用。
- **完成标准**：答案可展开看到具体文档/页码；总结类回答能跳到原消息。
- **依赖/影响面**：`qa.py`、`routes.py`、`AgentGatewayService`、`AgentController`、`AgentChatView.vue`。
- **优先级**：P2（ROADMAP M4）
- **核对程度**：已读代码确认。

#### AG-05 Agent 不是聊天成员、无 @助手、无未读追赶

- **现状**：聊天成员角色只有 owner/admin/member；Agent 会话是独立表与独立接口，不参与 `chat_members`，因此不能在群里被 @，也没有「从这里总结」入口；未读追赶依赖的增量同步本身不可用（X-03）。
- **缺什么**：机器人成员类型/虚拟成员、群内 @ 路由、打开会话时的「你错过了什么」摘要。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/model/ChatMember.java:29-33`；`service/agent/AgentSessionService.java:20-27`（会话独立存储）；`docs/ROADMAP.md:63-68`。
- **建议做法**：先做虚拟成员映射（不落真实用户），再在消息发送时识别 @ 并投递给 Agent；未读追赶复用 delta/摘要能力。
- **完成标准**：群成员能 @ 助手得到回复；AI 消息带标记；打开会话能看到错过内容摘要。
- **依赖/影响面**：成员模型、消息路由、Agent 网关、前端消息渲染。
- **优先级**：P2（ROADMAP M3）
- **核对程度**：部分确认（成员模型与会话结构已确认；@ 解析未实现由全仓检索确认）。

#### AG-06 会话历史仅存 Redis，7 天/50 条

- **现状**：Agent 会话索引在 MySQL，但消息内容存在 Redis list，TTL 7 天、最多保留 50 条；过期后历史会话能列出但内容为空。
- **缺什么**：是否要长期保存需产品决定；若保存，落 MySQL 并做分页。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/agent/memory/AgentMemoryService.java:23`、`47-52`、`106-108`；`service/agent/AgentSessionService.java:90-92`。
- **建议做法**：短期上下文继续 Redis，长期历史落库；或明确产品上「只保留最近 50 条」并在 UI 标注。
- **完成标准**：行为与产品约定一致；过期后 UI 不出现空白误导。
- **依赖/影响面**：记忆服务、会话列表、schema。
- **优先级**：P3
- **核对程度**：已读代码确认。

#### AG-07 nonce 重放缓存是单进程内存

- **现状**：Python 的 HMAC nonce 去重使用进程内 dict；注释也写明「single-process by design」。多 worker/多实例部署时，同一 nonce 可在不同进程重复使用；进程重启后缓存清空。
- **缺什么**：Redis 等共享存储的 nonce 去重，或明确只跑单 worker 并在部署上约束。
- **证据**：`nexus-agent-backend/app/security.py:38-52`；`nexus-agent-backend/Dockerfile` 的 uvicorn 默认单进程（未显式设置 worker 数）。
- **建议做法**：迁移到 Redis SETNX + TTL；或在 compose/部署文档中固定单 worker。
- **完成标准**：多进程下重放被拒；重启后窗口内 nonce 仍不可复用。
- **依赖/影响面**：`security.py`、Redis、部署配置。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### BE-16 Companion 仍在 Java 直连 LLM

- **现状**：ROADMAP 要求「Companion 并入 Agent，去掉 Java 直连 LLM 的管线」。当前 Java 仍有完整的 Companion 模型服务：`RestTemplate` 直连 OpenAI 兼容接口、流式解析、超时与 Token 上限、凭据加密与模型绑定；Companion 会话/角色/酒馆与 Agent 会话是两套体系，Provider/凭据也各有一套。
- **缺什么**：把 Companion 抽象成 Agent 的一个 persona/场景，模型调用统一走 Python；或至少在规划中明确暂缓并同步文档。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/service/CompanionModelService.java:56-80`、`91-118`；`service/TavernSessionService.java`、`service/CompanionPromptBuilder.java`；`controller/CompanionController.java`；`service/agent/AgentProviderService.java`（另一套 Provider）。
- **建议做法**：先统一「模型凭据 + Provider」存储，再把 Companion 的 prompt/会话迁到 Agent 编排层；迁移期保留兼容接口。
- **完成标准**：Companion 回复不再由 Java 直接发 HTTP 到模型；只有一套凭据/Provider；酒馆功能不回归。
- **依赖/影响面**：Companion 全部 service、Agent 网关、前端 `companion.js`/`RoleChatView.vue`、数据迁移。
- **优先级**：P2（架构收敛；影响 M1 完成判定）
- **核对程度**：已读代码确认。

### 5.4 范围遗留

#### BE-17 手机端专用接口与 nginx 反代未删

- **现状**：ROADMAP M1 要求删除手机端专用后端接口，但以下仍在：
  - `/ws-native` 端点仍注册，安全配置仍放行；
  - `/api/app/check-update` 仍匿名开放；
  - `/api/posts/**`（社区帖子，已冻结）整套 Controller/Service 仍在；
  - nginx 仍有 `/ws-native` 反代；
  - `/apk/**` 静态目录仍放行。
- **缺什么**：按冻结/删除决定清理；删除前确认桌面端与 Web 不依赖。
- **证据**：`nexus-chat-backend/src/main/java/com/nexus/chat/config/SecurityConfig.java:48`、`52-53`；`config/WebSocketConfig.java:63`；`controller/AppVersionController.java:16`、`26`；`controller/PostController.java:18`；`nginx/nginx.conf:86-100`。
- **建议做法**：删 `/ws-native` 注册与反代、删版本检查；帖子按产品决定删除或移入独立模块；APK 目录与 Dockerfile 中的 `/app/apk` 一起清理。
- **完成标准**：上述路径返回 404；前端无调用；nginx 配置无残留。
- **依赖/影响面**：SecurityConfig、WebSocketConfig、两个 Controller、nginx、Dockerfile、README。
- **优先级**：P2（范围收敛）
- **核对程度**：已读代码确认。

### 5.5 客户端体验与性能

#### FE-06 路由/Element Plus/Three.js 未按需加载

- **现状**：路由对登录、主界面、设置、资料页使用静态 import；`main.js` 全量 `app.use(ElementPlus)` 并注册全部图标；`Main.vue` 静态 import `CompanionAvatar3D`，后者在模块顶层 import three/GLTFLoader/FBXLoader/three-vrm。结果：即使 3D 默认关闭，Three.js 与 VRM 仍进入首屏依赖图。
- **缺什么**：路由懒加载；Element Plus 按需引入（unplugin-vue-components 等）；3D 组件 `defineAsyncComponent` + `petVisible` 时才加载。
- **证据**：`nexus-chat-frontend/src/router/index.js:2-6`；`src/main.js:3`、`5`、`17`、`21`；`src/views/Main.vue:70`；`src/components/companion/CompanionAvatar3D.vue:46-49`。
- **建议做法**：先做 3D 异步化（收益最大且与 ROADMAP 完成标准一致），再做 Element Plus 按需与路由拆分；用构建产物分析验证。
- **完成标准**：关闭 3D 时首屏 bundle 不含 three/VRM；主界面首包体积明显下降。
- **依赖/影响面**：`router/index.js`、`main.js`、`Main.vue`、`CompanionAvatar3D.vue`、`vite.config.js`、`package.json`。
- **优先级**：P2（ROADMAP M5）
- **核对程度**：已读代码确认。

#### FE-07 3D 默认关但资源未懒加载

- **现状**：`petVisible` 从 localStorage 读取，默认 false，主界面用 `v-if` 控制渲染；但组件是静态 import，资源下载已经发生。属于「视觉上默认关，网络层没关」。
- **缺什么**：见 FE-06 的异步组件方案；模型/动作按需 fetch（动作配置也是组件挂载后 fetch）。
- **证据**：`nexus-chat-frontend/src/stores/companion.js:45-50`；`src/views/Main.vue:29-30`、`70`；`CompanionAvatar3D.vue:46-49`、`638`、`676`。
- **建议做法**：`const CompanionAvatar3D = defineAsyncComponent(() => import(...))`，并确保未开启时不渲染。
- **完成标准**：默认登录不发任何 three/VRM/模型文件请求；打开开关后才加载。
- **依赖/影响面**：同 FE-06。
- **优先级**：P2（ROADMAP M1 未完成）
- **核对程度**：已读代码确认。

#### FE-08 IndexedDB 消息缓存无上限

- **现状**：消息会持续写入 IndexedDB；只有删除会话时才按 chatId 清理；读取默认 50 条，但存储不设上限、没有按会话裁剪或按时间清理。长期使用会持续膨胀。
- **缺什么**：每会话/全局条数或字节上限；LRU/按时间清理；迁移策略。
- **证据**：`nexus-chat-frontend/src/services/offlineStore.js:75-84`（读取 limit）、`141-145`、`180-183`（只有删除会话才清）；无定期清理逻辑。
- **建议做法**：保留最近 N 条 + 最近 M 天；写入后异步裁剪；提供「清除本地缓存」入口。
- **完成标准**：长时间使用后 IndexedDB 体积受控；清理不影响服务端数据。
- **依赖/影响面**：`offlineStore.js`、`message.js`、同步逻辑。
- **优先级**：P2（ROADMAP M5）
- **核对程度**：已读代码确认。

#### FE-09 Electron 无托盘/置顶，更新只检查不安装

- **现状**：主进程没有 `Tray`、没有 `setAlwaysOnTop`、没有开机自启；更新检查通过 GitHub Releases API 比较版本并返回下载链接，没有接入 electron-updater，也没有下载/安装流程。README 仍宣称有托盘与始终置顶。
- **缺什么**：按产品决定补托盘/置顶/自启；若保留手动更新，至少把「检查到新版本 → 打开下载页」在设置页接通并写清语义。
- **证据**：`nexus-chat-frontend/electron/main.js:1-2`（未引入 Tray）、`165-201`（仅检查）、`247`；`package.json:35-45`（无 electron-updater）；`README.md:868`、`1083`。
- **建议做法**：先修文档与 UI 表述；需要托盘时补 `Tray` 菜单与窗口隐藏/恢复；自动更新需签名与发布渠道。
- **完成标准**：README 与实际能力一致；若做托盘，关闭窗口后可从托盘恢复。
- **依赖/影响面**：`electron/main.js`、`preload.js`、设置页、打包配置、发布流程。
- **优先级**：P2
- **核对程度**：已读代码确认（未打包运行验证）。

#### FE-10 冻结的通话 UI 仍暴露

- **现状**：ROADMAP 冻结音视频通话，但主界面常驻挂载四个通话弹窗，会话头部仍有音频/视频按钮，点击会走 `callStore.initiateCall` 与 WebRTC 信令。用户会把它当成可用功能。
- **缺什么**：要么下线入口（按钮隐藏/禁用、弹窗按需挂载），要么明确解冻并补齐质量。
- **证据**：`nexus-chat-frontend/src/views/Main.vue:52-55`、`66-69`；`src/components/layout/MiddlePanel.vue:90-94`、`283-297`；`docs/ROADMAP.md:16`。
- **建议做法**：冻结期隐藏入口与常驻组件；保留代码但用开关控制。
- **完成标准**：界面上没有可发起的通话入口；代码仍可编译。
- **依赖/影响面**：`Main.vue`、`MiddlePanel.vue`、`call.js`、`webrtc.js`。
- **优先级**：P2（范围一致性）
- **核对程度**：已读代码确认。

#### FE-11 置顶/免打扰/隐藏只存本机

- **现状**：静音、置顶、隐藏会话都存在 `localStorage`，换设备/换浏览器不同步；服务端没有对应字段。是否要跨端同步是产品决定。
- **缺什么**：若要同步，需要服务端字段与接口；若不同步，需要在文档中明确。
- **证据**：`nexus-chat-frontend/src/stores/chat.js:39-78`。
- **建议做法**：短期保持本地并在文档标注；中期加到 `chat_members` 偏好字段。
- **完成标准**：行为与文档一致；如做同步，多端一致。
- **依赖/影响面**：`chat.js`、`ChatMember`、同步接口。
- **优先级**：P3
- **核对程度**：已读代码确认。

#### FE-12 设计稿字段与视觉件缺失

- **现状**：以下设计稿内容因「后端没有字段」被留空或未做：
  - 联系人职位/部门/工号（User 实体只有 username/email/phone/nickname/avatar/bio/background）；
  - 群共享文件、活跃度图表、群公告历史、群标签；
  - 「AI 润色优化」；
  - 语音消息气泡、群聊拼图头像；
  - 群组页的三栏贴边布局与设计稿的浮动卡片不同。
- **缺什么**：需要产品先决定哪些做；职位类字段涉及用户模型与隐私；共享文件涉及权限。
- **证据**：`nexus-chat-frontend/src/components/chat/GroupDetail.vue:4-5`；`nexus-chat-backend/src/main/java/com/nexus/chat/model/User.java:21-56`；`.alma/handoff-mwjzqivhjk38qc628d6.md:35-38`。
- **建议做法**：先做字段/权限设计评审，再分批实现；不要用假数据填充。
- **完成标准**：决定做的字段有真实数据与权限；不做的从设计验收清单移除。
- **依赖/影响面**：`User`、群模型、联系人/群资料 UI、i18n。
- **优先级**：P3
- **核对程度**：已读代码确认字段缺失；视觉差异未逐屏核对。

#### FE-13 `chatStore.createGroup` 是假实现

- **现状**：store 里的 `createGroup` 用 `Date.now()` 造 id，真实 API 调用被注释；当前 UI 走 `CreateGroupModal` + `chatAPI.createGroupChat`，所以这个 store 方法没有被使用。但它是可被误用的死码。
- **缺什么**：删除方法，或改为调用真实 API。
- **证据**：`nexus-chat-frontend/src/stores/chat.js:349-360`；`src/components/chat/CreateGroupModal.vue:305`（真实路径）。
- **建议做法**：删除死方法，避免后续页面误用。
- **完成标准**：全仓无假建群实现。
- **依赖/影响面**：`chat.js`。
- **优先级**：P3
- **核对程度**：已读代码确认。

#### FE-14 残留硬编码中文

- **现状**：i18n 的 zh/en 键完全对齐（各 1049 键，键集合无差异、无空英文值），但仍有少量中文直接写在代码里：Provider 显示名、3D 动作标签（部分有 `labelEn`）、部分开发页文案。
- **缺什么**：把这些字符串迁入 locale，或明确标注为品牌/开发专用不翻译。
- **证据**：`nexus-chat-frontend/src/stores/agentProviders.js:34-50`；`src/components/companion/CompanionAvatar3D.vue` 的动作 `待机/走路/思考`（带 `labelEn`）。
- **建议做法**：按「品牌文案 / 用户可见 / 仅开发」分类，前两类进 locale。
- **完成标准**：英文界面无非品牌中文；扫描脚本可复核。
- **依赖/影响面**：locale 文件、相关组件。
- **优先级**：P3
- **核对程度**：已读代码确认（采样，未逐条穷举全部中文）。

### 5.6 部署、质量与文档

#### OPS-01 生产构建把 API 地址解析成 localhost

- **现状**：`runtimeConfig` 对「以 `/` 开头的相对地址」一律丢弃并回退到 `http://localhost:8080/api`。Dockerfile 与 compose 恰恰用 `VITE_API_BASE_URL=/api` 构建 Web 版，于是打包后的生产前端会把所有 API 请求发到用户自己的 localhost。WS 地址是绝对 URL，不受影响，形成「WS 能连、REST 全挂」的典型现象。
- **缺什么**：相对路径应原样保留（同源部署），或在构建期生成绝对地址；回退值只用于本地开发。
- **证据**：`nexus-chat-frontend/src/services/runtimeConfig.js:6-24`；`nexus-chat-frontend/Dockerfile:14`；`docker-compose.yml:118`；`nexus-chat-frontend/.env.production:2`。
- **建议做法**：`resolveRuntimeUrl` 只在值为空时回退，保留 `/api`；或改为「生产必须显式配置」并在构建时校验。补一条构建后产物检查。
- **完成标准**：以 `/api` 构建的产物在 nginx 同源下能正常登录与拉数据；产物中不再出现 `localhost:8080`。
- **依赖/影响面**：`runtimeConfig.js`、Dockerfile、compose、`.env.*`、Electron 打包。
- **优先级**：P0（生产主链路）
- **核对程度**：已读代码确认（未实际部署验证）。

#### OPS-02 Docker 下 3D 资源目录与 nginx 未打通

- **现状**：Docker profile 把模型/动作写到 `/data/companion-assets`，compose 没有给这个目录挂卷；nginx 只代理 `/api`、`/ws`、`/ws-native`，没有 `/models`、`/motions`；前端组件却用相对路径 `/models/...`、`/motions/...` 从自身 origin 加载。结果：默认资源（构建进 dist）能看，运行期上传的新模型/动作在 Docker 部署里既会丢（无卷）也取不到（无代理）。
- **缺什么**：持久化卷；nginx 反向代理到 backend 的 `/models`、`/motions`；或前端改用后端绝对 URL。
- **证据**：`nexus-chat-backend/src/main/resources/application-docker.properties:85-88`；`docker-compose.yml:74-75`（仅 uploads）；`nginx/nginx.conf:39-108`（无 models/motions）；`nexus-chat-backend/src/main/java/com/nexus/chat/config/WebMvcConfig.java:39-44`；`nexus-chat-frontend/src/components/companion/CompanionAvatar3D.vue:205-208`、`638`、`676`。
- **建议做法**：加 `companion_assets` 卷；nginx 加两个 location；前端 URL 通过 `SERVER_BASE_URL` 拼接或保持同源。
- **完成标准**：Docker 部署中上传模型 → 重启容器仍在 → 刷新页面可加载。
- **依赖/影响面**：compose、nginx、Dockerfile、前端资源 URL、BE-02。
- **优先级**：P1
- **核对程度**：已读代码确认。

#### OPS-03 `ddl-auto=update`，迁移脚本不自动执行

- **现状**：开发与 Docker profile 都是 `ddl-auto=update`；仓库有 `resources/migrations/*.sql` 与 `schema_full.sql`，但没有 Flyway/Liquibase 依赖，也没有启动时执行迁移的机制。schema 变更靠 Hibernate 猜，历史脚本是否与实体一致无法保证。
- **缺什么**：迁移工具、基线、`ddl-auto=validate`、CI 中的迁移校验。
- **证据**：`nexus-chat-backend/src/main/resources/application.properties:17`；`application-docker.properties:11`；`nexus-chat-backend/src/main/resources/migrations/`（10 个日期脚本 + `schema_full.sql`）；`nexus-chat-backend/pom.xml` 无 flyway/liquibase。
- **建议做法**：以 `schema_full.sql` 建基线，引入 Flyway，把现有增量脚本整理为版本；先在生产库做 schema diff。
- **完成标准**：空库可由迁移脚本建全；实体与 schema 校验通过；`ddl-auto` 不再是 update。
- **依赖/影响面**：pom、resources、所有实体、部署流程；与 FE-01 的全文索引等未来变更相关。
- **优先级**：P2
- **核对程度**：部分确认（未逐表比对 `schema_full.sql` 与实体）。

#### OPS-04 CI 与主链路测试覆盖不足

- **现状**：CI 三个 job：后端 `mvn test`（排除启动测试）、Agent `pytest`、前端只 `npm run build`。后端测试集中在身份/MVC/WebSocket 鉴权、Agent 响应与 SSE 缓存、Companion 提示词与状态标签，没有消息发送、同步、已读、群管理、文件、鉴权注册/登录的主链路测试；前端没有 test/lint 脚本；没有 Testcontainers 启动测试；没有依赖/密钥扫描。
- **缺什么**：M0 的 Testcontainers 启动测试；消息/同步/群/已读的服务层测试；前端 lint + 组件测试；CI 增加密钥与依赖扫描。
- **证据**：`.github/workflows/ci.yml`；`nexus-chat-backend/src/test/java/**`（13 个文件，无上述主链路测试）；`nexus-chat-frontend/package.json:8-13`（无 test/lint）；`docs/ROADMAP.md:31`、`38`、`59`。
- **建议做法**：优先补「发消息 + 已读 + 同步 + 建群/踢人」服务层测试；再把启动测试接入 Testcontainers；前端至少加 ESLint。
- **完成标准**：PR 必须通过后端主链路测试与前端 lint；CI 能起 MySQL/Redis 跑启动测试。
- **依赖/影响面**：CI、pom、package.json、测试基础设施。
- **优先级**：P2
- **核对程度**：已读代码确认（未运行任何测试）。

#### OPS-05 `.env.example` 缺 compose 必需变量

- **现状**：compose 显式引用的变量里，只有 `CLOUDFLARE_TUNNEL_TOKEN` 没有出现在 `.env.example`；照示例部署时 cloudflared 会因为拿不到 tunnel token 而起不来。
- **缺什么**：补齐变量与说明；标注哪些是必填。
- **证据**：`docker-compose.yml:140`；`.env.example:1-32`；`docker-compose.yml:65`、`71-72`。
- **建议做法**：按「必填/可选」分组补全 `.env.example`；CI 或启动脚本校验必填变量。
- **完成标准**：按 `.env.example` 填空后 `docker compose up` 无变量缺失错误。
- **依赖/影响面**：`.env.example`、compose、部署文档。
- **优先级**：P3
- **核对程度**：已读代码确认。

#### OPS-06 生产域名、CORS、Electron 绕过逻辑硬编码

- **现状**：compose 的前端构建参数把 WS 域名写死为 `chat.angkin.cn`；Electron 主进程在响应头里把 `Access-Control-Allow-Origin` 改写为固定的 `http://127.0.0.1:5173`，并硬编码了生产域名与旧 IP；Spring 的 CORS 列表也硬编码了 `app://*`。换域名需要改多处，且 Electron 的重写逻辑在打包环境下是否成立没有实测。
- **缺什么**：域名与来源集中配置；Electron 端不要伪造 CORS，而是让服务端允许正确的 Electron origin（或走同源代理）。
- **证据**：`docker-compose.yml:119`；`nexus-chat-frontend/Dockerfile:15`；`.env.production:3`；`nexus-chat-frontend/electron/main.js:14-47`；`nexus-chat-backend/src/main/java/com/nexus/chat/config/SecurityConfig.java:67-74`。
- **建议做法**：统一由环境变量注入域名；Electron 打包走同源或正确 origin 白名单；补一次打包实测。
- **完成标准**：换域名只改环境变量；Electron 打包版登录/WS/文件均可用。
- **依赖/影响面**：compose、Dockerfile、env 文件、Electron 主进程、Spring CORS。
- **优先级**：P2
- **核对程度**：部分确认（Electron 打包行为未实测）。

#### DOC-01 README 多处与代码不符

- **现状**：
  - 5.1/5.8 宣称有托盘、始终置顶；Electron 主进程没有对应实现；
  - 3.5 宣称群成员变化、已读回执通过 `/topic/user.{userId}.messages` 承载；群事件实际发到 `/topic/group/{id}`，且前端不发已读；
  - 3.10 宣称 `src/test` 没有有效测试；实际已有 13 个测试文件；
  - 4.9 宣称仓库没有 Dockerfile、未纳入 compose；实际三端 Dockerfile 都在，compose 有 agent 服务；
  - 前端章节宣称「历史消息搜索」由组件配合完成；实际是本地 50 条搜索。
- **缺什么**：按当前代码重写相关小节，或标注「历史设计说明，不代表现状」。
- **证据**：`README.md:328`、`330`、`480-487`、`844-849`、`868`、`1083`、`1223`。
- **建议做法**：把 README 拆成「现状」与「历史设计」两类；现状部分以本文缺口为索引校对。
- **完成标准**：README 不再宣称未实现能力；测试/Dockerfile 描述与仓库一致。
- **依赖/影响面**：README。
- **优先级**：P2
- **核对程度**：已读代码确认。

#### DOC-02 DEVELOPMENT 环境表过期

- **现状**：`docs/DEVELOPMENT.md` 把生产写成「暂无，有了服务器后把预演那套搬过去」；但 compose 已包含生产域名构建参数与 Cloudflare Tunnel，说明生产部署已在进行中。文档会让新 Agent 误判部署状态。
- **缺什么**：更新环境表与部署说明；写清域名、Tunnel、证书与密钥来源（不写值）。
- **证据**：`docs/DEVELOPMENT.md:36-43`；`docker-compose.yml:112-149`。
- **建议做法**：补「当前生产/预演在哪、由谁维护、如何发布」。
- **完成标准**：文档与 compose/实际部署一致。
- **依赖/影响面**：DEVELOPMENT、README 部署章节。
- **优先级**：P3
- **核对程度**：已读代码确认。

#### DOC-03 ROADMAP 勾选与代码不一致

- **现状**：ROADMAP 有多个勾选与本轮核对结果不符（详见第 6 节），例如 Agent Dockerfile 实际已完成但未勾；M0 身份项已做但仍有前端兼容残留；M2/M3/M4/M5 多数未做但描述像是短期计划。
- **缺什么**：逐条以代码为准更新勾选与完成标准。
- **证据**：`docs/ROADMAP.md:23-86` 对照第 6 节。
- **建议做法**：以第 6 节表为草稿更新 ROADMAP，并把每条完成标准写成可验证行为。
- **完成标准**：ROADMAP 勾选与代码一致；未完成项都有对应缺口编号。
- **依赖/影响面**：ROADMAP 与本文。
- **优先级**：P2
- **核对程度**：已读代码确认。

## 6. ROADMAP 对照表

状态说明：**已完成** = 代码满足描述；**部分** = 有实现但缺关键一半；**未开始** = 未找到实现；**无法核验** = 需要运行/平台权限。

### M0 止血和地基

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 删除手机端部署脚本/未使用配置依赖 | 部分 | 手机源码已移出 git（`git ls-files nexus-chat-app`=0），但 `/ws-native`、版本检查、帖子、APK、nginx 反代仍在 | BE-17 |
| 运行数据移出版本管理 | 已完成 | `.gitignore:80-82` 忽略 `/uploads/`、backend uploads、agent data | — |
| Agent 测试与 `.env`/真实向量库隔离 | 已完成 | `nexus-agent-backend/tests/conftest.py` 切临时目录 | — |
| CI 配置 | 已完成 | `.github/workflows/ci.yml` 三个 job | OPS-04（覆盖不足） |
| 身份统一从令牌取 | 已完成（抽查） | `security/CurrentUser.java`；各 Controller 使用 `CurrentUser.require` | 未逐接口穷举 |
| 删除 WS `userId` 兼容分支 | 后端已完成，前端有残留 | `WebSocketAuthChannelInterceptor.java:52-56` 强制 Bearer；`websocket.js:96-97` 仍保留 fallback | — |
| 在真实服务上走一遍 | 无法核验 | 本次未启动服务 | — |
| 3D 资源权限 | 未开始 | `CompanionAssetController` 无鉴权 | BE-02 |
| 文件下载/预览鉴权 | 未开始 | `SecurityConfig:49` + Controller 无校验 | BE-01 |
| 整聊已读成员校验 | 未开始 | `MessageService:298-305` | BE-04 |
| 内部令牌默认值 | 未开始 | `application.properties:108`；`config.py:43` | BE-03 |
| 配置文件密钥改环境变量 + 扫描 | 部分 | 已用 `${ENV}`，但仍有默认值；CI 无扫描 | BE-03、OPS-04 |
| 轮换已泄露密钥 | 无法核验 | 需要运维确认 | BE-03（标注未知） |
| Testcontainers 启动测试 | 未开始 | pom 无 testcontainers；CI 排除启动测试 | OPS-04 |
| 保护 `main` | 无法核验 | 需要 GitHub 仓库设置 | — |

### M1 收敛范围

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 删除手机端接口/版本检查/`/ws-native`/nginx 反代 | 未开始 | 路径与配置均在 | BE-17 |
| `nexus-chat-app` 移出仓库 | 已完成 | `git ls-files` 为 0；`.gitignore:90` | — |
| Companion 并入 Agent，去掉 Java 直连 LLM | 未开始 | `CompanionModelService` 仍用 RestTemplate 直连 | BE-16 |
| 3D 默认关闭，打开后才下载 Three.js/模型 | 部分 | 默认关已做；静态 import 导致照样下载 | FE-07、FE-06 |
| 引入迁移工具，`ddl-auto=validate` | 未开始 | 无 Flyway/Liquibase；`ddl-auto=update` | OPS-03 |
| Agent Dockerfile + compose | 已完成（ROADMAP 未勾） | 三端 Dockerfile 与 compose agent 服务均存在 | — |

### M2 消息可靠性

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 消息序号数据库兜底 | 未开始 | Redis INCR，无 DB 回填 | BE-10 |
| 持久化发件箱 | 未开始 | `queuePendingMessage` 无调用方 | X-02 |
| 客户端送达确认 + 按序号补洞 | 部分 | ACK 与 `findByChatIdAndSequenceNumberGreaterThan` 存在，但客户端不补洞 | X-02、BE-10 |
| 发消息/同步主链路测试 | 未开始 | `src/test` 无对应测试 | OPS-04 |

### M3 Agent 进对话

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 修复 `reply-publish` | 未开始 | 直接 `messageRepository.save` | BE-11 |
| Agent 作为聊天成员 | 未开始 | 成员角色无 bot；Agent 会话独立 | AG-05 |
| 群里 @助手、从消息菜单总结 | 未开始 | 无 @ 解析、无入口 | AG-05、AG-01 |
| 未读追赶 | 未开始 | 依赖的 delta 同步不可用 | X-03、AG-05 |

### M4 可信和开放

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 写工具逐次确认 | 未开始 | 仅 7 个只读工具 | AG-02 |
| 答案带出处 | 未开始 | 引用只拼进 prompt，无结构返回 | AG-04 |
| MCP 双向 | 未开始 | 无 MCP 实现 | AG-03 |

### M5 客户端瘦身

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 路由/组件按需加载，Element Plus 按需 | 未开始 | 静态 import + 全量注册 | FE-06 |
| 虚拟滚动 + 缓存上限 | 未开始 | 固定 50 条全量渲染；缓存无裁剪 | FE-03、FE-08 |
| 实测 3D 内存后评估 Tauri | 未开始 | 无测量代码/记录 | FE-07（未实测） |

### 「之后」

| ROADMAP 项 | 实际状态 | 依据 | 缺口 |
|---|---|---|---|
| 多设备同步游标 | 未开始 | delta 同步本身不可用；无设备游标 | X-03 |
| 网页推送 | 未开始 | 只有 Electron 本地通知 | FE-09 |
| 群协作（公告/邀请链接/权限/日志） | 未开始 | 无邀请链接、无管理日志；公告无独立字段 | FE-02、FE-12 |
| 搜索升级（全文/跨聊天/发送人/日期） | 未开始 | 服务端 LIKE 单会话；前端本地 50 条 | FE-01 |
| 检索质量（混合/重排/父子切片） | 未开始 | Python 无 rerank/hybrid/BM25 实现 | —（未列入编号，按需新增） |
| 通话总结 | 未开始 | 通话冻结，无总结 | FE-10 |

## 7. 冻结模块现状

本节只描述现状，不作为待办。

- **社区帖子**：`PostController` 提供帖子、投票、收藏、评论等一整套 REST；前端没有对应页面/调用；数据表与 Service 仍在。冻结期实际效果是「后端仍在运行、无入口」。
- **编排引擎**：只有 LangGraph 一套（`orchestrator.py`），手写循环已删除；`ENGINE` 只接受 `langgraph`。
- **音视频通话**：WebRTC 信令、`call.js`、`webrtc.js`、四个通话弹窗、Electron 媒体权限与防休眠均存在；UI 仍暴露入口（见 FE-10）。冻结期间没有质量投入的迹象（无测试覆盖）。
- **手机端**：git 已无源码；本机残留 `nexus-chat-app/` 被忽略；相关后端接口未删（BE-17）。

## 8. 需要用户决定的问题

> 与 `docs/BOUNDARIES.md` 第 10 节是同一份清单。本文是**逐缺口展开的原始出处**，BOUNDARIES 是**收拢版**；两处内容等价，任一拍板后请两处同步更新（或在此标注结论），避免漂移。

以下问题无法从代码判断，必须由产品或用户拍板，否则实现方向会摇摆：

1. **群公告/标签/共享文件/活跃度**：继续按设计稿补齐，还是从验收清单删除？如果要补，共享文件的权限模型（全员可见/管理员可见）怎么定？
2. **联系人职位/部门/工号**：是否需要？涉及用户资料隐私与同步来源（手动填写/组织架构导入）。
3. **忘记密码**：是否允许邮箱验证码重置？重置后是否强制吊销所有设备会话？
4. **2FA**：是否立即强制登录校验（会影响现有用户登录流程与客户端版本兼容）？
5. **拉黑语义**：拉黑后历史消息是否保留？群聊里是否屏蔽对方消息？被拉黑方是否收到明确提示？
6. **Companion 合并节奏**：立即迁移到 Agent，还是先把两套 Provider/凭据统一、保留双轨到 M4 之后？
7. **冻结能力**：社区帖子代码删除还是保留？通话入口是否立即隐藏？
8. **Electron**：是否需要托盘常驻、开机自启、自动更新？自动更新需要签名与发布渠道投入。
9. **部署形态**：Web 与 Electron 是否都指向同一个生产域名？是否继续用 Cloudflare Tunnel？这决定 OPS-01/OPS-06 的修法。
10. **多端偏好**：置顶/免打扰是否需要跨设备同步？
11. **消息删除**：私聊「删除会话」是本地隐藏、双方清空，还是仅退出？这决定 X-05 走向。

## 9. 未覆盖范围与已知不确定项

### 9.1 没做的事

- **没有运行任何服务/构建/测试/Docker/浏览器**，因此所有「运行时会怎样」的判断都来自代码推导；包括 OPS-01 的生产 API 失效、Electron 打包后的 CORS 行为、Docker 资源路径问题。
- **没有逐表比对** `schema_full.sql` 与 JPA 实体，也没有核对 10 个增量 SQL 与实体字段是否一致。
- **没有穷举所有 Controller 的鉴权**：M0「身份统一从令牌取」只做了 `CurrentUser` 与若干 Controller 的抽查，没有对每个映射逐一验证。
- **没有测量** 3D 开关前后的内存/包体积，也没有统计消息列表的实际 DOM 节点数；`.alma` 交接单里的经验值只作为线索，未复现。
- **没有验证** GitHub 上 `main` 是否受保护、CI 最近一次是否全绿、密钥是否已轮换。
- **没有核对** Flutter 手机端（已明确不在范围）。
- **没有逐屏核对**设计稿与实现的视觉差异，只核对了「因后端无字段而缺失」的条目。
- **没有验证** Python 真实模型路径（需要 Provider key），Agent 相关结论均基于配置与代码分支。

### 9.2 已知不确定项

| 事项 | 为什么不确定 | 如何补齐 |
|---|---|---|
| 历史群数据的 `chat_members.role` | 代码层面对新建群确定；老数据可能已被修复，也可能仍是 member/null | 查生产库 `SELECT role, COUNT(*) FROM chat_members GROUP BY role`，并按 `chats.created_by` 对账 |
| 生产实际部署是否就是仓库这份 compose | 仓库只代表配置，线上可能手工改过 | 拉取服务器上的 compose/nginx 配置对比 |
| Electron 打包版 API/CORS 是否可用 | `enableCorsForBackend` 固定改写为开发 origin，file:// origin 行为需实机验证 | 打一次包，抓登录与 WS 请求 |
| 3D 上传在开发环境是否覆盖默认资源 | 开发时资源根指向前端 `public/`，会直接改写工作区文件 | 在干净分支上传后看 `git status` |
| `schema_full.sql` 与实体的一致性 | 未逐表比对 | 用迁移工具建空库后跑 `ddl-auto=validate` |
| 邮件服务在部署环境是否可用 | 未发信测试；异步失败会被吞 | 在预演环境发一次验证码并查日志/收件箱 |
| 生产环境 JWT/Companion 密钥是否为默认值 | 代码只能说明「缺省会回退」，无法知道环境变量 | 运维检查环境变量与启动日志 |
| 冻结模块是否仍有用户在用 | 无访问日志/统计 | 查 nginx/后端访问日志中 `/api/posts`、通话信令量 |
| `nexus-chat-app/` 本地残留是否会误提交 | 目录被 ignore，但本机仍存在 | 提交前 `git status --ignored` 复核 |

### 9.3 本次实际阅读范围（便于评估覆盖率）

- **Java 后端**：`SecurityConfig`、`WebSocketAuthChannelInterceptor`、`WebSocketConfig`（检索）、`CurrentUser`（前次）、`AuthController`/`AuthService`、`SecurityService`（2FA 段）、`VerificationCodeService`、`EmailService`、`ChatController`/`ChatService`（创建与列表段）、`GroupController`/`GroupService`（全量）、`MessageController`/`MessageService`（发送/已读/搜索段）、`MessageRepository`（检索）、`SyncController`、`FileUploadController`、`CompanionAssetController`、`CompanionModelService`（头部）、`AgentController`（端点表与 reply-publish）、`InternalAgentController`（publish）、`AgentSessionService`、`AgentMemoryService`、`MessageSequenceService`、`RedisCacheService`（序号段）、`WebMvcConfig`、`ChatMember`、`Message`、`User`、`EmailVerificationCode` 等。
- **Python Agent**：`config.py`、`security.py`、`tools.py`、`routes.py`（前次）、`orchestrator.py`（前次）、`knowledge/qa.py`、`knowledge/loaders.py`（元数据段）、`rag/*`（检索段）、测试目录清单。
- **前端**：`api.js`、`websocket.js`、`runtimeConfig.js`、`syncService.js`、`offlineStore.js`、`stores/chat.js`、`stores/message.js`、`stores/agent.js`（调用段）、`stores/agentProviders.js`、`stores/companion.js`（默认值段）、`Main.vue`、`MiddlePanel.vue`、`RightPanel.vue`（群管理段）、`MessageList.vue`、`MessageInput.vue`、`SearchMessagesModal.vue`、`GroupDetail.vue`、`CreateGroupModal.vue`、`AddGroupMemberModal.vue`（调用段）、`Login.vue`（表单段）、`AccountSecurityModule.vue`、`router/index.js`、`main.js`、`vite.config.js`、`electron/main.js`、`agentApi.js`、`knowledgeApi.js`、`package.json`、`.env.*`、`Dockerfile`、`FileUpload.vue`（分片段）。
- **部署/文档**：`docker-compose.yml`、`nginx/nginx.conf`、三端 `Dockerfile`、`.env.example`、`.github/workflows/ci.yml`、`docs/ROADMAP.md`、`docs/DEVELOPMENT.md`、`README.md`（相关小节）、`.alma/handoff-mwjzqivhjk38qc628d6.md`、`.gitignore`。

**覆盖率自评**：三个子项目的主链路入口（Controller/路由、主要 Service、主要页面与 API 层）基本覆盖；未覆盖的是低频工具类、完整 DTO/实体字段逐一比对、所有前端子组件、以及全部运行时行为。任何本文没写到的模块，不代表没有问题，只代表这次没有读到。
