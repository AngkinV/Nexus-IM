import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';
import 'package:stomp_dart_client/stomp_frame.dart';
import 'package:stomp_dart_client/stomp_handler.dart';

import '../../data/models/websocket/websocket_message.dart';
import '../config/api_config.dart';

/// WebSocket 连接状态
enum WebSocketConnectionState {
  /// 已断开
  disconnected,

  /// 连接中
  connecting,

  /// 已连接
  connected,

  /// 重连中
  reconnecting,

  /// 连接失败
  failed,
}

/// WebSocket 服务 - 单例模式
/// 用于管理 STOMP WebSocket 连接
class WebSocketService {
  static final WebSocketService _instance = WebSocketService._internal();

  factory WebSocketService() => _instance;

  WebSocketService._internal();

  // STOMP 客户端
  StompClient? _stompClient;

  // 当前用户信息
  int? _currentUserId;
  String? _currentToken;

  // 定时器
  Timer? _heartbeatTimer;
  Timer? _reconnectTimer;

  // 重连配置
  int _reconnectAttempts = 0;
  static const int _maxReconnectAttempts = 10;
  static const Duration _baseReconnectDelay = Duration(seconds: 2);

  // 连接状态
  final _connectionState = ValueNotifier<WebSocketConnectionState>(
    WebSocketConnectionState.disconnected,
  );

  // 消息流控制器
  final _messageController = StreamController<WebSocketMessage>.broadcast();

  // 订阅管理
  final Map<String, StompUnsubscribe> _subscriptions = {};

  /// 获取连接状态
  ValueListenable<WebSocketConnectionState> get connectionState =>
      _connectionState;

  /// 获取消息流
  Stream<WebSocketMessage> get messageStream => _messageController.stream;

  /// 是否已连接
  bool get isConnected =>
      _stompClient != null && _stompClient!.connected;

  /// 连接 WebSocket
  Future<void> connect(int userId, String token) async {
    // 如果已连接且用户相同，直接返回
    if (isConnected && _currentUserId == userId) {
      debugPrint('🔌 WebSocket: 已连接，无需重复连接');
      return;
    }

    // 如果正在连接，等待连接完成
    if (_connectionState.value == WebSocketConnectionState.connecting) {
      debugPrint('🔌 WebSocket: 正在连接中，请等待');
      return;
    }

    // 断开现有连接
    if (_stompClient != null) {
      await disconnect();
    }

    _currentUserId = userId;
    _currentToken = token;
    _connectionState.value = WebSocketConnectionState.connecting;

    // 获取 WebSocket URL
    final wsUrl = ApiConfig.getWsUrl(isAndroid: Platform.isAndroid);
    debugPrint('🔌 WebSocket: 正在连接 $wsUrl');

    try {
      _stompClient = StompClient(
        config: StompConfig(
          url: wsUrl,
          stompConnectHeaders: {
            'Authorization': 'Bearer $token',
          },
          onConnect: _onConnect,
          onDisconnect: _onDisconnect,
          onStompError: _onStompError,
          onWebSocketError: _onWebSocketError,
          heartbeatIncoming: const Duration(seconds: 10),
          heartbeatOutgoing: const Duration(seconds: 10),
          reconnectDelay: const Duration(seconds: 5),
        ),
      );

      _stompClient!.activate();
    } catch (e) {
      debugPrint('🔌 WebSocket: 连接失败 $e');
      _connectionState.value = WebSocketConnectionState.failed;
      _scheduleReconnect();
    }
  }

  /// 连接成功回调
  void _onConnect(StompFrame frame) {
    debugPrint('🔌 WebSocket: 连接成功');
    _connectionState.value = WebSocketConnectionState.connected;
    _reconnectAttempts = 0;

    // 订阅用户消息频道
    _subscribeToUserMessages();

    // 启动心跳
    _startHeartbeat();

    // 通知上线
    _sendUserStatus(true);
  }

  /// 订阅用户消息频道
  void _subscribeToUserMessages() {
    if (_currentUserId == null) return;

    final destination = '/topic/user.$_currentUserId.messages';
    debugPrint('🔌 WebSocket: 订阅 $destination');

    final unsubscribe = _stompClient?.subscribe(
      destination: destination,
      callback: _onMessage,
    );

    if (unsubscribe != null) {
      _subscriptions[destination] = unsubscribe;
    }
  }

  /// 收到消息回调
  void _onMessage(StompFrame frame) {
    if (frame.body == null) return;

    try {
      final json = jsonDecode(frame.body!) as Map<String, dynamic>;
      final message = WebSocketMessage.fromJson(json);
      debugPrint('📨 WebSocket: 收到消息 ${message.type}');
      _messageController.add(message);
    } catch (e) {
      debugPrint('📨 WebSocket: 消息解析失败 $e');
    }
  }

