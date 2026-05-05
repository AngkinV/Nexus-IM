# Nexus Chat 移动App端设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 项目名称 | Nexus Chat Mobile App |
| 文档版本 | v1.0 |
| 创建日期 | 2026-02-07 |
| 目标平台 | Android / iOS |
| 后端复用 | nexus-chat-backend (Spring Boot) |

---

## 一、项目概述

### 1.1 项目背景

Nexus Chat 是一个功能完整的实时通讯应用，目前已有：
- **后端服务**: Spring Boot 3.2.0 + MySQL + Redis + WebSocket (STOMP)
- **Web前端**: Vue 3 + Element Plus + Pinia
- **桌面端**: Electron (基于Web前端)

本文档规划移动App端的设计方案，复用现有后端服务，实现Android和iOS双平台适配。

### 1.2 项目目标

1. 开发一套代码同时适配 Android 和 iOS 平台
2. 完整复用现有后端 API 和 WebSocket 服务
3. 提供原生级的用户体验和性能
4. 支持移动端特有功能（推送通知、相机、相册、通讯录等）

### 1.3 核心功能清单

| 功能模块 | 功能点 | 优先级 |
|----------|--------|--------|
| 用户认证 | 注册、登录、邮箱验证码、JWT认证 | P0 |
| 即时通讯 | 私聊、群聊、消息收发、已读状态 | P0 |
| 联系人管理 | 添加联系人、好友申请、联系人列表 | P0 |
| 群组管理 | 创建群组、成员管理、群组设置 | P0 |
| 个人资料 | 资料编辑、头像上传、隐私设置 | P1 |
| 文件分享 | 图片/视频/音频/文件上传下载 | P1 |
| 音视频通话 | WebRTC语音/视频通话 | P2 |
| 推送通知 | 离线消息推送、新消息提醒 | P1 |
| 本地存储 | 消息缓存、离线访问 | P1 |

---

## 二、技术选型

### 2.1 跨平台框架对比

| 方案 | 优势 | 劣势 | 适用场景 |
|------|------|------|----------|
| **Flutter** | 高性能、UI一致性强、热重载、Google支持 | Dart语言学习成本、包体积较大 | 复杂UI、高性能要求 |
| **React Native** | JavaScript生态、社区成熟、代码复用 | 性能略低、桥接开销 | 快速迭代、Web团队 |
| **uni-app** | 国内生态好、多端适配、Vue语法 | 性能一般、复杂功能受限 | 国内市场、小程序 |

### 2.2 推荐方案: Flutter

**选择理由：**

1. **高性能**: Skia渲染引擎，接近原生性能，适合聊天应用的流畅滚动和动画
2. **UI一致性**: 自绘UI，Android/iOS外观完全一致
3. **WebSocket支持**: 原生支持WebSocket和STOMP协议
4. **WebRTC支持**: flutter_webrtc 库成熟，支持音视频通话
5. **社区生态**: 状态管理(Riverpod/Bloc)、网络请求(Dio)、本地存储(Hive/SQLite)等库完善
6. **热重载**: 开发效率高

### 2.3 技术栈规划

```
┌─────────────────────────────────────────────────────────────┐
│                     Nexus Chat Mobile App                    │
├─────────────────────────────────────────────────────────────┤
│  UI Layer                                                    │
│  ├── Flutter Widgets (Material Design 3)                    │
│  ├── 自定义组件库 (聊天气泡、头像、输入框等)                   │
│  └── 动画效果 (Hero、Lottie)                                 │
├─────────────────────────────────────────────────────────────┤
│  State Management                                            │
│  ├── Riverpod 2.0 (推荐) / Bloc                             │
│  └── Provider for DI                                         │
├─────────────────────────────────────────────────────────────┤
│  Business Logic                                              │
│  ├── 认证服务 (Auth Service)                                 │
│  ├── 聊天服务 (Chat Service)                                 │
│  ├── WebSocket管理器                                         │
│  ├── 联系人服务 (Contact Service)                            │
│  ├── 文件服务 (File Service)                                 │
│  └── 通话服务 (Call Service - WebRTC)                        │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                  │
│  ├── Repository Pattern                                      │
│  ├── Remote: Dio (HTTP) + stomp_dart_client (WebSocket)     │
│  └── Local: Hive (NoSQL) / SQLite + sqflite                 │
├─────────────────────────────────────────────────────────────┤
│  Platform Integration                                        │
│  ├── firebase_messaging (FCM/APNs 推送)                      │
│  ├── image_picker (相机/相册)                                │
│  ├── permission_handler (权限管理)                           │
│  ├── flutter_webrtc (音视频通话)                             │
│  ├── path_provider (文件存储)                                │
│  └── flutter_local_notifications (本地通知)                  │
└─────────────────────────────────────────────────────────────┘
```

