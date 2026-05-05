import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../../data/models/websocket/websocket_message.dart';
import '../config/api_config.dart';
import '../services/notification_service.dart';
import '../storage/notification_settings.dart';
import 'websocket_service.dart';

/// 消息服务 - 单例模式
/// 用于管理实时消息更新的通知，集成 WebSocket 和通知系统
class MessageService with WidgetsBindingObserver {
  static final MessageService _instance = MessageService._internal();

  factory MessageService() => _instance;

  MessageService._internal();

  // 依赖的服务
  final WebSocketService _wsService = WebSocketService();
  final NotificationService _notificationService = NotificationService();
  final NotificationSettings _notificationSettings = NotificationSettings();

  // 消息更新通知控制器
  final _messageUpdateController = StreamController<int>.broadcast();
  final _chatUpdateController = StreamController<int>.broadcast();
  final _typingController = StreamController<TypingEvent>.broadcast();
  final _userStatusController = StreamController<UserStatusEvent>.broadcast();
  final _userProfileUpdateController = StreamController<UserProfileUpdateEvent>.broadcast();

  // WebSocket 消息订阅
  StreamSubscription<WebSocketMessage>? _wsSubscription;

  // 应用状态
  AppLifecycleState _appState = AppLifecycleState.resumed;

  // 当前活跃的聊天 ID
  int? _activeChatId;

  // 当前用户 ID
  int? _currentUserId;

  // 应用内通知显示回调
  Function(Map<String, dynamic> messageData)? onShowInAppNotification;

  /// 消息更新流 - 传递 chatId
  Stream<int> get messageUpdateStream => _messageUpdateController.stream;

  /// 聊天列表更新流 - 传递 chatId（0 表示刷新全部）
  Stream<int> get chatUpdateStream => _chatUpdateController.stream;

  /// 输入状态流
  Stream<TypingEvent> get typingStream => _typingController.stream;

  /// 用户状态流
  Stream<UserStatusEvent> get userStatusStream => _userStatusController.stream;

  /// 用户资料更新流（头像/昵称变化）
  Stream<UserProfileUpdateEvent> get userProfileUpdateStream => _userProfileUpdateController.stream;

  /// WebSocket 连接状态
  ValueListenable<WebSocketConnectionState> get connectionState =>
      _wsService.connectionState;

  /// 是否已连接
  bool get isConnected => _wsService.isConnected;

  /// 初始化服务
  Future<void> initialize() async {
    // 初始化通知服务
    await _notificationService.initialize();
    await _notificationSettings.initialize();

    // 请求通知权限
    await _notificationService.requestPermission();

    // 注册应用生命周期观察者
    WidgetsBinding.instance.addObserver(this);

    debugPrint('📨 MessageService: 初始化完成');
  }

  /// 连接 WebSocket
  Future<void> connect(int userId, String token) async {
    _currentUserId = userId;

    // 连接 WebSocket
    await _wsService.connect(userId, token);

    // 订阅 WebSocket 消息
    _wsSubscription?.cancel();
    _wsSubscription = _wsService.messageStream.listen(_handleWebSocketMessage);

    debugPrint('📨 MessageService: WebSocket 已连接');
  }

  /// 断开 WebSocket
  Future<void> disconnect() async {
    _wsSubscription?.cancel();
    _wsSubscription = null;
    await _wsService.disconnect();
    _currentUserId = null;
    debugPrint('📨 MessageService: WebSocket 已断开');
  }

  /// 设置当前活跃的聊天
  void setActiveChatId(int? chatId) {
    _activeChatId = chatId;
    if (chatId != null) {
      // 进入聊天时取消该聊天的通知
      _notificationService.cancelChatNotifications(chatId);
    }
    debugPrint('📨 MessageService: 活跃聊天 $_activeChatId');
  }

