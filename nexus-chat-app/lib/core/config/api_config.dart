import 'dart:io' show Platform;

/// API 配置
///
/// 环境切换说明：
/// - 本地开发：isProduction = false
/// - 生产部署：isProduction = true (部署脚本会自动切换)
class ApiConfig {
  ApiConfig._();

  /// 缓存当前平台是否为 Android
  static final bool _isAndroid = Platform.isAndroid;

  // ===== 开发环境 =====
  // Android 模拟器使用 10.0.2.2 访问本机
  // iOS 模拟器使用 localhost
  static const String devBaseUrlAndroid = 'http://10.0.2.2:8080';
  static const String devBaseUrlIOS = 'http://localhost:8080';

  // ===== 生产环境 (公网服务器) =====
  static const String prodBaseUrl = 'http://8.130.161.255:8080';

  // ===== WebSocket =====
  static const String devWsUrlAndroid = 'ws://10.0.2.2:8080/ws-native';
  static const String devWsUrlIOS = 'ws://localhost:8080/ws-native';
  static const String prodWsUrl = 'ws://8.130.161.255:8080/ws-native';

  // ===== 环境配置 =====
  // 本地开发设为 false，部署脚本会自动切换为 true
  static const bool isProduction = false;

  // 根据平台获取基础URL
  static String getBaseUrl({bool isAndroid = false}) {
    if (isProduction) {
      return prodBaseUrl;
    }
    return isAndroid ? devBaseUrlAndroid : devBaseUrlIOS;
  }

  // 获取WebSocket URL
  static String getWsUrl({bool isAndroid = false}) {
    if (isProduction) {
      return prodWsUrl;
    }
    return isAndroid ? devWsUrlAndroid : devWsUrlIOS;
  }

  // ===== API 端点 =====
  static const String authSendCode = '/api/auth/send-code';
  static const String authVerifyCode = '/api/auth/verify-code';
  static const String authRegister = '/api/auth/register';
  static const String authLogin = '/api/auth/login';
  static const String authLogout = '/api/auth/logout';

  static const String users = '/api/users';
  static const String chats = '/api/chats';
  static const String messages = '/api/messages';
  static const String contacts = '/api/contacts';
  static const String groups = '/api/groups';
  static const String files = '/api/files';

  // ===== 超时配置 =====
  static const int connectTimeout = 30000; // 30秒
  static const int receiveTimeout = 30000;
  static const int sendTimeout = 30000;

  /// 获取当前平台的基础 URL（自动检测平台）
  static String get currentBaseUrl => getBaseUrl(isAndroid: _isAndroid);

  /// 获取完整的资源 URL（头像、文件等）
  /// 自动处理相对路径和绝对路径，自动检测平台
  static String getFullUrl(String? path) {
    if (path == null || path.isEmpty) {
      return '';
    }
    // 已经是完整 URL
    if (path.startsWith('http://') || path.startsWith('https://')) {
      return path;
    }
    // 拼接基础 URL（自动检测平台）
    return '$currentBaseUrl$path';
  }
}