### 2.4 核心依赖库

```yaml
dependencies:
  flutter:
    sdk: flutter

  # UI组件
  cupertino_icons: ^1.0.6
  flutter_svg: ^2.0.9
  cached_network_image: ^3.3.1
  shimmer: ^3.0.0
  lottie: ^3.0.0

  # 状态管理
  flutter_riverpod: ^2.4.9
  riverpod_annotation: ^2.3.3

  # 网络请求
  dio: ^5.4.0
  retrofit: ^4.0.3

  # WebSocket
  stomp_dart_client: ^1.0.0
  web_socket_channel: ^2.4.0

  # 本地存储
  hive: ^2.2.3
  hive_flutter: ^1.1.0
  sqflite: ^2.3.2
  path_provider: ^2.1.2

  # 安全存储
  flutter_secure_storage: ^9.0.0

  # 推送通知
  firebase_core: ^2.25.4
  firebase_messaging: ^14.7.15
  flutter_local_notifications: ^17.0.0

  # 权限管理
  permission_handler: ^11.2.0

  # 媒体相关
  image_picker: ^1.0.7
  image_cropper: ^5.0.1
  video_player: ^2.8.3
  audioplayers: ^6.0.0
  file_picker: ^6.1.1

  # WebRTC
  flutter_webrtc: ^0.9.47

  # 工具库
  intl: ^0.19.0
  timeago: ^3.6.1
  uuid: ^4.3.3
  connectivity_plus: ^5.0.2

  # 路由
  go_router: ^13.1.0

  # JSON序列化
  json_annotation: ^4.8.1
  freezed_annotation: ^2.4.1

dev_dependencies:
  flutter_test:
    sdk: flutter
  build_runner: ^2.4.8
  retrofit_generator: ^8.0.6
  hive_generator: ^2.0.1
  json_serializable: ^6.7.1
  freezed: ^2.4.6
  riverpod_generator: ^2.3.9
  flutter_lints: ^3.0.1
```

---

## 三、项目结构

### 3.1 目录结构

