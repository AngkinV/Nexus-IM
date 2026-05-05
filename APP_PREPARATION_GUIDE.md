# Nexus Chat App 前期准备指南

## 开发环境: IntelliJ IDEA

---

## 一、开发环境搭建

### 1.1 安装 Flutter SDK

```bash
# macOS 使用 Homebrew 安装
brew install --cask flutter

# 或手动下载
# 访问 https://docs.flutter.dev/get-started/install/macos
# 下载 flutter_macos_arm64_3.19.x-stable.zip (M1/M2 Mac)

# 解压并配置环境变量
export PATH="$PATH:/path/to/flutter/bin"

# 验证安装
flutter --version
```

### 1.2 运行 Flutter Doctor

```bash
flutter doctor -v
```

**确保以下项目全部通过 ✓**

```
[✓] Flutter (Channel stable, 3.19.x)
[✓] Android toolchain - develop for Android devices
[✓] Xcode - develop for iOS and macOS
[✓] Chrome - develop for the web
[✓] IntelliJ IDEA Ultimate Edition (或 Community)
[✓] Connected device
```

### 1.3 Android 环境

#### 安装 Android Studio (仅用于 SDK 和模拟器)

```bash
brew install --cask android-studio
```

#### 配置 Android SDK

1. 打开 Android Studio → Settings → SDK Manager
2. 安装以下组件:
   - Android SDK Platform 34 (Android 14)
   - Android SDK Build-Tools 34.0.0
   - Android SDK Command-line Tools
   - Android Emulator
   - Android SDK Platform-Tools

3. 配置环境变量 (`~/.zshrc` 或 `~/.bash_profile`):

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
```

#### 创建 Android 模拟器

```bash
# 查看可用设备
avdmanager list device

# 创建模拟器 (通过 Android Studio 更方便)
# Android Studio → Device Manager → Create Virtual Device
# 推荐: Pixel 7 + API 34
```

#### 接受 Android 许可证

```bash
flutter doctor --android-licenses
# 输入 y 接受所有许可证
```

### 1.4 iOS 环境 (macOS 必需)

#### 安装 Xcode

```bash
# 从 App Store 安装 Xcode (需要 15.0+)

# 安装命令行工具
sudo xcode-select --install

# 接受许可证
sudo xcodebuild -license accept

# 安装 CocoaPods
sudo gem install cocoapods
# 或
brew install cocoapods
```

#### 配置 iOS 模拟器

```bash
# 打开模拟器
open -a Simulator

# 或通过 Xcode → Open Developer Tool → Simulator
```

### 1.5 IntelliJ IDEA 配置

#### 安装 Flutter 插件

1. 打开 IDEA → Settings (⌘ + ,)
2. Plugins → Marketplace
3. 搜索并安装:
   - **Flutter** (必装)
   - **Dart** (会自动安装)

4. 重启 IDEA

#### 配置 Flutter SDK 路径

1. Settings → Languages & Frameworks → Flutter
2. Flutter SDK path: `/path/to/flutter` (Homebrew 默认: `/opt/homebrew/Caskroom/flutter/3.19.x/flutter`)
3. 点击 Apply

#### 配置 Dart SDK

1. Settings → Languages & Frameworks → Dart
2. Dart SDK path: `<flutter_sdk>/bin/cache/dart-sdk`
3. 点击 Apply

---

## 二、项目创建

### 2.1 通过 IDEA 创建项目

1. File → New → Project
2. 选择 **Flutter** (左侧)
3. 配置:
   - Project name: `nexus_chat_app`
   - Project location: `/Users/anglv/Nexus Chat/nexus-chat-app`
   - Flutter SDK path: 自动填充
   - Project type: Application
   - Organization: `com.nexus` (包名前缀)
   - Android language: Kotlin
   - iOS language: Swift
   - Platforms: ✓ Android, ✓ iOS

4. 点击 Create

### 2.2 通过命令行创建项目

```bash
cd "/Users/anglv/Nexus Chat"

flutter create \
  --org com.nexus \
  --project-name nexus_chat_app \
  --platforms android,ios \
  nexus-chat-app

cd nexus-chat-app
```

### 2.3 项目结构验证

```
nexus-chat-app/
├── android/          ← Android 原生代码
├── ios/              ← iOS 原生代码
├── lib/              ← Flutter 代码
│   └── main.dart
├── test/             ← 测试代码
├── pubspec.yaml      ← 依赖配置
└── README.md
```

### 2.4 首次运行测试

```bash
# 查看可用设备
flutter devices

