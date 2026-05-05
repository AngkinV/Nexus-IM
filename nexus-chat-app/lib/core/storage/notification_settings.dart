import 'package:flutter/foundation.dart';
import 'package:hive_flutter/hive_flutter.dart';

/// 通知设置存储 - 使用 Hive
/// 用于存储静音聊天列表等通知偏好设置
class NotificationSettings {
  static final NotificationSettings _instance = NotificationSettings._internal();

  factory NotificationSettings() => _instance;

  NotificationSettings._internal();

  static const String _boxName = 'notification_settings';
  static const String _mutedChatsKey = 'muted_chats';
  static const String _notificationsEnabledKey = 'notifications_enabled';

  Box? _box;
  bool _isInitialized = false;

  // 内存中的静音聊天列表（用于快速查询）
  final Set<int> _mutedChats = {};

  /// 初始化
  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      _box = await Hive.openBox(_boxName);

      // 加载静音聊天列表
      final mutedList = _box?.get(_mutedChatsKey) as List<dynamic>?;
      if (mutedList != null) {
        _mutedChats.addAll(mutedList.cast<int>());
      }

      _isInitialized = true;
      debugPrint('🔔 NotificationSettings: 初始化完成，静音聊天数: ${_mutedChats.length}');
    } catch (e) {
      debugPrint('🔔 NotificationSettings: 初始化失败 $e');
    }
  }

  /// 是否启用通知
  bool get isNotificationsEnabled {
    return _box?.get(_notificationsEnabledKey, defaultValue: true) ?? true;
  }

  /// 设置是否启用通知
  Future<void> setNotificationsEnabled(bool enabled) async {
    await _box?.put(_notificationsEnabledKey, enabled);
  }

  /// 检查聊天是否静音
  bool isChatMuted(int chatId) {
    return _mutedChats.contains(chatId);
  }

  /// 静音聊天
  Future<void> muteChat(int chatId) async {
    _mutedChats.add(chatId);
    await _saveMutedChats();
  }

  /// 取消静音聊天
  Future<void> unmuteChat(int chatId) async {
    _mutedChats.remove(chatId);
    await _saveMutedChats();
  }

  /// 切换聊天静音状态
  Future<void> toggleChatMute(int chatId) async {
    if (_mutedChats.contains(chatId)) {
      await unmuteChat(chatId);
    } else {
      await muteChat(chatId);
    }
  }

  /// 获取所有静音聊天 ID
  Set<int> get mutedChats => Set.unmodifiable(_mutedChats);

  /// 保存静音聊天列表
  Future<void> _saveMutedChats() async {
    await _box?.put(_mutedChatsKey, _mutedChats.toList());
  }

  /// 清除所有设置
  Future<void> clear() async {
    _mutedChats.clear();
    await _box?.clear();
  }
}