```
nexus-chat-app/
├── android/                          # Android原生代码
│   ├── app/
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── kotlin/
│   └── build.gradle
├── ios/                              # iOS原生代码
│   ├── Runner/
│   │   ├── Info.plist
│   │   └── AppDelegate.swift
│   └── Podfile
├── lib/                              # Flutter主代码
│   ├── main.dart                     # 应用入口
│   ├── app.dart                      # App配置
│   │
│   ├── core/                         # 核心模块
│   │   ├── config/                   # 配置
│   │   │   ├── app_config.dart       # 应用配置
│   │   │   ├── api_config.dart       # API配置
│   │   │   └── theme_config.dart     # 主题配置
│   │   ├── constants/                # 常量
│   │   │   ├── api_endpoints.dart    # API端点
│   │   │   ├── storage_keys.dart     # 存储键
│   │   │   └── app_constants.dart    # 应用常量
│   │   ├── errors/                   # 错误处理
│   │   │   ├── exceptions.dart       # 自定义异常
│   │   │   └── error_handler.dart    # 错误处理器
│   │   ├── network/                  # 网络层
│   │   │   ├── dio_client.dart       # Dio客户端
│   │   │   ├── api_interceptor.dart  # 请求拦截器
│   │   │   └── network_info.dart     # 网络状态
│   │   ├── storage/                  # 本地存储
│   │   │   ├── secure_storage.dart   # 安全存储
│   │   │   ├── hive_storage.dart     # Hive存储
│   │   │   └── cache_manager.dart    # 缓存管理
│   │   ├── utils/                    # 工具类
│   │   │   ├── validators.dart       # 验证器
│   │   │   ├── formatters.dart       # 格式化
│   │   │   ├── file_utils.dart       # 文件工具
│   │   │   └── date_utils.dart       # 日期工具
│   │   └── extensions/               # 扩展方法
│   │       ├── string_ext.dart
│   │       ├── context_ext.dart
│   │       └── datetime_ext.dart
│   │
│   ├── data/                         # 数据层
│   │   ├── models/                   # 数据模型
│   │   │   ├── user/
│   │   │   │   ├── user_model.dart
│   │   │   │   ├── user_profile_model.dart
│   │   │   │   └── privacy_settings_model.dart
│   │   │   ├── chat/
│   │   │   │   ├── chat_model.dart
│   │   │   │   ├── message_model.dart
│   │   │   │   └── group_model.dart
│   │   │   ├── contact/
│   │   │   │   ├── contact_model.dart
│   │   │   │   └── contact_request_model.dart
│   │   │   └── file/
│   │   │       └── file_upload_model.dart
│   │   ├── datasources/              # 数据源
│   │   │   ├── remote/               # 远程数据源
│   │   │   │   ├── auth_remote_datasource.dart
│   │   │   │   ├── user_remote_datasource.dart
│   │   │   │   ├── chat_remote_datasource.dart
│   │   │   │   ├── contact_remote_datasource.dart
│   │   │   │   └── file_remote_datasource.dart
│   │   │   └── local/                # 本地数据源
│   │   │       ├── user_local_datasource.dart
│   │   │       ├── chat_local_datasource.dart
│   │   │       └── message_local_datasource.dart
│   │   └── repositories/             # 仓库实现
│   │       ├── auth_repository_impl.dart
│   │       ├── user_repository_impl.dart
│   │       ├── chat_repository_impl.dart
│   │       ├── contact_repository_impl.dart
│   │       └── file_repository_impl.dart
│   │
│   ├── domain/                       # 业务层
│   │   ├── entities/                 # 业务实体
│   │   │   ├── user.dart
│   │   │   ├── chat.dart
│   │   │   ├── message.dart
│   │   │   ├── contact.dart
│   │   │   └── group.dart
│   │   ├── repositories/             # 仓库接口
│   │   │   ├── auth_repository.dart
│   │   │   ├── user_repository.dart
│   │   │   ├── chat_repository.dart
│   │   │   ├── contact_repository.dart
│   │   │   └── file_repository.dart
│   │   └── usecases/                 # 用例
│   │       ├── auth/
│   │       │   ├── login_usecase.dart
│   │       │   ├── register_usecase.dart
│   │       │   └── logout_usecase.dart
│   │       ├── chat/
│   │       │   ├── send_message_usecase.dart
│   │       │   ├── get_messages_usecase.dart
│   │       │   └── create_chat_usecase.dart
│   │       └── contact/
│   │           ├── add_contact_usecase.dart
│   │           └── get_contacts_usecase.dart
│   │
│   ├── presentation/                 # 表现层
│   │   ├── providers/                # Riverpod Providers
│   │   │   ├── auth_provider.dart
│   │   │   ├── user_provider.dart
│   │   │   ├── chat_provider.dart
│   │   │   ├── message_provider.dart
│   │   │   ├── contact_provider.dart
│   │   │   ├── websocket_provider.dart
│   │   │   └── call_provider.dart
│   │   ├── pages/                    # 页面
│   │   │   ├── splash/
│   │   │   │   └── splash_page.dart
│   │   │   ├── auth/
│   │   │   │   ├── login_page.dart
│   │   │   │   ├── register_page.dart
│   │   │   │   └── verify_code_page.dart
│   │   │   ├── home/
│   │   │   │   └── home_page.dart
│   │   │   ├── chat/
│   │   │   │   ├── chat_list_page.dart
│   │   │   │   ├── chat_detail_page.dart
│   │   │   │   └── group_info_page.dart
│   │   │   ├── contact/
│   │   │   │   ├── contact_list_page.dart
│   │   │   │   ├── add_contact_page.dart
│   │   │   │   └── contact_requests_page.dart
│   │   │   ├── profile/
│   │   │   │   ├── profile_page.dart
│   │   │   │   ├── edit_profile_page.dart
│   │   │   │   └── settings_page.dart
│   │   │   └── call/
│   │   │       ├── incoming_call_page.dart
│   │   │       ├── outgoing_call_page.dart
│   │   │       └── call_page.dart
│   │   └── widgets/                  # 组件
│   │       ├── common/
│   │       │   ├── app_bar.dart
│   │       │   ├── loading_widget.dart
│   │       │   ├── error_widget.dart
│   │       │   ├── empty_widget.dart
│   │       │   └── avatar_widget.dart
│   │       ├── chat/
│   │       │   ├── message_bubble.dart
│   │       │   ├── message_input.dart
│   │       │   ├── chat_list_item.dart
│   │       │   ├── typing_indicator.dart
│   │       │   └── file_message_widget.dart
│   │       ├── contact/
│   │       │   ├── contact_list_item.dart
│   │       │   └── contact_search_bar.dart
│   │       └── media/
│   │           ├── image_preview.dart
│   │           ├── video_player_widget.dart
│   │           └── audio_player_widget.dart
│   │
│   ├── services/                     # 服务层
│   │   ├── websocket/
│   │   │   ├── websocket_service.dart      # WebSocket服务
│   │   │   ├── stomp_client.dart           # STOMP客户端
│   │   │   └── message_handler.dart        # 消息处理
│   │   ├── notification/
│   │   │   ├── notification_service.dart   # 通知服务
│   │   │   ├── fcm_service.dart            # FCM推送
│   │   │   └── local_notification.dart     # 本地通知
│   │   ├── webrtc/
│   │   │   ├── webrtc_service.dart         # WebRTC服务
│   │   │   ├── call_manager.dart           # 通话管理
│   │   │   └── signaling_service.dart      # 信令服务
│   │   └── sync/
│   │       ├── sync_service.dart           # 数据同步
│   │       └── offline_queue.dart          # 离线队列
│   │
│   └── router/                       # 路由
│       ├── app_router.dart           # 路由配置
│       ├── route_names.dart          # 路由名称
│       └── route_guards.dart         # 路由守卫
│
├── assets/                           # 资源文件
│   ├── images/
│   ├── icons/
│   ├── fonts/
│   └── animations/                   # Lottie动画
│
├── l10n/                             # 国际化
│   ├── app_en.arb
│   └── app_zh.arb
│
├── test/                             # 测试
│   ├── unit/
│   ├── widget/
│   └── integration/
│
├── pubspec.yaml                      # 依赖配置
├── analysis_options.yaml             # 分析选项
└── README.md
```