# 运行到 Android 模拟器
flutter run -d android

# 运行到 iOS 模拟器
flutter run -d ios

# 或在 IDEA 中点击 ▶️ Run 按钮
```

---

## 三、依赖安装

### 3.1 编辑 pubspec.yaml

```yaml
name: nexus_chat_app
description: Nexus Chat Mobile Application
publish_to: 'none'
version: 0.1.0+1

environment:
  sdk: '>=3.2.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.6

  # 状态管理
  flutter_riverpod: ^2.4.9
  riverpod_annotation: ^2.3.3

  # 路由
  go_router: ^13.1.0

  # 网络请求
  dio: ^5.4.0
  retrofit: ^4.0.3

  # WebSocket (STOMP)
  stomp_dart_client: ^1.0.0
  web_socket_channel: ^2.4.0

  # 本地存储
  hive: ^2.2.3
  hive_flutter: ^1.1.0
  sqflite: ^2.3.2
  path_provider: ^2.1.2
  flutter_secure_storage: ^9.0.0

  # 图片和缓存
  cached_network_image: ^3.3.1
  shimmer: ^3.0.0

  # 权限
  permission_handler: ^11.2.0

  # 媒体
  image_picker: ^1.0.7
  image_cropper: ^5.0.1

  # 工具
  intl: ^0.19.0
  uuid: ^4.3.3
  connectivity_plus: ^5.0.2

  # JSON
  json_annotation: ^4.8.1
  freezed_annotation: ^2.4.1

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^3.0.1
  build_runner: ^2.4.8
  retrofit_generator: ^8.0.6
  hive_generator: ^2.0.1
  json_serializable: ^6.7.1
  freezed: ^2.4.6
  riverpod_generator: ^2.3.9

flutter:
  uses-material-design: true
```

### 3.2 安装依赖

```bash
cd "/Users/anglv/Nexus Chat/nexus-chat-app"
flutter pub get
```

### 3.3 运行代码生成

```bash
flutter pub run build_runner build --delete-conflicting-outputs
```

---

## 四、账号准备

### 4.1 Firebase 账号 (推送通知)

| 步骤 | 操作 |
|------|------|
| 1 | 访问 [Firebase Console](https://console.firebase.google.com/) |
| 2 | 创建新项目: `nexus-chat` |
| 3 | 添加 Android 应用: 包名 `com.nexus.nexus_chat_app` |
| 4 | 添加 iOS 应用: Bundle ID `com.nexus.nexusChatApp` |
| 5 | 下载 `google-services.json` → 放入 `android/app/` |
| 6 | 下载 `GoogleService-Info.plist` → 放入 `ios/Runner/` |

### 4.2 Apple Developer 账号 (iOS发布)

| 项目 | 费用 | 用途 |
|------|------|------|
| Apple Developer Program | $99/年 | App Store 发布 |
| Provisioning Profile | - | iOS 真机调试 |
| Push Certificate | - | APNs 推送 |

**当前阶段可暂不注册，模拟器开发不需要**

### 4.3 Google Play 账号 (Android发布)

| 项目 | 费用 | 用途 |
|------|------|------|
| Google Play Console | $25 (一次性) | Google Play 发布 |

**当前阶段可暂不注册**

---

## 五、后端准备

### 5.1 确认后端服务可访问

```bash
# 检查后端是否运行
curl http://localhost:8080/api/users

# 检查 WebSocket
# 使用 wscat 测试
npm install -g wscat
wscat -c ws://localhost:8080/ws-native
```

### 5.2 配置 API 地址

创建 `lib/core/config/api_config.dart`:

```dart
class ApiConfig {
  // 开发环境
  static const String devBaseUrl = 'http://10.0.2.2:8080'; // Android 模拟器
  // static const String devBaseUrl = 'http://localhost:8080'; // iOS 模拟器

  // 生产环境
  static const String prodBaseUrl = 'https://chat.angkin.cn';

  // WebSocket
  static const String devWsUrl = 'ws://10.0.2.2:8080/ws-native';
  static const String prodWsUrl = 'wss://chat.angkin.cn/ws';

  // 当前环境
  static const bool isProduction = false;

