<div align="center">
  <img src="./nexus-chat-frontend/public/icons/icon.png" alt="Nexus Chat Logo" width="120" />
  <h1>Nexus Chat</h1>
  <p><strong>使用Spring Boot、Electron/Vue3和Flutter构建的多客户端实时聊天系统。</strong></p>
</div>

<div align="center">

![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-STOMP-111111?style=for-the-badge&logo=socketdotio&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-Presence%20%26%20Relay-DC382D?style=for-the-badge&logo=redis&logoColor=white)

![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?style=for-the-badge&logo=vuedotjs&logoColor=white)
![Electron](https://img.shields.io/badge/Electron-Desktop-47848F?style=for-the-badge&logo=electron&logoColor=white)
![Flutter](https://img.shields.io/badge/Flutter-Mobile-02569B?style=for-the-badge&logo=flutter&logoColor=white)

![Python](https://img.shields.io/badge/Python-Agent-3776AB?style=for-the-badge&logo=python&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-Agent%20Service-009688?style=for-the-badge&logo=fastapi&logoColor=white)
![LangChain](https://img.shields.io/badge/LangChain-RAG-1C3C3C?style=for-the-badge&logo=langchain&logoColor=white)
![ChromaDB](https://img.shields.io/badge/ChromaDB-Vector%20Store-FF6F00?style=for-the-badge)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-Reverse%20Proxy-009639?style=for-the-badge&logo=nginx&logoColor=white)

</div>

## 1. 项目整体定位

这不是一个单一前端项目，而是一个完整的"即时通讯 + AI Agent"产品工作区，分为四个模块:

| 目录 | 角色 | 主要技术 |
|---|---|---|
| `nexus-chat-backend` | 核心服务端 (Java 网关)，提供鉴权、消息、群组、联系人、社区、AI 陪伴、文件、同步、版本更新、Agent/知识库网关等能力 | Spring Boot 3.2, Java 17, MySQL, Redis, STOMP WebSocket |
| `nexus-agent-backend` | Python Agent 服务，负责 LLM 调用、工具编排、短期/长期记忆、RAG、知识库向量化与检索 | Python 3.10+, FastAPI, LangChain, LangGraph, ChromaDB, Redis, OpenAI / Anthropic / Gemini / OpenAI-compatible |
| `nexus-chat-frontend` | Web + Electron 桌面端主界面，功能最完整 | Vue 3, Pinia, Vite, Element Plus, Electron, Dexie, Three.js |
| `nexus-chat-app` | Flutter 移动端客户端，偏移动场景体验 | Flutter, Dio, STOMP, Hive, SecureStorage, 本地通知 |

可以把它理解成:

- `chat-backend` 是唯一的业务中台和数据源，同时也是 Agent 模块对客户端暴露的网关。
- `agent-backend` 是独立部署的 Python Agent 服务，只对内网/`chat-backend` 开放，通过 HMAC 签名互信。
- `frontend` 是桌面/Web 主客户端，承担最丰富的交互能力，已深度接入 Agent 与知识库。
- `app` 是面向手机的独立客户端，复用后端接口，但 UI 和实现方式完全独立。

## 2. 根目录协作关系

根目录不仅是三个子项目的容器，还负责把它们串起来。

### 2.1 部署入口

- `docker-compose.yml`
  - 启动 `mysql + redis + backend + frontend + cloudflared`
  - 适合 Web/Electron 体系的公网发布
- `docker-compose-app.yml`
  - 启动 `mysql + redis + backend`
  - 让手机 App 直接通过公网 IP 访问后端
- `nginx/nginx.conf`
  - 负责 Vue 静态资源
  - 反代 `/api`
  - 反代 `/ws` 和 `/ws-native`
  - 暴露 `/uploads`
- `.env.example`
  - 定义 MySQL、JWT、CORS、邮件、Companion 密钥等运行参数
- `nexus-agent-backend`
  - 当前不在根目录的 docker-compose 里，独立运行 (默认 `:8100`)
  - 由 `nexus-chat-backend` 通过 HMAC 内网调用，无需对客户端直连
  - 详见 `agent开发文档/`

### 2.2 整体通信关系

```mermaid
flowchart LR
    A[Flutter App] -->|REST /api| B[Spring Boot Backend]
    A -->|STOMP /ws-native| B
    C[Vue Web] -->|REST /api via Nginx| B
    C -->|SockJS STOMP /ws via Nginx| B
    D[Electron Desktop] -->|REST /api| B
    D -->|SockJS STOMP /ws| B
    B --> E[MySQL]
    B --> F[Redis]
    B <-->|models / motions assets| G[frontend/public]
    B -->|HMAC + X-Model-* headers| H[Python Agent Backend]
    H -->|内部 /internal/agent 工具回调| B
    H --> I[ChromaDB / Vector Store]
    H --> F
    H -->|LLM API| J[(OpenAI / Anthropic / Gemini / OpenAI-compatible)]
```

关键点:

- Web/Electron 走 `/ws`，依赖 SockJS 兼容层。
- Flutter 走 `/ws-native`，直接使用原生 WebSocket STOMP。
- 后端除数据库外，还把 Redis 用在在线状态、未读数、离线消息、消息序号、跨实例转发。
- Companion 3D 资产在本地开发模式下和 `nexus-chat-frontend/public/models`、`public/motions` 直接耦合。
- Agent 调用链是单向闭环: `客户端 → Java 网关 → Python Agent → 反向调用 Java 内部 /internal/agent 工具`。Java 永远不直接调 LLM，Python 也不直接面对客户端。
- Agent 端走 BYOK 模式: 客户端在网关存放 provider/key (加密)，请求时由 Java 通过 `X-Model-*` 头转发到 Python，Python 不持久化用户密钥。

## 3. `nexus-chat-backend` 详解

### 3.1 项目定位

`nexus-chat-backend` 是整个产品的业务核心。它不仅负责用户、聊天、群组和文件，还扩展了:

- 社区帖子
- 关注/粉丝
- 增量同步
- App 更新检查
- AI 陪伴角色、记忆、状态、模型绑定
- 3D 模型和动作资源管理
- Agent 会话、长期记忆审计、模型 Provider 凭据
- 知识库 (Knowledge Base) 文档管理与 Python Agent 网关

### 3.2 技术栈与规模

- Spring Boot 3.2.0
- Java 17
- Spring Web / Security / WebSocket / Data JPA / Data Redis / Mail
- MySQL
- Redis
- JWT
- Maven

当前源码规模大致为:

- `183` 个 Java 源文件
- `18` 个 Controller (含 `controller/agent/` 子包下的 4 个 Agent/KB 控制器)
- `24` 个 Service
- `37` 个 Entity/Model
- `37` 个 Repository

### 3.3 目录结构

核心结构如下:

- `src/main/java/com/nexus/chat/NexusChatApplication.java`
  - 应用入口，开启异步和定时任务
- `config/`
  - 安全、CORS、WebSocket、Redis、国际化、限流、消息校验
- `controller/`
  - REST API 入口层
- `service/`
  - 业务逻辑层
- `repository/`
  - JPA 数据访问层
- `model/`
  - 数据实体
- `dto/`
  - 前后端传输对象
- `security/`
  - JWT 鉴权过滤器和 Token Provider
- `websocket/`
  - 实时消息控制器
- `resources/`
  - `application*.properties`、建表 SQL、日志、国际化、迁移脚本

### 3.4 REST API 能力边界

从 Controller 划分看，这个后端已经不是“纯聊天 API”，而是完整社交产品后端。

#### 认证与用户

- `AuthController`
  - 发送验证码
  - 校验验证码
  - 注册
  - 登录
  - 登出
- `UserController`
  - 用户查询、搜索、推荐
  - 资料读取与更新
  - 头像上传/删除
  - 隐私设置
  - 背景图
  - 社交链接
  - 活动流
  - 资料更新后通过 WebSocket 广播 `USER_PROFILE_UPDATED`

#### 聊天与消息

- `ChatController`
  - 创建私聊
  - 创建群聊
  - 获取用户聊天列表
  - 获取聊天详情
- `MessageController`
  - REST 发送消息
  - 分页获取消息
  - 单条/整聊已读
  - REST 发消息后仍会触发统一 WebSocket 通知

#### 联系人和群组

- `ContactController`
  - 添加联系人或发送好友申请
  - 删除联系人
  - 联系人列表
  - 是否为好友
  - 共同好友
  - 好友申请收件箱/发件箱/数量
  - 接受/拒绝申请
- `GroupController`
  - 群信息、群成员、加人、踢人、退群、解散、管理员、转让群主

#### 社区能力

- `PostController`
  - 发帖、删帖
  - 推荐/热门/最新
  - 用户帖子
  - 搜索
  - 点赞/点踩
  - 收藏
  - 评论、回复、评论点赞
- `FollowController`
  - 关注、取消关注
  - 关注状态
  - 关注列表/粉丝列表

#### 文件与版本

- `FileUploadController`
  - 单文件上传
  - 分片上传
  - MD5 秒传
  - 文件信息
  - 下载
  - 在线预览
- `AppVersionController`
  - `GET /api/app/check-update`
  - 给移动端提供版本号、下载地址、更新日志、强更标记

#### 增量同步

- `SyncController`
  - `GET /api/sync/delta`
  - 按 `since` 时间戳增量同步消息、聊天和联系人
  - 这和桌面端 IndexedDB 离线缓存设计是配套的

#### Companion / AI 陪伴

- `CompanionController`
  - 初始化默认角色
  - 获取/更新角色
  - 获取对话
  - 发送消息
  - 管理记忆
  - 获取成长值和状态
  - 保存模型凭据
  - 绑定模型与 endpoint
- `CompanionAssetController`
  - 上传/重命名 3D 模型
  - 上传/重命名/删除动作文件
  - 读取模型库和动作库

#### Agent 网关 (`controller/agent/`)

这是和 `nexus-agent-backend` 配套的 Java 侧网关层，独立于 Companion 模块。

- `AgentController`  (`/api/agent/*`)
  - 会话管理: 创建/列出/删除会话、拉历史消息
  - 实时对话: `POST /sessions/{id}/chat` 与 `POST /sessions/{id}/chat/stream` (SSE)
  - 长期记忆: 删除会话记忆、删除单条记忆、整体重置
  - 业务原子操作: `chats/{chatId}/summarize`、`todo-extract`、`reply-suggest`、`reply-publish`
- `AgentProvidersController`  (`/api/agent/providers`)
  - 用户的 LLM Provider/Key 管理
  - 设为默认、连通性测试
- `KnowledgeBaseController`  (`/api/agent/knowledge`)
  - 知识库 CRUD
  - 文档上传/列表/删除/状态查询
  - 触发 Python Agent 端的向量化入库
- `InternalAgentController`  (`/internal/agent/*`)
  - 仅供 Python Agent 反向调用，HMAC 鉴权
  - 暴露 `recent-messages / chat-profile / user-profile / by-username / messages / me/chats` 等工具接口
  - `/messages/publish` 让 Agent 代发消息

### 3.5 实时通信架构

这是后端最关键的一层。

#### WebSocket 入口

- `/ws`
  - SockJS STOMP 端点，给 Web/Electron 用
- `/ws-native`
  - 原生 WebSocket 端点，给 Flutter 用

#### STOMP 约定

- Broker 前缀: `/topic`, `/queue`
- 应用前缀: `/app`
- 用户前缀: `/user`

#### 统一用户频道

当前实现的核心思想是:

- 所有实时事件尽量统一投递到 `/topic/user.{userId}.messages`
- 不再以“每个 chat 一个 topic”作为主通信模型

这个频道承载:

- 新消息
- ACK
- 输入中状态
- 已读回执
- 群成员变化
- 通话信令
- 错误事件

#### Redis 在实时层的作用

`RedisCacheService` 和 `RedisMessageRelay` 让 WebSocket 具备了更接近生产系统的能力:

- 在线状态 Presence
  - 90 秒 TTL
  - 客户端每 30 秒发心跳
  - 支持多设备会话
- 离线消息队列
  - 目标用户离线时先写 Redis List
  - 上线后回放
- 输入中状态
  - 5 秒 TTL
- 未读数
- 聊天缓存
- 消息序号
  - 通过 Redis INCR 生成单调递增 `sequenceNumber`
- 跨实例转发
  - 通过 Redis Pub/Sub 广播到其它实例，再由本地实例判断目标用户是否在线

#### 其他实时细节

- `WebSocketAuthChannelInterceptor`
  - WebSocket 优先走 JWT
  - 仍兼容旧式 `userId` 头
- `MessageValidationInterceptor`
  - 文本校验、XSS 清洗、URL 校验
- `WebSocketRateLimiter`
  - 基于 Redis 的限流
- `WebSocketController`
  - 覆盖消息、状态、typing、read receipt、group event、contact event、call signaling

### 3.6 数据模型

从 `schema.sql` 和 `model/` 可以看出，数据库已经覆盖了多种业务域。

核心表包括:

- `users`
- `user_privacy_settings`
- `contacts`
- `contact_requests`
- `chats`
- `chat_members`
- `messages`
- `message_read_status`
- `file_uploads`

社交和扩展表包括:

- `posts`
- `post_comments`
- `post_votes`
- `post_bookmarks`
- `comment_likes`
- `user_follows`
- `user_social_links`
- `user_security_settings`
- `user_sessions`
- `login_history`
- `user_activities`

Companion 相关表包括:

- `companion_roles`
- `companion_conversations`
- `companion_messages`
- `companion_memories`
- `companion_growth`
- `companion_status`
- `model_credentials`
- `companion_model_bindings`

Agent / 知识库相关表包括:

- `agent_session`
- `agent_session_summary`
- `agent_long_memory`
- `agent_memory_embedding`
- `agent_memory_audit`
- `knowledge_base`
- `knowledge_document`

### 3.7 文件与定时任务

后端不只是保存元数据，文件生命周期也有管理逻辑。

- 上传目录: `uploads/`
- 支持单文件上传和分片合并
- `FileCleanupService`
  - 每天凌晨 3 点清理过期文件
  - 清理未完成上传
  - 清理孤立分片目录

### 3.8 Companion 模块的设计特点

这一块是本项目区别于普通 IM 项目的重点。

- 初始会为用户创建 3 个默认角色
  - 温柔倾听者
  - 理性伙伴
  - 活力陪玩
- 每个角色有:
  - 名称
  - traits
  - tone
  - baseline mood
  - growth
  - status
  - memory
  - conversation history
- 模型调用方式不是写死 OpenAI SDK，而是“OpenAI-compatible endpoint”
  - 客户端保存 provider / model / endpoint
  - 服务端保存并加密 API Key
  - `CompanionModelService` 按 `/v1/chat/completions` 规范请求
- 如果远程模型失败，会回退到 `CompanionFallbackService`

这意味着 Companion 模块本质上是一个“可配置的陪伴型 LLM 中间层”，而不是单纯的前端假 UI。

### 3.9 配置与部署

#### 开发/生产/容器配置

- `application.properties`
  - 本地开发配置
- `application-prod.properties`
  - 生产配置
- `application-docker.properties`
  - 容器环境配置

#### Docker 化

- `Dockerfile` 使用多阶段构建
  - Maven 构建 JAR
  - Temurin 17 JRE 运行
- 运行容器时会创建:
  - `/app/uploads`
  - `/app/apk`

#### 与前端资源目录的耦合

Companion 资产路径有两种模式:

- 本地开发: 指向 `../nexus-chat-frontend/public`
- Docker: 指向 `/data/companion-assets`

这说明 Companion 资源管理是“后端可写、前端可直接静态读取”的设计。

### 3.10 当前观察

当前后端实现已经很丰富，但有几处工程信号值得单独记住:

- `application.properties` 和 `application-prod.properties` 中存在硬编码敏感配置，不适合继续保留在仓库里。
- 多个 profile 都在使用 `spring.jpa.hibernate.ddl-auto=update`，上线环境存在 schema 漂移风险。
- 自动化测试基本缺失，`src/test` 下没有有效测试代码。
- WebSocket、Redis、同步、Companion 都已经进入"可运行复杂系统"阶段，但回归保障不足。

## 4. `nexus-agent-backend` 详解

### 4.1 项目定位

`nexus-agent-backend` 是 Nexus 的 Agent 中台，独立于 Java 网关运行的 Python 服务。

它承担:

- LLM 调用 (OpenAI / Anthropic / Gemini / OpenAI-compatible 多家)
- 工具编排 (ReAct + 反向回调 Java 内部接口)
- 短期记忆 (Redis) + 长期记忆 (RAG)
- 知识库文档入库与向量检索 (ChromaDB)
- 流式输出 (SSE)
- 双引擎: 手写 ReAct 与 LangGraph StateGraph 可热切换

它不直接对外，所有客户端流量先到 Java 网关 `/api/agent/*`，再经 HMAC 内部调用进入 Python。

### 4.2 技术栈与规模

- Python 3.10+
- FastAPI 0.115 + uvicorn
- pydantic v2 / pydantic-settings
- httpx (异步)
- redis (短期记忆 / 限流)
- openai SDK (兼容 DeepSeek / Moonshot / Groq / DashScope 等)
- LangChain 0.3 + LangChain Community / Chroma / OpenAI / TextSplitters
- LangGraph 0.2 (备选编排引擎)
- ChromaDB 0.5 (本地持久化向量库)
- structlog (结构化日志)
- pypdf / unstructured / python-docx / docx2txt / markdown (Module B 文档加载器)
- pytest + pytest-asyncio

源码规模大致为:

- `46` 个 Python 文件 (`app/` + `tests/` + `main.py`)
- `16` 个测试文件 (RAG / 工具 / 路由 / 编排器 / mock 全覆盖)
- 2 套编排引擎 (`orchestrator.py` 手写 + `orchestrator_langgraph.py`)
- 4 个 LLM Client 适配 (`openai_like` / `anthropic_client` / `gemini_client` + `factory`)
- 6 个 Embedding Provider 预设 (OpenAI / DashScope / Zhipu / SiliconFlow / Ollama / NewAPI)

### 4.3 目录结构

```
nexus-agent-backend/
├── main.py                  # uvicorn 启动入口
├── requirements.txt
├── pytest.ini
├── README.md
├── data/
│   └── chroma/              # ChromaDB 持久化
├── app/
│   ├── __init__.py          # FastAPI app 工厂
│   ├── config.py            # 环境变量 + Embedding Provider 预设
│   ├── routes.py            # /v1/agent/* 与 /v1/knowledge/* 路由
│   ├── schemas.py           # Pydantic 请求/响应模型
│   ├── security.py          # HMAC 签名校验依赖
│   ├── memory.py            # Redis 短期记忆
│   ├── prompts.py           # 系统/业务 prompt + 注入清洗
│   ├── tools.py             # Tool Schema + 反向调用 Java 的执行器
│   ├── langchain_tools.py   # LangChain 适配的工具封装
│   ├── orchestrator.py      # 手写 ReAct 编排
│   ├── orchestrator_langgraph.py  # LangGraph StateGraph 编排
│   ├── mock.py              # 无 OpenAI Key 时的确定性回复
│   ├── sse.py               # SSE 帧封装
│   ├── llm/                 # LLM Client 抽象与多家实现
│   ├── rag/                 # 向量库、Embedding、记忆 RAG、知识库 RAG
│   └── knowledge/           # 文档加载、切片、入库、QA
└── tests/                   # pytest 测试集
```

### 4.4 对外接口

所有路由都依赖 `verify_internal_signature` HMAC 校验，仅供 Java 内部调用。

#### Agent 调用

- `GET  /v1/agent/health`
  - 健康检查，返回当前 provider / model / engine
- `POST /v1/agent/invoke`
  - 同步调用，一次返回最终结果 (含 token usage)
- `POST /v1/agent/invoke/stream`
  - SSE 流式输出，事件类型: `meta / tool_call / tool_result / delta / usage / done / error`

#### 知识库 (Module B)

- `POST /v1/knowledge/ingest`
  - 同步入库: 加载 → 切分 → 向量化 → 写 ChromaDB
  - 失败返回 502 + 短原因，匹配 Java 侧 `agent_knowledge_document.error_message` 字段
- `POST /v1/knowledge/delete`
  - 删除 KB 全量 (docId=null) 或单文档
- `POST /v1/knowledge/query`
  - Top-K 相似度检索，永远返回 200，空结果交给 Java 渲染"我不知道"

### 4.5 编排器与工具

#### 双引擎可切换

通过 `ENGINE` 环境变量切换:

- `handcrafted` (默认): `orchestrator.py` 手写 ReAct loop
- `langgraph`: `orchestrator_langgraph.py` 基于 LangGraph StateGraph

两套引擎产出同样的 `Event` 序列，所以 SSE 线格式与引擎无关。

#### 工具 (反向调用 Java)

`TOOL_SCHEMAS` 当前注册了 7 个工具，全部由 `ToolExecutor` 通过 HMAC + Bearer 调到 `nexus-chat-backend` 的 `/internal/agent/*`:

- `get_recent_messages`
- `get_chat_profile`
- `get_user_profile`
- `get_message_by_id`
- `find_user_by_username`
- `list_my_chats`
- `find_direct_chat_with_user`

特定操作 (如 `CHAT_SUMMARY`, `TODO_EXTRACT`) 强制要求至少调用 `get_recent_messages`，避免幻觉。

### 4.6 记忆与 RAG

#### 短期记忆 (Redis)

- 默认 7 天 TTL
- 单会话保留最近 20 轮
- 上下文预算: `context_max_tokens = 12000`，最近 6 轮强制保留

#### 长期记忆 (Module A: memory RAG)

- 写入门槛: `memory_write_confidence_threshold = 0.75`
- 检索 Top-K = 3
- 入向量库后异步写出，主线程不阻塞 (`_pending_rag_writes` 强引用防 GC)

#### 知识库 (Module B: knowledge RAG)

- 默认切片 512 / overlap 64
- Top-K = 4
- 文档加载支持 PDF / DOCX / TXT / MD
- 入库失败上报 Java，状态写到 `knowledge_document` 表

### 4.7 Provider 与 BYOK 设计

Agent 端走 BYOK (Bring Your Own Key):

- 客户端在 Java `AgentProvidersController` 录入 provider/key (Java 加密落库)
- Java 调 Python 时通过 `X-Model-*` 头转发，Python 不持久化用户 Key
- 没有 Key 时回退到 `app.mock.mock_answer` 的确定性输出，本地开发不烧钱

Embedding 走独立 Provider 通道 (因为很多便宜的 chat 厂商不出 embedding):

- `EMBEDDING_PROVIDER` 一行切换 OpenAI / DashScope / Zhipu / SiliconFlow / Ollama / NewAPI
- `EMBEDDING_*` 显式变量永远优先于预设

### 4.8 配置与运行

环境变量 (节选，完整列表见 `agent开发文档/Agent 设计说明.md` §15):

- `SERVICE_PORT` (默认 8100)
- `INTERNAL_SIGNING_SECRET` / `JAVA_INTERNAL_TOKEN` / `JAVA_INTERNAL_BASE_URL` (与 Java 双向互信)
- `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `MODEL_NAME` (本地兜底用)
- `REDIS_URL` (默认 `redis://localhost:6379/2`)
- `CHROMA_PERSIST_DIR` (默认 `./data/chroma`)
- `EMBEDDING_PROVIDER` + `EMBEDDING_*` 或各 Provider 预设变量
- `ENGINE` (`handcrafted` | `langgraph`)
- `MEMORY_RAG_ENABLED` / `KNOWLEDGE_RAG_ENABLED` 等开关

启动:

```bash
cd nexus-agent-backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python main.py        # 默认 :8100
# 或: uvicorn app:app --port 8100
```

测试:

```bash
pytest tests/
```

### 4.9 当前观察

- Agent 端工程化程度高: 双编排引擎、SSE、HMAC、BYOK、Embedding 多供应商预设、RAG 双模块都已具备。
- 仓库未提供 `Dockerfile`，目前仅以 `python main.py` 形式部署，未纳入根目录 `docker-compose`。
- 测试覆盖比 Java/前端都好 (16 个测试文件，覆盖 RAG / 路由 / 编排 / mock)。
- LangGraph 引擎是备选项，生产路径仍是手写 ReAct。
- 设计文档完整放在仓库根 `agent开发文档/`，工程实现与文档一一对应。

## 5. `nexus-chat-frontend` 详解

### 5.1 项目定位

`nexus-chat-frontend` 是当前功能最完整、体验最丰富的客户端实现。

它同时服务两种运行形态:

- 浏览器 Web 版
- Electron 桌面版

它并不是简单共享 UI，而是显式处理了:

- Web/Electron 路由模式差异
- 原生窗口控制
- 托盘
- 通知
- 桌面端媒体权限
- GitHub Release 更新检查

### 4.2 技术栈与规模

- Vue 3
- Pinia
- Vue Router 4
- Element Plus
- Vite 5
- Electron 28
- Axios
- STOMP.js + SockJS
- Dexie / IndexedDB
- Three.js + `@pixiv/three-vrm`

源码规模大致为:

- `6` 个视图页面
- `25` 个组件
- `6` 个 Pinia Store
- `6` 个 Service
- `58` 个 `src + electron` 文件

### 4.3 启动入口和路由

关键入口:

- `src/main.js`
  - 挂载 Pinia、Router、Element Plus、i18n
- `src/App.vue`
  - 基础容器，启动时从 `localStorage` 恢复用户
- `src/router/index.js`
  - Electron 用 `HashHistory`
  - Web 用 `History`
  - 路由守卫基于本地 `token`

页面路由包括:

- `/login`
- `/setup`
- `/main`
- `/settings`
- `/profile`
- `/user/:id`

其中 `/setup` 看起来更像早期或辅助流程页面，因为当前真实登录流程主要仍由 `/login` 驱动。

### 4.4 主界面布局

`Main.vue` 是桌面/网页主工作台，结构非常清晰:

- `LeftPanel`
  - 聊天/联系人/群组三标签
  - 搜索
  - 设置入口
  - 新建群聊、添加联系人
  - Electron 下的窗口控制按钮
- `MiddlePanel`
  - 当前会话头部
  - 消息列表
  - 输入区
  - 音视频呼叫入口
- `RightPanel`
  - 私聊资料 / 群资料 / 群成员管理 / 搜索历史 / 置顶静音等
- `CompanionAvatar3D`
  - 右下角 3D 陪伴挂件
- `CompanionPanel`
  - 可拖拽、可缩放的陪伴操作窗
- 通话组件
  - `IncomingCallModal`
  - `OutgoingCallModal`
  - `CallView`
  - `CallEndModal`

整体上，这个前端的核心体验是“即时通讯主界面 + 陪伴组件 + 桌面壳能力”的组合。

### 4.5 状态管理设计

Pinia Store 划分比较成熟:

- `user.js`
  - 登录、登出、资料、背景、隐私、社交链接
- `chat.js`
  - 会话列表、当前会话、置顶、静音、未读数
- `message.js`
  - 按 chatId 管理消息
  - 临时消息替换
  - ACK 后合并
  - typing 状态
- `contact.js`
  - 联系人、好友申请、推荐用户、共同好友
- `call.js`
  - 通话状态机
  - 来电/去电/响铃/连接中/已接通/结束
  - 对接 WebRTC 与 WebSocket 信令
- `companion.js`
  - 角色、消息、记忆、成长值、状态、模型凭据、模型绑定

这一层说明桌面端已经不再是“简单 API 调用器”，而是完整的前端状态机。

### 4.6 Service 层设计

#### `api.js`

统一封装了后端 REST 接口:

- auth
- user
- chat
- message
- contact
- group
- sync
- companion
- file

它还提供了 `resolveFileUrl()`，把 `/uploads/...` 之类的相对地址转成后端完整地址。

#### `websocket.js`

这是前端实时能力的中心。

关键特点:

- 使用 SockJS + STOMP
- 连接后订阅统一频道 `/topic/user.{userId}.messages`
- 同步处理:
  - 聊天消息
  - ACK
  - 投递失败
  - typing
  - 已读
  - 群事件
  - 联系人事件
  - 通话信令
- 内置重连、心跳、页面可见性处理
- 维护 pending ACK 映射，支持“乐观消息 -> 服务端确认”流程

#### `db.js` + `offlineStore.js`

前端实现了本地离线缓存:

- IndexedDB 名称: `NexusChatDB`
- 表:
  - `messages`
  - `chats`
  - `contacts`
  - `syncMeta`
  - `pendingMessages`

这意味着它不是“断网即废”的薄前端，而是有明显的离线优先设计。

#### `syncService.js`

和后端 `SyncController` 配套，实现:

- 根据最后同步时间拉取增量
- 合并消息、聊天、联系人
- 刷新本地 Dexie
- 重连后刷出待发送消息

这套逻辑和微信式“先开 UI，再连 WS，再补 delta，再冲离线消息”的思路一致。

#### `webrtc.js`

负责:

- 音视频采集
- RTCPeerConnection
- SDP offer/answer
- ICE candidate
- 连接状态管理

和 `call.js` + `websocket.js` 配合完成桌面端通话能力。

### 4.7 Companion / 3D 能力

这是前端差异化最强的模块。

#### 入口

- `components/companion/CompanionAvatar3D.vue`
- `components/companion/CompanionPanel.vue`

#### 功能特点

- 支持加载:
  - VRM
  - GLB/GLTF
  - FBX
- 支持 `motions.json` 配置动作库
- 支持本地模型导入、动作导入、重命名、切换
- 支持 Mixamo 动作重定向到 VRM humanoid
- Companion Panel 中还集成:
  - 对话
  - 记忆管理
  - 成长值查看
  - 3D 模型设置
  - OpenAI-compatible 模型凭据与绑定

`public/models` 与 `public/motions` 存放默认资源，这些目录和后端 Companion 资源管理接口是联动的。

### 4.8 Electron 层实现

`electron/main.js` 和 `preload.js` 说明桌面版不是单纯 WebView 包装，而是显式补了原生能力:

- 窗口创建
  - macOS 使用 `hiddenInset`
  - Windows/Linux 使用无边框窗口
- 托盘
- 原生通知
- 始终置顶
- 外链打开
- 媒体权限申请
- GitHub Release 更新检查
- preload 桥接 `electronAPI`

在 Vue 端，`LeftPanel`、`Settings.vue`、`TitleBar.vue`、通话组件等都直接使用了这个桥。

### 4.9 环境配置与部署

环境文件:

- `.env.development`
  - 本地 `http://localhost:8080/api`
- `.env.production`
  - `/api` + 指定 WebSocket 地址
- `.env.electron`
  - Electron 标记和生产服务地址

`vite.config.js` 会根据是否 Electron 切换 `base` 和路由模式。

`Dockerfile` 则采用:

- Node 20 Alpine 构建前端
- Nginx Alpine 运行静态资源

根目录 `nginx/nginx.conf` 最终负责把 Web 资源和后端 API/WS 串起来。

### 4.10 当前观察

- 桌面/Web 端是当前三者中工程完成度最高的一端。
- Companion、通话、离线缓存、增量同步都已具备明确实现，不是占位目录。
- `Setup.vue` 仍带有较强的本地 mock 色彩，和当前真实鉴权主线不完全一致，像遗留/过渡页面。
- 工作区里已包含 `dist`、`dist-electron`、`node_modules`，说明当前仓库偏“开发现场快照”而非严格瘦身版源码。
- 项目级测试基本缺失。

## 5. `nexus-chat-app` 详解

### 5.1 项目定位

`nexus-chat-app` 是 Flutter 移动端客户端，承担:

- 登录/快速登录
- 消息与会话列表
- 联系人和好友申请
- 群聊
- 社区帖子
- 个人中心与设置
- 本地通知和应用内横幅通知

它不是前端的 WebView，也不是 Electron 共用 UI，而是一套完全独立的移动端代码。

### 5.2 技术栈与规模

- Flutter
- Dio
- STOMP Dart Client
- Hive
- Flutter Secure Storage
- Flutter Local Notifications
- Cached Network Image
- Image Picker / Cropper

源码规模大致为:

- `55` 个 `lib` 文件
- `25` 个页面文件
- `4` 个 Repository
- `7` 个远程 API Service
- `6` 组模型定义目录

需要特别指出:

- `pubspec.yaml` 声明了 `flutter_riverpod`、`riverpod_annotation`、`go_router`
- 但当前 `lib/` 内基本没有真正使用这些依赖
- 实际代码仍然以 `StatefulWidget + Repository + Navigator.push` 为主

也就是说，移动端的架构目标和现状之间还有一段距离。

### 5.3 代码分层

移动端采用比较标准的三层结构:

- `core/`
  - 配置、网络、通知、状态管理、安全存储
- `data/`
  - `datasources/remote`
  - `models`
  - `repositories`
- `presentation/`
  - 页面与通用组件

这是目前三个项目里最清晰的“分层式”目录组织。

### 5.4 应用启动流程

`main.dart` 做了几件关键事:

- 初始化 Hive
- 初始化 `UserStateManager`
- 初始化 `MessageService`
- 锁定竖屏
- 设置系统 UI 样式

`app.dart` 里:

- 设置 `MaterialApp`
- 注册全局 `navigatorKey`
- 配置亮暗主题
- 启动到 `SplashPage`

### 5.5 登录与导航流程

当前主导航是手写的，不是 `go_router`。

实际流程是:

- `SplashPage`
  - 检查是否已登录
  - 检查是否有“记忆账号”
- 如果会话有效
  - 进入 `MainNavigationPage`
- 如果无有效会话但有记忆账号
  - 进入 `QuickLoginPage`
- 否则
  - 进入 `LoginPage`

这套流程和 `SecureStorageService` 的“软登出 / 账号记忆 / 30 天会话有效期”设计是配套的。

### 5.6 主页面结构

`MainNavigationPage` 是移动端主容器，底部有四个 Tab:

- 消息
- 联系人
- 社区
- 我

对应页面:

- `MessagesPage`
- `ContactsPage`
- `CommunityPage`
- `ProfilePage`

另外还包含:

- `ChatPage`
- 群创建与群设置页面
- 发帖、帖子详情、收藏页
- 资料编辑、设置、关于页
- 用户详情页

### 5.7 网络层与后端对接

#### `ApiConfig`

这里定义了移动端的服务入口:

- Android 开发: `10.0.2.2`
- iOS 开发: `localhost`
- 生产: 固定公网 IP
- WebSocket: 走 `/ws-native`
- 当前代码里 `isProduction = true`

这意味着移动端默认是直接指向线上 IP，而不是构建时注入环境变量。

#### `DioClient`

能力包括:

- 统一 Base URL
- Bearer Token 注入
- 401 后软登出
- 开发态日志拦截

#### Repository 和 API Service

当前远程能力主要覆盖:

- `AuthRepository`
- `ChatRepository`
- `ContactRepository`
- `PostRepository`

其下对应:

- `auth_api_service.dart`
- `chat_api_service.dart`
- `contact_api_service.dart`
- `group_api_service.dart`
- `file_api_service.dart`
- `post_api_service.dart`
- `user_api_service.dart`

可以看出移动端目前重点接了:

- 认证
- 聊天
- 联系人
- 群组
- 文件
- 社区
- 用户统计
- App 更新检查

### 5.8 实时消息和通知

#### `WebSocketService`

移动端实时层和桌面端不同，走的是原生 STOMP:

- 连接 `/ws-native`
- 带 JWT 头连接
- 订阅 `/topic/user.{userId}.messages`
- 发送:
  - `/app/user.status`
  - `/app/user.heartbeat`
- 带重连和指数退避

#### `MessageService`

这是移动端的实时中枢:

- 监听 WebSocket 消息流
- 分发给:
  - 消息更新
  - 聊天列表更新
  - typing
  - 用户状态
  - 用户资料更新
- 根据前后台状态决定:
  - 系统通知
  - 应用内横幅
  - 只更新界面不提醒

#### 本地通知

`NotificationService` + `NotificationSettings` 提供:

- 本地消息通知
- 好友申请通知
- 静音聊天
- Hive 持久化通知偏好

### 5.9 本地状态与账号记忆

#### `SecureStorageService`

移动端登录体验的关键在这里:

- 存 Token
- 存用户 ID
- 存用户 JSON
- 记忆上次登录账号、昵称、头像、用户 ID
- 记录最后活跃时间
- 支持:
  - 软登出
  - 完全登出
  - 切换账号

#### `UserStateManager`

提供全局用户状态广播，并处理:

- 头像变化时的缓存清理
- 用户昵称/签名更新同步
- 各页面监听用户信息变化

### 5.10 页面能力概览

#### 消息

- `MessagesPage`
  - 拉聊天列表
  - 监听 WebSocket 刷新
  - 显示最近消息和未读
- `ChatPage`
  - 拉消息列表
  - 监听当前 chat 的消息更新
  - 发送消息
  - 标记已读
  - 群聊和私聊头部不同

注意:

- 当前移动端发消息仍主要走 REST `/api/messages`
- WebSocket 更偏“通知消息到了、刷新界面”
- 与桌面端“乐观发送 + ACK”相比，移动端实时交互模型更保守

#### 联系人

- `ContactsPage`
  - 按首字母分组
  - 右侧字母索引
  - 好友申请数量
  - WebSocket 驱动联系人刷新
- 还包含:
  - `AddContactPage`
  - `FriendRequestsPage`
  - `CreateGroupPage`

#### 社区

- `CommunityPage`
  - 推荐 / 热门 / 最新 三个流
  - 分页
  - 点赞/点踩/收藏
  - 预加载帖子图片
- 相关页面:
  - `CreatePostPage`
  - `PostDetailPage`
  - `BookmarksPage`

#### 个人中心

- `ProfilePage`
  - 头像、昵称、统计、功能卡片
- `ProfileEditPage`
  - 编辑资料
- `SettingsPage`
  - 切换账号、退出登录、关于
- `UserProfilePage`
  - 查看聊天对象资料

### 5.11 当前观察

- 移动端已经能覆盖 IM 主链路和社区链路，但 Companion、桌面级离线同步、通话 UI 等能力没有像 Web/Electron 那样完整展开。
- `README.md` 仍是 Flutter 默认模板，文档没有跟上实际代码。
- 自动化测试基本没有，只有默认 `widget_test.dart`。
- `Riverpod`、`go_router` 已写进依赖，但当前实现基本没真正用起来。
- `ApiConfig.isProduction = true` 且公网 IP 写死，发布/测试环境切换不够工程化。

## 6. 三个子项目的职责分工对比

| 能力 | backend | frontend | app |
|---|---|---|---|
| 用户认证 | 提供接口与 JWT | 完整接入 | 完整接入 |
| 私聊/群聊 | 核心实现 | 完整 UI | 完整 UI |
| 实时消息 | STOMP + Redis | 统一频道 + ACK + 离线缓存 | STOMP 通知流 |
| 联系人与好友申请 | 核心实现 | 完整 UI | 完整 UI |
| 社区帖子 | 核心实现 | 已接入 | 已接入 |
| 关注/粉丝 | 核心实现 | 已接入个人页 | 已接入个人页统计 |
| 文件上传 | 核心实现 | 已接入 | 已接入 |
| 音视频通话 | WebSocket 信令 | 已实现 WebRTC 前端 | 仅见协议模型，UI 主链路未成型 |
| 增量同步 | `SyncController` | 已接入 Dexie | 未见完整对等实现 |
| AI Companion | 核心实现 | 已深度接入 | 未见同等级 UI |
| 3D 资产管理 | 提供接口 | 已接入 | 无 |
| 桌面能力 | 无 | Electron 完整实现 | 无 |
| 本地通知 | 无 | Electron 通知 | Flutter 本地通知 |

## 7. 建议的阅读顺序

建议按下面顺序熟悉:

1. 先读根目录 `docker-compose.yml`、`docker-compose-app.yml`、`nginx/nginx.conf`
2. 再读 `nexus-chat-backend`
   - 先 `controller`
   - 再 `service`
   - 再 `config` 和 `model`
3. 再读 `nexus-chat-frontend`
   - 先 `src/views/Main.vue`
   - 再 `stores`
   - 再 `services/websocket.js`、`syncService.js`
   - 最后看 `electron/` 和 `companion/`
4. 最后读 `nexus-chat-app`
   - 先 `main.dart`、`app.dart`
   - 再 `presentation/pages`
   - 再 `core/network`、`core/storage`