---

## 四、核心模块设计

### 4.1 认证模块 (Authentication)

#### 4.1.1 功能需求

| 功能 | 描述 | API端点 |
|------|------|---------|
| 发送验证码 | 邮箱验证码 | POST /api/auth/send-code |
| 验证验证码 | 校验验证码 | POST /api/auth/verify-code |
| 注册 | 用户注册 | POST /api/auth/register |
| 登录 | 用户登录 | POST /api/auth/login |
| 登出 | 用户登出 | POST /api/auth/logout |

#### 4.1.2 Token管理

```dart
// JWT Token 存储方案
class TokenManager {
  // 使用 flutter_secure_storage 安全存储
  Future<void> saveToken(String token);
  Future<String?> getToken();
  Future<void> clearToken();

  // Token 自动刷新 (如果后端支持)
  Future<bool> isTokenValid();
  Future<void> refreshTokenIfNeeded();
}
```

#### 4.1.3 登录流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  启动App    │────▶│  检查Token  │────▶│  Token有效  │────▶ 进入主页
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                           ▼ Token无效/不存在
                    ┌─────────────┐
                    │  登录页面   │
                    └─────────────┘
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │  邮箱登录   │ │  注册流程   │ │  第三方登录 │
    └─────────────┘ └─────────────┘ └─────────────┘
```

### 4.2 WebSocket通信模块

#### 4.2.1 连接管理

```dart
class WebSocketService {
  // 连接配置
  static const String wsEndpoint = '/ws';
  static const int heartbeatInterval = 30000; // 30秒
  static const int reconnectDelay = 5000;     // 5秒
  static const int maxReconnectAttempts = 10;

  // 连接状态
  enum ConnectionState { disconnected, connecting, connected, reconnecting }

  // 核心方法
  Future<void> connect(String token);
  Future<void> disconnect();
  void subscribe(String destination, Function(dynamic) callback);
  void unsubscribe(String destination);
  void send(String destination, dynamic payload);
}
```

#### 4.2.2 消息订阅

```dart
// 需要订阅的频道
class WebSocketSubscriptions {
  // 聊天消息
  String chatTopic(int chatId) => '/topic/chat/$chatId';

  // 用户状态
  static const usersTopic = '/topic/users';

  // 群组消息
  String groupTopic(int groupId) => '/topic/group/$groupId';

  // 个人队列
  String userContactsQueue(int userId) => '/user/$userId/queue/contacts';
  String userGroupsQueue(int userId) => '/user/$userId/queue/groups';
  String userErrorsQueue(int userId) => '/user/$userId/queue/errors';
}
```

#### 4.2.3 心跳机制

```
┌──────────┐                           ┌──────────┐
│  Client  │                           │  Server  │
└────┬─────┘                           └────┬─────┘
     │                                      │
     │──── CONNECT (with JWT) ─────────────▶│
     │◀─── CONNECTED ──────────────────────│
     │                                      │
     │──── SUBSCRIBE /topic/chat/1 ────────▶│
     │                                      │
     ├─────────── 每30秒 ─────────────────────┤
     │                                      │
     │──── /app/user.heartbeat ────────────▶│ (刷新Redis TTL)
     │                                      │
     │◀─── MESSAGE (新消息) ───────────────│
     │                                      │
```

### 4.3 聊天模块 (Chat)

#### 4.3.1 消息类型

```dart
enum MessageType {
  text,    // 文本消息
  image,   // 图片消息
  video,   // 视频消息
  audio,   // 音频消息
  file,    // 文件消息
  emoji,   // 表情消息
}
```

#### 4.3.2 消息模型

```dart
@freezed
class Message with _$Message {
  factory Message({
    required int id,
    required int chatId,
    required int senderId,
    required String content,
    required MessageType messageType,
    String? fileUrl,
    String? fileName,
    int? fileSize,
    String? mimeType,
    required int sequenceNumber,
    String? clientMessageId,
    required DateTime createdAt,
    required bool isRead,
  }) = _Message;
}
```

#### 4.3.3 消息发送流程

```
┌──────────────┐
│  用户输入    │
└──────┬───────┘
       │
       ▼