  /// 断开连接回调
  void _onDisconnect(StompFrame frame) {
    debugPrint('🔌 WebSocket: 连接断开');
    _connectionState.value = WebSocketConnectionState.disconnected;
    _stopHeartbeat();
    _clearSubscriptions();

    // 如果不是主动断开，尝试重连
    if (_currentUserId != null && _currentToken != null) {
      _scheduleReconnect();
    }
  }

  /// STOMP 错误回调
  void _onStompError(StompFrame frame) {
    debugPrint('🔌 WebSocket: STOMP 错误 ${frame.body}');
    _connectionState.value = WebSocketConnectionState.failed;
  }

  /// WebSocket 错误回调
  void _onWebSocketError(dynamic error) {
    debugPrint('🔌 WebSocket: 连接错误 $error');
    _connectionState.value = WebSocketConnectionState.failed;
  }

  /// 发送消息
  void send(String destination, Map<String, dynamic> payload) {
    if (!isConnected) {
      debugPrint('🔌 WebSocket: 未连接，无法发送消息');
      return;
    }

    try {
      _stompClient!.send(
        destination: destination,
        body: jsonEncode(payload),
      );
      debugPrint('📤 WebSocket: 发送消息到 $destination');
    } catch (e) {
      debugPrint('📤 WebSocket: 发送失败 $e');
    }
  }

  /// 发送用户状态
  void _sendUserStatus(bool isOnline) {
    if (_currentUserId == null) return;

    send('/app/user.status', {
      'userId': _currentUserId,
      'isOnline': isOnline,
    });
  }

  /// 发送心跳
  void _sendHeartbeat() {
    if (_currentUserId == null) return;

    send('/app/user.heartbeat', {
      'userId': _currentUserId,
    });
  }

  /// 启动心跳定时器
  void _startHeartbeat() {
    _stopHeartbeat();
    _heartbeatTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _sendHeartbeat(),
    );
    debugPrint('💓 WebSocket: 心跳启动');
  }

  /// 停止心跳定时器
  void _stopHeartbeat() {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;
  }

  /// 计划重连
  void _scheduleReconnect() {
    if (_reconnectAttempts >= _maxReconnectAttempts) {
      debugPrint('🔌 WebSocket: 达到最大重连次数，停止重连');
      _connectionState.value = WebSocketConnectionState.failed;
      return;
    }

    _reconnectTimer?.cancel();
    _connectionState.value = WebSocketConnectionState.reconnecting;
    _reconnectAttempts++;

    // 指数退避
    final delay = _getReconnectDelay();
    debugPrint('🔌 WebSocket: ${delay.inSeconds}秒后尝试第$_reconnectAttempts次重连');

    _reconnectTimer = Timer(delay, () {
      if (_currentUserId != null && _currentToken != null) {
        connect(_currentUserId!, _currentToken!);
      }
    });
  }

  /// 获取重连延迟（指数退避 + 随机抖动）
  Duration _getReconnectDelay() {
    final exponential = _baseReconnectDelay.inMilliseconds *
        pow(2, min(_reconnectAttempts - 1, 5));
    final jitter = Random().nextInt(1000);
    return Duration(milliseconds: exponential.toInt() + jitter);
  }

  /// 清理所有订阅
  void _clearSubscriptions() {
    for (final unsubscribe in _subscriptions.values) {
      try {
        unsubscribe();
      } catch (_) {}
    }
    _subscriptions.clear();
  }

  /// 断开连接
  Future<void> disconnect() async {
    debugPrint('🔌 WebSocket: 主动断开连接');

    // 取消重连定时器
    _reconnectTimer?.cancel();
    _reconnectTimer = null;

    // 停止心跳
    _stopHeartbeat();

    // 通知下线
    if (isConnected) {
      _sendUserStatus(false);
    }

    // 清理订阅
    _clearSubscriptions();

    // 断开 STOMP
    try {
      _stompClient?.deactivate();
    } catch (_) {}
    _stompClient = null;

    // 清理用户信息
    _currentUserId = null;
    _currentToken = null;
    _reconnectAttempts = 0;

    _connectionState.value = WebSocketConnectionState.disconnected;
  }

  /// 重新连接（手动触发）
  Future<void> reconnect() async {
    if (_currentUserId != null && _currentToken != null) {
      _reconnectAttempts = 0;
      await connect(_currentUserId!, _currentToken!);
    }
  }

  /// 释放资源
  void dispose() {
    disconnect();
    _messageController.close();
    _connectionState.dispose();
  }
}