  static String get baseUrl => isProduction ? prodBaseUrl : devBaseUrl;
  static String get wsUrl => isProduction ? prodWsUrl : devWsUrl;
}
```

**注意**: Android 模拟器访问本机服务需要使用 `10.0.2.2` 而不是 `localhost`

### 5.3 后端新增 API (稍后需要实现)

```java
// UserController.java 新增
@PostMapping("/{id}/push-token")
public ResponseEntity<?> registerPushToken(
    @PathVariable Long id,
    @RequestBody PushTokenRequest request) {
    // 保存 FCM/APNs token
}

@DeleteMapping("/{id}/push-token")
public ResponseEntity<?> unregisterPushToken(@PathVariable Long id) {
    // 删除 token
}
```

---

## 六、IDEA 开发技巧

### 6.1 常用快捷键

| 快捷键 | 功能 |
|--------|------|
| ⌘ + S | 保存并热重载 (Hot Reload) |
| ⇧ + F10 | 运行 |
| ⇧ + F9 | 调试运行 |
| ⌘ + ⇧ + F10 | 热重启 (Hot Restart) |
| ⌘ + B | 跳转到定义 |
| ⌘ + ⌥ + L | 格式化代码 |
| ⌘ + ⇧ + A | 查找操作 |

### 6.2 Flutter DevTools

```bash
# 在终端打开
flutter pub global activate devtools
flutter pub global run devtools
```

或在 IDEA 中: View → Tool Windows → Flutter Inspector

### 6.3 调试技巧

```dart
// 打印日志
debugPrint('message'); // 推荐，长文本不截断

// 条件断点
// 在断点处右键 → Edit breakpoint → Condition

// Widget 检查
// Flutter Inspector → Widget Tree
```

---

## 七、前期开发顺序

### 阶段 1: 基础框架 (1-2天)

- [ ] 项目创建并配置
- [ ] 目录结构搭建
- [ ] 路由配置 (go_router)
- [ ] 主题配置
- [ ] 网络层封装 (Dio + 拦截器)
- [ ] 本地存储初始化 (Hive)

### 阶段 2: 认证模块 (2-3天)

- [ ] 登录页面 UI
- [ ] 注册页面 UI
- [ ] 验证码页面 UI
- [ ] 认证 API 对接
- [ ] Token 存储和管理
- [ ] 自动登录逻辑

### 阶段 3: 主页框架 (1-2天)

- [ ] 底部导航栏
- [ ] 聊天列表页面
- [ ] 联系人列表页面
- [ ] 个人中心页面

### 阶段 4: WebSocket (2-3天)

- [ ] STOMP 客户端封装
- [ ] 连接/断线重连
- [ ] 心跳机制
- [ ] 消息订阅和处理

### 阶段 5: 聊天功能 (3-5天)

- [ ] 聊天详情页 UI
- [ ] 消息气泡组件
- [ ] 消息输入框
- [ ] 消息收发
- [ ] 本地消息存储

---

## 八、检查清单

### 环境检查

```bash
# 执行以下命令，确保全部 ✓
flutter doctor -v
```

| 检查项 | 状态 |
|--------|------|
| Flutter SDK 安装 | ⬜ |
| Android SDK 安装 | ⬜ |
| Android 模拟器可用 | ⬜ |
| Xcode 安装 | ⬜ |
| iOS 模拟器可用 | ⬜ |
| IDEA Flutter 插件 | ⬜ |
| 项目创建成功 | ⬜ |
| Hello World 运行成功 | ⬜ |

### 后端检查

| 检查项 | 状态 |
|--------|------|
| 后端服务启动 | ⬜ |
| API 可访问 | ⬜ |
| WebSocket 可连接 | ⬜ |
| 数据库正常 | ⬜ |

---

## 九、常见问题

### Q1: Android 模拟器无法连接后端

**原因**: `localhost` 在模拟器中指向模拟器自身

**解决**: 使用 `10.0.2.2` 代替 `localhost`

### Q2: iOS 模拟器无法连接 HTTP

**原因**: iOS 默认只允许 HTTPS

**解决**: 修改 `ios/Runner/Info.plist`:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsArbitraryLoads</key>
    <true/>
</dict>
```

### Q3: CocoaPods 安装失败

```bash
# 清理并重新安装
cd ios
rm -rf Pods Podfile.lock
pod install --repo-update
```

### Q4: Gradle 构建失败

```bash
# 清理并重新构建
cd android
./gradlew clean
cd ..
flutter clean
flutter pub get
flutter run
```

### Q5: IDEA 无法识别 Flutter

1. 确认插件已安装
2. File → Invalidate Caches → Invalidate and Restart
3. 重新配置 Flutter SDK 路径

---

*文档创建时间: 2026-02-07*