┌──────────────┐     ┌──────────────┐
│ 生成clientId │────▶│ 乐观更新UI  │ (立即显示，状态:发送中)
└──────┬───────┘     └──────────────┘
       │
       ▼
┌──────────────────────────────┐
│ WebSocket发送                │
│ /app/chat.sendMessage        │
│ { chatId, senderId, content, │
│   messageType, clientMsgId } │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│ 等待 MESSAGE_ACK             │
│ 包含 sequenceNumber          │
└──────────────┬───────────────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌──────────────┐ ┌──────────────┐
│  ACK收到     │ │  超时/失败   │
│  更新状态:   │ │  更新状态:   │
│  已发送      │ │  发送失败    │
└──────────────┘ └──────────────┘
```

#### 4.3.4 消息去重

```dart
class MessageDeduplicator {
  final Set<String> _processedClientIds = {};
  final Set<int> _processedSequences = {};

  bool shouldProcess(Message message) {
    // 1. 检查 clientMessageId
    if (message.clientMessageId != null) {
      if (_processedClientIds.contains(message.clientMessageId)) {
        return false;
      }
      _processedClientIds.add(message.clientMessageId!);
    }

    // 2. 检查 sequenceNumber
    final key = '${message.chatId}_${message.sequenceNumber}';
    if (_processedSequences.contains(key.hashCode)) {
      return false;
    }
    _processedSequences.add(key.hashCode);

    return true;
  }
}
```

### 4.4 本地存储模块

#### 4.4.1 存储方案

| 数据类型 | 存储方案 | 说明 |
|----------|----------|------|
| JWT Token | flutter_secure_storage | 加密存储 |
| 用户信息 | Hive | 快速读写 |
| 聊天列表 | Hive | 缓存优化 |
| 消息记录 | SQLite | 结构化查询 |
| 文件缓存 | 文件系统 | 图片/视频缓存 |

#### 4.4.2 消息数据库设计

```sql
-- messages 表
CREATE TABLE messages (
    id INTEGER PRIMARY KEY,
    chat_id INTEGER NOT NULL,
    sender_id INTEGER NOT NULL,
    content TEXT,
    message_type TEXT NOT NULL,
    file_url TEXT,
    file_name TEXT,
    file_size INTEGER,
    mime_type TEXT,
    sequence_number INTEGER NOT NULL,
    client_message_id TEXT,
    created_at TEXT NOT NULL,
    is_read INTEGER DEFAULT 0,
    is_sent INTEGER DEFAULT 1,
    is_synced INTEGER DEFAULT 1
);

CREATE INDEX idx_messages_chat_id ON messages(chat_id);
CREATE INDEX idx_messages_sequence ON messages(chat_id, sequence_number);
CREATE UNIQUE INDEX idx_messages_client_id ON messages(client_message_id);

-- chats 表
CREATE TABLE chats (
    id INTEGER PRIMARY KEY,
    type TEXT NOT NULL,
    name TEXT,
    avatar_url TEXT,
    last_message TEXT,
    last_message_time TEXT,
    unread_count INTEGER DEFAULT 0,
    is_pinned INTEGER DEFAULT 0,
    updated_at TEXT
);
```

#### 4.4.3 离线消息队列

```dart
class OfflineMessageQueue {
  final Database _db;

  // 添加待发送消息
  Future<void> enqueue(Message message);

  // 获取待发送消息
  Future<List<Message>> getPendingMessages();

  // 移除已发送消息
  Future<void> dequeue(String clientMessageId);

  // 网络恢复时同步
  Future<void> syncPendingMessages();
}
```

### 4.5 文件上传模块

#### 4.5.1 上传策略

```dart
class FileUploadService {
  static const int chunkSize = 5 * 1024 * 1024; // 5MB
  static const int maxFileSize = 100 * 1024 * 1024; // 100MB

  Future<FileUploadResult> upload(File file, int uploaderId) async {
    if (file.lengthSync() <= chunkSize) {
      return _singleUpload(file, uploaderId);
    } else {
      return _chunkedUpload(file, uploaderId);
    }
  }

  // 单文件上传
  Future<FileUploadResult> _singleUpload(File file, int uploaderId);

  // 分片上传
  Future<FileUploadResult> _chunkedUpload(File file, int uploaderId);
}
```

#### 4.5.2 上传进度

```dart
class UploadProgress {
  final String fileId;
  final int totalBytes;
  final int uploadedBytes;
  final double progress; // 0.0 - 1.0
  final UploadStatus status;

