import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 安全存储服务
class SecureStorageService {
  static SecureStorageService? _instance;
  late final FlutterSecureStorage _storage;

  // 存储键
  static const String _tokenKey = 'auth_token';
  static const String _userIdKey = 'user_id';
  static const String _userDataKey = 'user_data';

  // 登录记忆相关存储键
  static const String _lastActiveKey = 'last_active_time';
  static const String _lastAccountKey = 'last_account';
  static const String _lastAvatarKey = 'last_avatar';
  static const String _lastNicknameKey = 'last_nickname';
  static const String _lastUserIdKey = 'last_user_id';

  // 会话有效期（30天，单位：毫秒）
  static const int sessionValidDuration = 30 * 24 * 60 * 60 * 1000;

  SecureStorageService._() {
    _storage = const FlutterSecureStorage(
      aOptions: AndroidOptions(
        encryptedSharedPreferences: true,
      ),
      iOptions: IOSOptions(
        accessibility: KeychainAccessibility.first_unlock_this_device,
      ),
    );
  }

  factory SecureStorageService() {
    _instance ??= SecureStorageService._();
    return _instance!;
  }

  // ===== Token 管理 =====

  /// 保存 Token
  Future<void> saveToken(String token) async {
    await _storage.write(key: _tokenKey, value: token);
    // 保存token时同时更新最后活跃时间
    await updateLastActiveTime();
  }

  /// 获取 Token
  Future<String?> getToken() async {
    return await _storage.read(key: _tokenKey);
  }

  /// 清除 Token
  Future<void> clearToken() async {
    await _storage.delete(key: _tokenKey);
  }

  /// 检查是否有 Token
  Future<bool> hasToken() async {
    final token = await getToken();
    return token != null && token.isNotEmpty;
  }

  // ===== 用户ID 管理 =====

  /// 保存用户ID
  Future<void> saveUserId(int userId) async {
    await _storage.write(key: _userIdKey, value: userId.toString());
  }

  /// 获取用户ID
  Future<int?> getUserId() async {
    final idStr = await _storage.read(key: _userIdKey);
    if (idStr != null) {
      return int.tryParse(idStr);
    }
    return null;
  }

  /// 清除用户ID
  Future<void> clearUserId() async {
    await _storage.delete(key: _userIdKey);
  }

  // ===== 用户数据 管理 =====

  /// 保存用户数据 (JSON)
  Future<void> saveUserData(Map<String, dynamic> userData) async {
    final jsonStr = jsonEncode(userData);
    await _storage.write(key: _userDataKey, value: jsonStr);

    // 同时保存账号记忆信息
    await _saveAccountMemory(userData);
  }

  /// 获取用户数据
  Future<Map<String, dynamic>?> getUserData() async {
    final jsonStr = await _storage.read(key: _userDataKey);
    if (jsonStr != null) {
      return jsonDecode(jsonStr) as Map<String, dynamic>;
    }
    return null;
  }

  /// 清除用户数据
  Future<void> clearUserData() async {
    await _storage.delete(key: _userDataKey);
  }

  // ===== 账号记忆管理 =====

  /// 保存账号记忆信息（从用户数据中提取）
  Future<void> _saveAccountMemory(Map<String, dynamic> userData) async {
    final email = userData['email'] as String?;
    final username = userData['username'] as String?;
    final nickname = userData['nickname'] as String?;
    final avatarUrl = userData['avatarUrl'] as String?;
    final userId = userData['id'] ?? userData['userId'];

    // 保存账号（优先邮箱，其次用户名）
    final account = email ?? username ?? '';
    if (account.isNotEmpty) {
      await _storage.write(key: _lastAccountKey, value: account);
    }

    // 保存昵称
    if (nickname != null && nickname.isNotEmpty) {
      await _storage.write(key: _lastNicknameKey, value: nickname);
    } else if (username != null && username.isNotEmpty) {
      await _storage.write(key: _lastNicknameKey, value: username);
    }

    // 保存头像
    if (avatarUrl != null && avatarUrl.isNotEmpty) {
      await _storage.write(key: _lastAvatarKey, value: avatarUrl);
    }

    // 保存用户ID
    if (userId != null) {
      await _storage.write(key: _lastUserIdKey, value: userId.toString());
    }
  }

  /// 获取上次登录的账号
  Future<String?> getLastAccount() async {
    return await _storage.read(key: _lastAccountKey);
  }

  /// 获取上次登录的昵称
  Future<String?> getLastNickname() async {
    return await _storage.read(key: _lastNicknameKey);
  }

  /// 获取上次登录的头像
  Future<String?> getLastAvatar() async {
    return await _storage.read(key: _lastAvatarKey);
  }

  /// 获取上次登录的用户ID
  Future<int?> getLastUserId() async {
    final idStr = await _storage.read(key: _lastUserIdKey);
    if (idStr != null) {
      return int.tryParse(idStr);
    }
    return null;
  }

  /// 检查是否有记忆的账号
  Future<bool> hasRememberedAccount() async {
    final account = await getLastAccount();
    return account != null && account.isNotEmpty;
  }

  /// 清除账号记忆（完全登出时调用）
  Future<void> clearAccountMemory() async {
    await _storage.delete(key: _lastAccountKey);
    await _storage.delete(key: _lastNicknameKey);
    await _storage.delete(key: _lastAvatarKey);
    await _storage.delete(key: _lastUserIdKey);
  }

  // ===== 活跃时间管理 =====

  /// 更新最后活跃时间
  Future<void> updateLastActiveTime() async {
    final now = DateTime.now().millisecondsSinceEpoch.toString();
    await _storage.write(key: _lastActiveKey, value: now);
  }

  /// 获取最后活跃时间
  Future<DateTime?> getLastActiveTime() async {
    final timeStr = await _storage.read(key: _lastActiveKey);
    if (timeStr != null) {
      final timestamp = int.tryParse(timeStr);
      if (timestamp != null) {
        return DateTime.fromMillisecondsSinceEpoch(timestamp);
      }
    }
    return null;
  }

  /// 检查会话是否在有效期内（30天）
  Future<bool> isSessionValid() async {
    final lastActive = await getLastActiveTime();
    if (lastActive == null) {
      return false;
    }

    final now = DateTime.now();
    final diff = now.difference(lastActive).inMilliseconds;
    return diff < sessionValidDuration;
  }

  /// 清除活跃时间
  Future<void> clearLastActiveTime() async {
    await _storage.delete(key: _lastActiveKey);
  }

  // ===== 登出操作 =====

  /// 软登出（保留账号记忆，清除登录状态）
  Future<void> softLogout() async {
    await clearToken();
    await clearUserId();
    await clearUserData();
    await clearLastActiveTime();
    // 注意：不清除账号记忆信息
  }

  /// 完全登出（清除所有数据包括账号记忆）
  Future<void> clearAll() async {
    await _storage.deleteAll();
  }

  /// 切换账号（清除当前登录状态和账号记忆）
  Future<void> switchAccount() async {
    await clearAll();
  }
}
