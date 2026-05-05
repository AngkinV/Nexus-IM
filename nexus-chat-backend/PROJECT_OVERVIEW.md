**项目说明书**
本文档为 Nexus Chat Backend 的说明书，面向开发、测试、运维与交付场景。

**一、项目概览**
Nexus Chat 是一个现代化实时聊天后端，提供私聊/群聊、联系人、社区帖子、文件上传、版本更新、以及陪伴式 AI（Companion）能力。后端采用 Spring Boot 3.2.x + Java 17 + MySQL + Redis + WebSocket(STOMP) 体系。

**二、快速开始**
1. 准备环境：Java 17、Maven 3.6+、MySQL 8.x、Redis。
2. 创建数据库：
```sql
CREATE DATABASE nexus_chat;
```
3. 配置数据库与关键参数：编辑 `src/main/resources/application.properties`。
4. 构建：
```bash
mvn clean package
```
5. 运行：
```bash
mvn spring-boot:run
```
6. 服务默认端口：`8080`。

**三、目录结构**
```
.
├── src/main/java/com/nexus/chat
│   ├── config/           Spring 配置
│   ├── controller/       REST API 控制器
│   ├── websocket/        WebSocket 入口
│   ├── service/          业务逻辑
│   ├── repository/       JPA 仓库
│   ├── model/            实体模型
│   ├── dto/              数据传输对象
│   ├── security/         JWT 安全
│   ├── exception/        异常处理
│   └── util/             工具
├── src/main/resources
│   ├── application*.properties
│   ├── schema.sql
│   ├── migrations/
│   ├── i18n/
│   └── logback-spring.xml
├── uploads/
├── logs/
└── Dockerfile
```

**四、核心架构说明**
- 分层结构：Controller -> Service -> Repository -> Model。
- 通信方式：REST + WebSocket（实时消息）并行。
- Redis 角色：在线状态、离线队列、消息序列号、限流、缓存。

**五、鉴权与安全**
- JWT 签发与验证：`src/main/java/com/nexus/chat/security/JwtTokenProvider.java`。
- REST 认证过滤：`src/main/java/com/nexus/chat/security/JwtAuthenticationFilter.java`。
- WebSocket 认证拦截：`src/main/java/com/nexus/chat/config/WebSocketAuthChannelInterceptor.java`。
- CORS 与权限：`src/main/java/com/nexus/chat/config/SecurityConfig.java`。

**六、WebSocket 使用说明**
- 端点：`/ws`（SockJS）与 `/ws-native`。
- 统一用户通道：`/topic/user.{userId}.messages`。
- 消息类型：`src/main/java/com/nexus/chat/dto/WebSocketMessage.java`。
- 关键特性：ACK、离线队列、消息序列号、输入状态、跨实例转发。

**七、消息流说明**
1. 客户端发送消息到 `/app/chat.sendMessage` 或 `POST /api/messages`。
2. 服务端生成序列号（Redis INCR）。
3. 服务端返回 ACK（MESSAGE_ACK）。
4. 在线用户实时投递，离线用户入 Redis 队列。

**八、REST API 模块概览**
- 认证：`AuthController`.
- 用户：`UserController`.
- 聊天：`ChatController`.
- 群组：`GroupController`.
- 消息：`MessageController`.
- 联系人：`ContactController`.
- 帖子：`PostController`.
- 关注：`FollowController`.
- 版本：`AppVersionController`.
- 同步：`SyncController`.
- Companion：`CompanionController`.
- 资产：`CompanionAssetController`.

**九、数据库与实体模型**
- 基线结构：`src/main/resources/schema.sql`。
- Companion 迁移：`src/main/resources/migrations/2026_03_13_companion_mvp1.sql`。
- 核心实体：
  - 账号：`User`, `UserPrivacySettings`, `UserSocialLink`, `UserSecuritySettings`.
  - 聊天：`Chat`, `ChatMember`, `Message`, `MessageReadStatus`.
  - 联系人：`Contact`, `ContactRequest`.
  - 文件：`FileUpload`.
  - 社区：`Post`, `PostComment`, `PostVote`, `PostBookmark`, `CommentLike`, `UserFollow`.
  - Companion：`CompanionRole`, `CompanionGrowth`, `CompanionMemory`, `CompanionConversation`, `CompanionMessage`, `CompanionModelBinding`, `ModelCredential`, `CompanionStatus`.

**十、Redis 设计说明**
- 在线状态：`presence:*`、`presence:sessions:*`。
- 离线队列：`offline:{userId}`。
- 消息序列号：`chat:seq:{chatId}`。
- 限流：`ratelimit:*`。
- 缓存：用户资料、联系人、聊天列表等，集中在 `RedisCacheService`.

**十一、文件上传与清理**
- 上传：`/api/files/upload` 与 `/api/files/upload/chunk`。
- 秒传：基于 MD5 哈希。
- 过期清理：`FileCleanupService`，每天凌晨 3 点自动执行。
- 静态访问：`/uploads/**` 由 `WebMvcConfig` 暴露。

**十二、Companion 子系统说明**
- 角色与成长：`CompanionService`.
- 模型调用：`CompanionModelService`（OpenAI-compatible）。
- 密钥加密：`CompanionCryptoService`（AES-GCM）。
- 兜底回复：`CompanionFallbackService`.
- 资产管理：`CompanionAssetController`（VRM/FBX + motions.json）。

**十三、配置说明**
- 本地：`src/main/resources/application.properties`.
- 生产：`src/main/resources/application-prod.properties`.
- Docker：`src/main/resources/application-docker.properties`.
- 建议：生产环境使用环境变量覆盖敏感项（JWT、邮箱密码、API Key 密钥）。

**十四、日志与国际化**
- 日志：`src/main/resources/logback-spring.xml`.
- 国际化：`src/main/resources/i18n/messages*.properties`.
- 业务异常：`BusinessException` + `GlobalExceptionHandler`.

**十五、Docker 运行**
- Dockerfile 为多阶段构建，运行镜像基于 `eclipse-temurin:17-jre`.
- 默认暴露端口 `8080`，上传目录 `/app/uploads`.

**十六、注意事项**
- WebSocket 统一用户通道设计要求客户端订阅 `/topic/user.{userId}.messages`.
- Redis 故障时限流策略为“放行”，避免完全不可用。
- 配置文件包含明文密钥，生产环境必须替换。

**十七、关键文件索引**
- 入口：`src/main/java/com/nexus/chat/NexusChatApplication.java`.
- WebSocket：`src/main/java/com/nexus/chat/websocket/WebSocketController.java`.
- 安全：`src/main/java/com/nexus/chat/security/*`.
- 业务服务：`src/main/java/com/nexus/chat/service/*`.
- 数据模型：`src/main/java/com/nexus/chat/model/*`.
- 数据访问：`src/main/java/com/nexus/chat/repository/*`.
- 配置资源：`src/main/resources/*`.