  double get percentage => progress * 100;
}

enum UploadStatus {
  pending,
  uploading,
  completed,
  failed,
  cancelled,
}
```

### 4.6 推送通知模块

#### 4.6.1 推送架构

```
┌─────────────────────────────────────────────────────────────┐
│                        推送通知架构                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────┐    ┌─────────────┐    ┌─────────────────────┐  │
│  │  后端   │───▶│  FCM/APNs   │───▶│  移动设备           │  │
│  │ Server  │    │  推送服务   │    │  (Android/iOS)      │  │
│  └─────────┘    └─────────────┘    └─────────────────────┘  │
│       │                                      │               │
│       │                                      ▼               │
│       │                           ┌─────────────────────┐   │
│       │                           │  Notification       │   │
│       │                           │  Service            │   │
│       │                           │  - 显示通知         │   │
│       │                           │  - 角标管理         │   │
│       │                           │  - 通知分组         │   │
│       │                           └─────────────────────┘   │
│       │                                                      │
│       ▼                                                      │
│  需要后端新增接口:                                           │
│  POST /api/users/{id}/push-token                            │
│  { "token": "...", "platform": "android|ios" }              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

#### 4.6.2 通知类型

```dart
enum NotificationType {
  newMessage,        // 新消息
  contactRequest,    // 好友申请
  contactAccepted,   // 好友申请通过
  groupInvite,       // 群组邀请
  incomingCall,      // 来电
  missedCall,        // 未接来电
}
```

### 4.7 音视频通话模块 (WebRTC)

#### 4.7.1 通话流程

```
发起方(Caller)                     服务器                      接听方(Callee)
    │                               │                               │
    │── CALL_INVITE ───────────────▶│── CALL_INVITE ───────────────▶│
    │                               │                               │
    │                               │◀── CALL_ACCEPT ──────────────│
    │◀── CALL_ACCEPT ──────────────│                               │
    │                               │                               │
    │   [获取本地媒体流]             │                               │
    │   [创建PeerConnection]        │                               │
    │   [创建SDP Offer]             │                               │
    │                               │                               │
    │── CALL_OFFER (SDP) ──────────▶│── CALL_OFFER ────────────────▶│
    │                               │                               │
    │                               │         [获取本地媒体流]       │
    │                               │         [创建PeerConnection]  │
    │                               │         [设置Remote SDP]      │
    │                               │         [创建SDP Answer]      │
    │                               │                               │
    │                               │◀── CALL_ANSWER (SDP) ────────│
    │◀── CALL_ANSWER ──────────────│                               │
    │                               │                               │
    │   [设置Remote SDP]            │                               │
    │                               │                               │
    │◀─────────── ICE Candidates ─────────────────────────────────▶│
    │                               │                               │
    │════════════════════ 通话建立 ════════════════════════════════│
    │                               │                               │
    │── CALL_END ──────────────────▶│── CALL_END ──────────────────▶│
    │                               │                               │
```

#### 4.7.2 通话状态机

```dart
enum CallState {
  idle,         // 空闲
  calling,      // 呼出中
  ringing,      // 来电响铃
  connecting,   // WebRTC连接中
  connected,    // 通话中
  ended,        // 通话结束
}

enum CallEndReason {
  completed,    // 正常挂断
  rejected,     // 被拒绝
  cancelled,    // 被取消
  busy,         // 忙线
  timeout,      // 超时
  failed,       // 连接失败
  missed,       // 未接
}
```

---

## 五、UI/UX设计规范

### 5.1 设计原则

1. **一致性**: 保持与Web端视觉风格一致
2. **响应式**: 适配不同屏幕尺寸
3. **可访问性**: 支持无障碍功能
4. **性能优先**: 60fps流畅滚动

### 5.2 主题配置

```dart
class AppTheme {
  // 主色调
  static const Color primaryColor = Color(0xFF1890FF);
  static const Color secondaryColor = Color(0xFF52C41A);

  // 背景色
  static const Color backgroundColor = Color(0xFFF5F5F5);
  static const Color surfaceColor = Color(0xFFFFFFFF);

  // 文字色
  static const Color textPrimary = Color(0xFF1F1F1F);
  static const Color textSecondary = Color(0xFF8C8C8C);

  // 消息气泡
  static const Color bubbleSent = Color(0xFF1890FF);
  static const Color bubbleReceived = Color(0xFFFFFFFF);

  // 在线状态
  static const Color onlineColor = Color(0xFF52C41A);
  static const Color offlineColor = Color(0xFFBFBFBF);

  // 暗色主题
  static ThemeData darkTheme = ThemeData.dark().copyWith(...);

  // 亮色主题
  static ThemeData lightTheme = ThemeData.light().copyWith(...);
}
```

