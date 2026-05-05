# Nexus Chat Backend

<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java">
  <img src="https://img.shields.io/badge/MySQL-8.0-blue" alt="MySQL">
  <img src="https://img.shields.io/badge/WebSocket-STOMP-purple" alt="WebSocket">
  <img src="https://img.shields.io/badge/License-MIT-green" alt="License">
</p>

Nexus Chat 是一个现代化的实时聊天应用后端服务，基于 Spring Boot 3.2.0 构建，支持私聊、群聊、文件传输等功能。

**前端项目**: [Nexus-Chat](https://github.com/AngkinV/Nexus-Chat)

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.2.0 | 核心框架 |
| Spring Data JPA | - | ORM 数据访问 |
| Spring Security | - | 安全认证 |
| Spring WebSocket | - | 实时通信 (STOMP) |
| MySQL | 8.0+ | 关系型数据库 |
| JWT (JJWT) | 0.12.3 | 令牌认证 |
| Lombok | - | 代码简化 |
| Maven | - | 构建工具 |

## 功能特性

### 核心功能
- **用户认证** - 基于 JWT 的安全认证系统
- **邮箱验证** - 注册时发送验证码验证
- **实时消息** - 基于 WebSocket (STOMP) 的实时通信
- **私聊** - 一对一即时聊天
- **群聊** - 多人群组聊天，支持群管理
- **消息已读** - 消息已读状态追踪
- **在线状态** - 实时用户在线/离线状态

### 高级功能
- **联系人管理** - 添加、删除、管理联系人
- **群组管理** - 创建群组、成员管理、权限控制
- **文件上传** - 支持大文件分片上传
- **用户资料** - 头像、昵称、个人简介、背景图
- **隐私设置** - 控制个人信息可见性
- **社交链接** - 管理社交媒体链接
- **活动记录** - 用户活动和朋友动态

## 前置要求

- Java 17 或更高版本
- MySQL 8.0 或更高版本
- Maven 3.6+

## 项目结构

```
nexus-chat-backend/
├── src/main/java/com/nexus/chat/
│   ├── NexusChatApplication.java    # 应用入口
│   ├── config/                      # 配置类
│   ├── controller/                  # REST API 控制器
│   ├── service/                     # 业务逻辑层
│   ├── repository/                  # 数据访问层
│   ├── model/                       # 实体类
│   ├── dto/                         # 数据传输对象
│   ├── security/                    # 安全相关
│   ├── websocket/                   # WebSocket 处理
│   └── exception/                   # 异常处理
├── src/main/resources/
│   ├── application.properties       # 应用配置
│   └── application-prod.properties  # 生产环境配置
├── uploads/                         # 文件上传目录
└── pom.xml                          # Maven 配置
```

## 安装配置

1. **创建 MySQL 数据库**：
```sql
CREATE DATABASE nexus_chat;
```

2. **配置数据库**（可选）：
如需修改，请编辑 `src/main/resources/application.properties`：
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/nexus_chat
spring.datasource.username=root
spring.datasource.password=root
```

3. **构建项目**：
```bash
mvn clean install
```

4. **运行应用**：
```bash
mvn spring-boot:run
```

服务器将在 `http://localhost:8080` 启动

## API 接口

### 用户认证
- `POST /api/auth/register` - 注册新用户
- `POST /api/auth/login` - 用户登录
- `POST /api/auth/logout` - 用户登出

### 用户管理
- `GET /api/users/{id}` - 根据 ID 获取用户
- `GET /api/users/username/{username}` - 根据用户名获取用户
- `GET /api/users` - 获取所有用户
- `PUT /api/users/{id}/profile` - 更新用户资料
- `PUT /api/users/{id}/status` - 更新在线状态

### 聊天管理
- `POST /api/chats/direct` - 创建私聊
- `POST /api/chats/group` - 创建群聊
- `GET /api/chats/user/{userId}` - 获取用户的聊天列表
- `GET /api/chats/{chatId}` - 获取聊天详情

### 消息管理
- `POST /api/messages` - 发送消息
- `GET /api/messages/chat/{chatId}` - 获取聊天消息（分页）
- `PUT /api/messages/{messageId}/read` - 标记消息为已读
- `PUT /api/messages/chat/{chatId}/read` - 标记所有聊天消息为已读

### 联系人管理
- `POST /api/contacts` - 添加联系人
- `DELETE /api/contacts` - 删除联系人
- `GET /api/contacts/user/{userId}` - 获取用户联系人列表

### 文件管理
- `POST /api/files/upload` - 上传文件
- `POST /api/files/upload/chunk` - 上传文件分片（用于大文件）

### 群组管理
- `GET /api/groups/{groupId}` - 获取群组详情
- `PUT /api/groups/{groupId}` - 更新群组信息
- `DELETE /api/groups/{groupId}` - 删除群组
- `GET /api/groups/{groupId}/members` - 获取群组成员
- `POST /api/groups/{groupId}/members` - 添加成员
- `DELETE /api/groups/{groupId}/members/{memberId}` - 移除成员
- `PUT /api/groups/{groupId}/admin/{memberId}` - 设置管理员
- `PUT /api/groups/{groupId}/owner` - 转移群主

## WebSocket 端点

通过 SockJS 连接到 WebSocket，地址为 `/ws`。

### 消息映射

| 目的地 | 说明 |
|--------|------|
| `/app/chat.sendMessage` | 发送聊天消息 |
| `/app/user.status` | 更新在线状态 |
| `/app/chat.typing` | 发送输入状态 |
| `/app/message.read` | 标记消息已读 |
| `/app/group.create` | 创建群组 |
| `/app/group.join` | 加入群组 |
| `/app/group.leave` | 离开群组 |

### 订阅主题

| 主题 | 说明 |
|------|------|
| `/topic/chat/{chatId}` | 聊天室消息 |
| `/topic/group/{groupId}` | 群组消息 |
| `/topic/users` | 用户状态更新 |
| `/queue/chats` | 聊天列表更新 |
| `/queue/contacts` | 联系人更新 |
| `/queue/groups` | 群组更新 |

## 数据库架构

### 核心表
- `users` - 用户账户
- `chats` - 聊天室（私聊/群聊）
- `chat_members` - 聊天参与者
- `messages` - 聊天消息
- `message_read_status` - 已读回执
- `contacts` - 用户联系人
- `contact_requests` - 联系人请求

### 辅助表
- `email_verification_codes` - 邮箱验证码
- `file_uploads` - 文件元数据
- `login_history` - 登录历史
- `user_activities` - 用户活动
- `user_privacy_settings` - 隐私设置
- `user_social_links` - 社交链接

## 开发

运行测试：
```bash
mvn test
```

生产环境打包：
```bash
mvn clean package
```

## 注意事项

- 生产环境中应更改默认的 JWT 密钥
- 文件上传存储在 `uploads/` 目录
- WebSocket 使用 SockJS 回退以提高浏览器兼容性
- 开发环境中已启用 CORS（生产环境需要配置）

## 许可证

本项目基于 [MIT](LICENSE) 许可证开源。