  /// 处理 WebSocket 消息
  void _handleWebSocketMessage(WebSocketMessage message) {
    debugPrint('📨 MessageService: 处理消息 ${message.type}');

    switch (message.type) {
      case WebSocketMessageType.chatMessage:
        _handleChatMessage(message.payload);
        break;
      case WebSocketMessageType.messageRead:
        _handleMessageRead(message.payload);
        break;
      case WebSocketMessageType.typing:
        _handleTyping(message.payload);
        break;
      case WebSocketMessageType.messageAck:
        _handleMessageAck(message.payload);
        break;
      case WebSocketMessageType.userOnline:
      case WebSocketMessageType.userOffline:
        _handleUserStatus(message);
        break;
      case WebSocketMessageType.userProfileUpdated:
        _handleUserProfileUpdate(message.payload);
        break;
      case WebSocketMessageType.contactRequest:
        _handleContactRequest(message.payload);
        break;
      case WebSocketMessageType.groupMemberJoined:
      case WebSocketMessageType.groupMemberLeft:
        _handleGroupMemberChange(message.payload);
        break;
      default:
        debugPrint('📨 MessageService: 未处理的消息类型 ${message.type}');
    }
  }

  /// 处理聊天消息
  void _handleChatMessage(Map<String, dynamic> payload) {
    final chatId = payload['chatId'] as int?;
    final senderId = payload['senderId'] as int?;

    if (chatId == null) return;

    // 不处理自己发送的消息
    if (senderId == _currentUserId) {
      debugPrint('📨 MessageService: 跳过自己发送的消息');
      // 仍然需要更新聊天列表
      _chatUpdateController.add(chatId);
      return;
    }

    // 通知 UI 更新
    _messageUpdateController.add(chatId);
    _chatUpdateController.add(chatId);

    // 根据应用状态决定通知方式
    _showNotification(chatId, payload);
  }

  /// 显示通知
  void _showNotification(int chatId, Map<String, dynamic> payload) {
    // 检查是否静音
    if (_notificationSettings.isChatMuted(chatId)) {
      debugPrint('📨 MessageService: 聊天 $chatId 已静音');
      return;
    }

    final senderName = payload['senderNickname'] as String? ?? '未知用户';
    final senderAvatar = payload['senderAvatar'] as String?;
    final content = payload['content'] as String? ?? '';
    final messageType = payload['messageType'] as String?;

    if (_appState == AppLifecycleState.paused ||
        _appState == AppLifecycleState.inactive ||
        _appState == AppLifecycleState.detached) {
      // 后台: 显示系统通知
      debugPrint('📨 MessageService: 应用在后台，显示系统通知');
      _notificationService.showMessageNotification(
        chatId: chatId,
        senderName: senderName,
        senderAvatar: senderAvatar,
        content: content,
        messageType: messageType,
      );
    } else if (_activeChatId != chatId) {
      // 前台但不在当前聊天: 显示应用内横幅
      debugPrint('📨 MessageService: 应用在前台，显示应用内横幅');
      onShowInAppNotification?.call(payload);
    }
    // 前台且在当前聊天: 仅更新 UI，不显示通知
  }

  /// 处理消息已读
  void _handleMessageRead(Map<String, dynamic> payload) {
    final chatId = payload['chatId'] as int?;
    if (chatId != null) {
      _messageUpdateController.add(chatId);
    }
  }

  /// 处理输入状态
  void _handleTyping(Map<String, dynamic> payload) {
    final chatId = payload['chatId'] as int?;
    final userId = payload['userId'] as int?;
    final isTyping = payload['isTyping'] as bool? ?? false;

    if (chatId != null && userId != null) {
      _typingController.add(TypingEvent(
        chatId: chatId,
        userId: userId,
        isTyping: isTyping,
      ));
    }
  }

  /// 处理消息确认
  void _handleMessageAck(Map<String, dynamic> payload) {
    final chatId = payload['chatId'] as int?;
    if (chatId != null) {
      _messageUpdateController.add(chatId);
    }
  }

  /// 处理用户状态变化
  void _handleUserStatus(WebSocketMessage message) {
    final userId = message.payload['userId'] as int?;
    final isOnline = message.type == WebSocketMessageType.userOnline;

    if (userId != null) {
      _userStatusController.add(UserStatusEvent(
        userId: userId,
        isOnline: isOnline,
      ));
    }
  }