### 5.3 页面布局

#### 5.3.1 主页 (Home)

```
┌─────────────────────────────────────┐
│  ┌─────────────────────────────┐   │
│  │        顶部导航栏            │   │
│  │  [Logo]    搜索    [头像]   │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │                             │   │
│  │                             │   │
│  │        聊天列表             │   │
│  │                             │   │
│  │                             │   │
│  │                             │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │   消息   联系人   我的      │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

#### 5.3.2 聊天详情页

```
┌─────────────────────────────────────┐
│  ┌─────────────────────────────┐   │
│  │  [←]  用户名/群名    [...]  │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │                             │   │
│  │        消息列表             │   │
│  │        (下拉加载更多)        │   │
│  │                             │   │
│  │   ┌───────────────────┐    │   │
│  │   │  对方消息气泡      │    │   │
│  │   └───────────────────┘    │   │
│  │                             │   │
│  │        ┌───────────────┐   │   │
│  │        │  我的消息气泡  │   │   │
│  │        └───────────────┘   │   │
│  │                             │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  [+]  输入消息...    [发送] │   │
│  └─────────────────────────────┘   │
└─────────────────────────────────────┘
```

### 5.4 组件规范

| 组件 | 尺寸 | 说明 |
|------|------|------|
| 头像(小) | 40x40 | 聊天列表 |
| 头像(中) | 56x56 | 联系人详情 |
| 头像(大) | 80x80 | 个人资料页 |
| 消息气泡 | max 70% 宽度 | 自适应内容 |
| 底部导航 | 56高度 | Material规范 |
| 列表项 | 72高度 | 标准列表项 |

---

## 六、后端适配需求

### 6.1 需要新增的API

| API | 方法 | 描述 | 优先级 |
|-----|------|------|--------|
| `/api/users/{id}/push-token` | POST | 注册推送Token | P0 |
| `/api/users/{id}/push-token` | DELETE | 注销推送Token | P0 |
| `/api/users/{id}/devices` | GET | 获取登录设备列表 | P1 |
| `/api/users/{id}/devices/{deviceId}` | DELETE | 踢出设备 | P1 |

### 6.2 推送Token注册

```json
// POST /api/users/{id}/push-token
{
  "token": "fcm_or_apns_token",
  "platform": "android" | "ios",
  "deviceId": "unique_device_id",
  "deviceName": "iPhone 15 Pro"
}
```

### 6.3 后端推送集成

需要后端集成 Firebase Admin SDK 或 APNs 进行推送:

```java
// 后端需要新增的服务
@Service
public class PushNotificationService {
    // 发送新消息通知
    void sendNewMessageNotification(Long userId, Message message);

    // 发送好友申请通知
    void sendContactRequestNotification(Long userId, ContactRequest request);

    // 发送来电通知
    void sendIncomingCallNotification(Long userId, CallInvite call);
}
```

### 6.4 WebSocket适配

后端WebSocket配置需要增加对移动端的CORS支持:

```java
// WebSocketConfig.java 需要修改
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws")
        .setAllowedOrigins(
            "http://localhost:*",
            "https://chat.angkin.cn",
            "app://*",           // 已有 - 桌面Electron
            "capacitor://*",     // 新增 - Capacitor (如使用)
            "ionic://*"          // 新增 - Ionic (如使用)
        )
        .withSockJS();

    // 原生WebSocket端点 (Flutter推荐使用)
    registry.addEndpoint("/ws-native")
        .setAllowedOrigins("*");
}
```

---

## 七、安全设计

### 7.1 数据安全

| 安全措施 | 实现方案 |
|----------|----------|
| Token存储 | flutter_secure_storage (Keychain/Keystore) |
| 网络传输 | HTTPS + Certificate Pinning |
| 本地数据 | SQLCipher 加密数据库 (可选) |
| 敏感信息 | 不在日志中打印Token等敏感信息 |

### 7.2 权限管理

```dart
// Android权限 (android/app/src/main/AndroidManifest.xml)
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>

// iOS权限 (ios/Runner/Info.plist)
<key>NSCameraUsageDescription</key>
<string>需要相机权限进行视频通话和拍照</string>
<key>NSMicrophoneUsageDescription</key>
<string>需要麦克风权限进行语音和视频通话</string>
<key>NSPhotoLibraryUsageDescription</key>
<string>需要相册权限发送图片</string>
```

### 7.3 Certificate Pinning

```dart
class SecureHttpClient {
  Dio createSecureDio() {
    final dio = Dio();

    // 证书固定 (生产环境)
    (dio.httpClientAdapter as IOHttpClientAdapter).createHttpClient = () {
      final client = HttpClient();
      client.badCertificateCallback = (cert, host, port) {
        // 验证证书指纹
        return _validateCertificate(cert);
      };
      return client;
    };

    return dio;
  }
}
```

---

## 八、性能优化

### 8.1 列表优化

```dart
// 使用 ListView.builder 虚拟化
ListView.builder(
  itemCount: messages.length,
  itemBuilder: (context, index) {
    return MessageBubble(message: messages[index]);
  },
  // 缓存范围
  cacheExtent: 500,
);

