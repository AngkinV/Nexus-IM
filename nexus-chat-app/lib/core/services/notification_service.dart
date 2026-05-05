import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../storage/notification_settings.dart';

/// 通知服务 - 单例模式
/// 用于管理本地推送通知
class NotificationService {
  static final NotificationService _instance = NotificationService._internal();

  factory NotificationService() => _instance;

  NotificationService._internal();

  late FlutterLocalNotificationsPlugin _plugin;
  bool _isInitialized = false;

  // 通知设置
  final NotificationSettings _settings = NotificationSettings();

  // 通知点击回调
  Function(int chatId)? onNotificationTap;

  /// 是否已初始化
  bool get isInitialized => _isInitialized;

  /// 初始化通知服务
  Future<void> initialize() async {
    if (_isInitialized) return;

    _plugin = FlutterLocalNotificationsPlugin();

    // Android 初始化设置
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');

    // iOS 初始化设置
    const iosSettings = DarwinInitializationSettings(
      requestAlertPermission: false,
      requestBadgePermission: false,
      requestSoundPermission: false,
    );

    // 初始化
    await _plugin.initialize(
      const InitializationSettings(
        android: androidSettings,
        iOS: iosSettings,
      ),
      onDidReceiveNotificationResponse: _onNotificationResponse,
    );

    // 初始化通知设置
    await _settings.initialize();

    _isInitialized = true;
    debugPrint('🔔 NotificationService: 初始化完成');
  }

  /// 请求通知权限
  Future<bool> requestPermission() async {
    if (Platform.isIOS) {
      final ios = _plugin.resolvePlatformSpecificImplementation<
          IOSFlutterLocalNotificationsPlugin>();
      final granted = await ios?.requestPermissions(
        alert: true,
        badge: true,
        sound: true,
      );
      debugPrint('🔔 NotificationService: iOS 权限请求结果 $granted');
      return granted ?? false;
    }

    if (Platform.isAndroid) {
      final android = _plugin.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      final granted = await android?.requestNotificationsPermission();
      debugPrint('🔔 NotificationService: Android 权限请求结果 $granted');
      return granted ?? false;
    }

    return false;
  }

  /// 显示消息通知
  Future<void> showMessageNotification({
    required int chatId,
    required String senderName,
    String? senderAvatar,
    required String content,
    String? messageType,
  }) async {
    if (!_isInitialized) {
      debugPrint('🔔 NotificationService: 未初始化，跳过通知');
      return;
    }

    // 检查是否静音
    if (_settings.isChatMuted(chatId)) {
      debugPrint('🔔 NotificationService: 聊天 $chatId 已静音，跳过通知');
      return;
    }

    // 根据消息类型格式化内容
    String body = content;
    if (messageType == 'IMAGE') {
      body = '[图片]';
    } else if (messageType == 'VIDEO') {
      body = '[视频]';
    } else if (messageType == 'AUDIO') {
      body = '[语音]';
    } else if (messageType == 'FILE') {
      body = '[文件]';
    } else if (messageType == 'EMOJI') {
      body = '[表情]';
    }

    // Android 通知详情
    final androidDetails = AndroidNotificationDetails(
      'messages',
      '消息通知',
      channelDescription: '聊天消息通知',
      importance: Importance.high,
      priority: Priority.high,
      groupKey: 'chat_$chatId',
      category: AndroidNotificationCategory.message,
      autoCancel: true,
    );

    // iOS 通知详情
    final iosDetails = DarwinNotificationDetails(
      threadIdentifier: 'chat_$chatId',
      presentAlert: true,
      presentBadge: true,
      presentSound: true,
    );

    // 生成通知 ID
    final notificationId = _generateNotificationId(chatId);

    // 显示通知
    await _plugin.show(
      notificationId,
      senderName,
      body,
      NotificationDetails(
        android: androidDetails,
        iOS: iosDetails,
      ),
      payload: jsonEncode({'chatId': chatId}),
    );

    debugPrint('🔔 NotificationService: 显示通知 chatId=$chatId');
  }

  /// 显示好友请求通知
  Future<void> showContactRequestNotification({
    required int fromUserId,
    required String fromUsername,
    String? message,
  }) async {
    if (!_isInitialized) return;

    final androidDetails = AndroidNotificationDetails(
      'contacts',
      '好友通知',
      channelDescription: '好友申请和联系人通知',
      importance: Importance.high,
      priority: Priority.high,
      category: AndroidNotificationCategory.social,
      autoCancel: true,
    );

    const iosDetails = DarwinNotificationDetails(
      threadIdentifier: 'contacts',
      presentAlert: true,
      presentBadge: true,
      presentSound: true,
    );

    await _plugin.show(
      fromUserId + 100000, // 避免与聊天通知 ID 冲突
      '新的好友请求',
      '$fromUsername 请求添加你为好友${message != null ? "：$message" : ""}',
      NotificationDetails(
        android: androidDetails,
        iOS: iosDetails,
      ),
      payload: jsonEncode({'type': 'contact_request', 'fromUserId': fromUserId}),
    );
  }

  /// 取消指定聊天的所有通知
  Future<void> cancelChatNotifications(int chatId) async {
    if (!_isInitialized) return;

    // 取消该聊天的通知
    await _plugin.cancel(_generateNotificationId(chatId));
    debugPrint('🔔 NotificationService: 取消聊天 $chatId 的通知');
  }

  /// 取消所有通知
  Future<void> cancelAllNotifications() async {
    if (!_isInitialized) return;

    await _plugin.cancelAll();
    debugPrint('🔔 NotificationService: 取消所有通知');
  }

  /// 更新应用角标
  Future<void> updateBadgeCount(int count) async {
    // TODO: 使用 flutter_app_badger 更新角标
    // FlutterAppBadger.updateBadgeCount(count);
    debugPrint('🔔 NotificationService: 更新角标 $count');
  }

  /// 静音聊天
  void muteChat(int chatId) {
    _settings.muteChat(chatId);
    debugPrint('🔔 NotificationService: 静音聊天 $chatId');
  }

  /// 取消静音聊天
  void unmuteChat(int chatId) {
    _settings.unmuteChat(chatId);
    debugPrint('🔔 NotificationService: 取消静音聊天 $chatId');
  }

  /// 检查聊天是否静音
  bool isChatMuted(int chatId) {
    return _settings.isChatMuted(chatId);
  }

  /// 通知点击回调
  void _onNotificationResponse(NotificationResponse response) {
    if (response.payload == null) return;

    try {
      final data = jsonDecode(response.payload!) as Map<String, dynamic>;

      if (data.containsKey('chatId')) {
        final chatId = data['chatId'] as int;
        debugPrint('🔔 NotificationService: 点击通知 chatId=$chatId');
        onNotificationTap?.call(chatId);
      }
    } catch (e) {
      debugPrint('🔔 NotificationService: 解析通知数据失败 $e');
    }
  }

  /// 生成通知 ID
  int _generateNotificationId(int chatId) {
    // 使用 chatId 作为基础，确保同一聊天的通知 ID 相同
    return chatId;
  }
}