  /// 处理用户资料更新（头像/昵称变化）
  void _handleUserProfileUpdate(Map<String, dynamic> payload) async {
    final userId = payload['userId'] as int?;
    final avatarUrl = payload['avatarUrl'] as String?;
    final nickname = payload['nickname'] as String?;

    if (userId == null) return;

    debugPrint('📨 MessageService: 收到用户资料更新 userId=$userId, avatarUrl=$avatarUrl');

    // 清除该用户的旧头像缓存
    if (avatarUrl != null && avatarUrl.isNotEmpty) {
      try {
        // 构建完整的头像URL
        final fullAvatarUrl = ApiConfig.getFullUrl(avatarUrl);
        // 注意：我们无法清除旧的URL缓存（因为不知道旧URL），
        // 但新URL应该会自动加载新图片
        debugPrint('📨 MessageService: 新头像URL $fullAvatarUrl');
      } catch (e) {
        debugPrint('📨 MessageService: 处理头像缓存失败 $e');
      }
    }

    // 通知订阅者用户资料已更新
    _userProfileUpdateController.add(UserProfileUpdateEvent(
      userId: userId,
      avatarUrl: avatarUrl,
      nickname: nickname,
    ));

    // 触发聊天列表刷新以获取最新数据
    _chatUpdateController.add(0);
  }

  /// 处理好友请求
  void _handleContactRequest(Map<String, dynamic> payload) {
    final fromUserId = payload['fromUserId'] as int?;
    final fromUsername = payload['fromUsername'] as String? ?? '用户';
    final message = payload['message'] as String?;

    if (fromUserId != null) {
      _notificationService.showContactRequestNotification(
        fromUserId: fromUserId,
        fromUsername: fromUsername,
        message: message,
      );
    }
  }

  /// 处理群成员变化
  void _handleGroupMemberChange(Map<String, dynamic> payload) {
    final chatId = payload['groupId'] as int? ?? payload['chatId'] as int?;
    if (chatId != null) {
      _chatUpdateController.add(chatId);
    }
  }

  /// 通知有新消息（手动触发，兼容旧逻辑）
  void notifyNewMessage(int chatId) {
    debugPrint('📨 MessageService: 通知新消息 chatId=$chatId');
    _messageUpdateController.add(chatId);
    _chatUpdateController.add(chatId);
  }

  /// 通知聊天列表需要刷新
  void notifyChatsUpdate() {
    debugPrint('📨 MessageService: 通知聊天列表更新');
    _chatUpdateController.add(0);
  }

  /// 发送输入状态
  void sendTyping(int chatId, bool isTyping) {
    if (_currentUserId == null) return;

    _wsService.send('/app/chat.typing', {
      'chatId': chatId,
      'userId': _currentUserId,
      'isTyping': isTyping,
    });
  }

  /// 发送消息已读
  void sendMessageRead(int chatId, int messageId) {
    if (_currentUserId == null) return;

    _wsService.send('/app/message.read', {
      'chatId': chatId,
      'userId': _currentUserId,
      'messageId': messageId,
    });
  }

  /// 应用生命周期回调
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _appState = state;
    debugPrint('📨 MessageService: 应用状态变化 $state');

    if (state == AppLifecycleState.resumed) {
      // 应用回到前台，检查 WebSocket 连接
      if (!_wsService.isConnected && _currentUserId != null) {
        _wsService.reconnect();
      }
    }
  }

  /// 释放资源
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _wsSubscription?.cancel();
    _messageUpdateController.close();
    _chatUpdateController.close();
    _typingController.close();
    _userStatusController.close();
    _userProfileUpdateController.close();
  }
}

/// 输入状态事件
class TypingEvent {
  final int chatId;
  final int userId;
  final bool isTyping;

  TypingEvent({
    required this.chatId,
    required this.userId,
    required this.isTyping,
  });
}

/// 用户状态事件
class UserStatusEvent {
  final int userId;
  final bool isOnline;

  UserStatusEvent({
    required this.userId,
    required this.isOnline,
  });
}

/// 用户资料更新事件
class UserProfileUpdateEvent {
  final int userId;
  final String? avatarUrl;
  final String? nickname;

  UserProfileUpdateEvent({
    required this.userId,
    this.avatarUrl,
    this.nickname,
  });
}