// 图片懒加载
CachedNetworkImage(
  imageUrl: message.fileUrl,
  placeholder: (context, url) => Shimmer(...),
  errorWidget: (context, url, error) => Icon(Icons.error),
);
```

### 8.2 状态管理优化

```dart
// 使用 Riverpod 选择性重建
final messageProvider = StateNotifierProvider<MessageNotifier, List<Message>>((ref) {
  return MessageNotifier();
});

// 只监听特定聊天的消息
final chatMessagesProvider = Provider.family<List<Message>, int>((ref, chatId) {
  return ref.watch(messageProvider).where((m) => m.chatId == chatId).toList();
});
```

### 8.3 网络优化

- 消息分页加载 (每页20-50条)
- 图片压缩后上传
- WebSocket心跳保活
- 断线自动重连
- 请求去重和节流

---

## 九、测试策略

### 9.1 测试类型

| 测试类型 | 覆盖率目标 | 工具 |
|----------|------------|------|
| 单元测试 | 80% | flutter_test |
| Widget测试 | 60% | flutter_test |
| 集成测试 | 核心流程 | integration_test |
| E2E测试 | 关键场景 | patrol |

### 9.2 核心测试场景

1. **认证流程**: 注册 → 验证码 → 登录 → 登出
2. **消息收发**: 发送消息 → 接收消息 → 已读状态
3. **离线支持**: 断网发送 → 恢复网络 → 自动同步
4. **文件上传**: 选择文件 → 上传进度 → 发送成功
5. **WebSocket**: 连接 → 断线重连 → 消息不丢失

---

## 十、发布规划

### 10.1 版本规划

| 版本 | 功能范围 | 目标 |
|------|----------|------|
| v0.1.0 (Alpha) | 认证 + 基础聊天 | 内部测试 |
| v0.2.0 (Alpha) | 联系人 + 群组 | 内部测试 |
| v0.3.0 (Beta) | 文件分享 + 推送 | 公开测试 |
| v0.4.0 (Beta) | 音视频通话 | 公开测试 |
| v1.0.0 (Release) | 全功能 | 正式发布 |

### 10.2 应用商店

| 平台 | 商店 | 要求 |
|------|------|------|
| Android | Google Play | 开发者账号 $25 |
| Android | 华为/小米/OPPO等 | 各商店开发者账号 |
| iOS | App Store | Apple Developer $99/年 |

### 10.3 CI/CD

```yaml
# .github/workflows/flutter.yml
name: Flutter CI/CD

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: subosito/flutter-action@v2
        with:
          flutter-version: '3.19.0'
      - run: flutter pub get
      - run: flutter test
      - run: flutter build apk --release
      - run: flutter build ios --release --no-codesign
```

---

## 十一、项目初始化命令

```bash
# 1. 创建Flutter项目
flutter create nexus_chat_app
cd nexus_chat_app

# 2. 配置包名
# Android: android/app/build.gradle
# applicationId "com.nexus.chat"
# iOS: ios/Runner.xcodeproj - Bundle Identifier

# 3. 添加依赖
flutter pub add dio flutter_riverpod go_router \
  hive hive_flutter sqflite path_provider \
  flutter_secure_storage cached_network_image \
  stomp_dart_client web_socket_channel \
  firebase_core firebase_messaging \
  flutter_local_notifications permission_handler \
  image_picker flutter_webrtc \
  freezed_annotation json_annotation

flutter pub add --dev build_runner freezed json_serializable \
  riverpod_generator hive_generator

# 4. 运行代码生成
flutter pub run build_runner build --delete-conflicting-outputs

# 5. 运行项目
flutter run
```

---

## 十二、总结

本设计文档详细规划了 Nexus Chat 移动App的技术架构和实现方案：

### 技术选型
- **框架**: Flutter 3.x
- **状态管理**: Riverpod 2.0
- **网络**: Dio + STOMP WebSocket
- **存储**: Hive + SQLite
- **推送**: FCM/APNs

### 核心特性
- 完整复用现有后端API
- 实时消息收发 (WebSocket)
- 离线消息支持
- 文件上传/下载
- 音视频通话 (WebRTC)
- 推送通知

### 后端需求
- 新增推送Token注册API
- WebSocket CORS配置更新
- 推送服务集成 (FCM/APNs)

---

*文档创建时间: 2026-02-07*
*待审核后进入开发阶段*