/// WebSocket 消息类型枚举
/// 对应后端 WebSocketMessage.MessageType
enum WebSocketMessageType {
  // 聊天消息
  chatMessage,
  messageRead,
  typing,

  // 消息确认
  messageAck,
  messageDelivered,
  messageDeliveryFailed,

  // 群组事件
  groupCreated,
  groupUpdated,
  groupDeleted,
  groupMemberJoined,
  groupMemberLeft,
  groupAdminChanged,
  groupOwnershipTransferred,

  // 联系人事件
  contactAdded,
  contactRemoved,
  contactRequest,
  contactRequestAccepted,
  contactRequestRejected,
  contactStatusChanged,

  // 通话信令
  callInvite,
  callAccept,
  callReject,
  callCancel,
  callOffer,
  callAnswer,
  callIceCandidate,
  callMute,
  callVideoToggle,
  callEnd,
  callTimeout,

  // 同步
  syncRequest,
  syncResponse,

  // 用户状态
  userOnline,
  userOffline,
  userStatusChanged,
  userProfileUpdated,  // 用户头像/昵称更新

  // 错误
  error,

  // 未知类型
  unknown,
}

/// WebSocket 消息模型
class WebSocketMessage {
  final WebSocketMessageType type;
  final Map<String, dynamic> payload;

  WebSocketMessage({
    required this.type,
    required this.payload,
  });

  factory WebSocketMessage.fromJson(Map<String, dynamic> json) {
    return WebSocketMessage(
      type: _parseType(json['type'] as String?),
      payload: json['payload'] as Map<String, dynamic>? ?? {},
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'type': _typeToString(type),
      'payload': payload,
    };
  }

  /// 解析消息类型字符串
  static WebSocketMessageType _parseType(String? typeStr) {
    switch (typeStr) {
      // 聊天消息
      case 'CHAT_MESSAGE':
        return WebSocketMessageType.chatMessage;
      case 'MESSAGE_READ':
        return WebSocketMessageType.messageRead;
      case 'TYPING':
        return WebSocketMessageType.typing;

      // 消息确认
      case 'MESSAGE_ACK':
        return WebSocketMessageType.messageAck;
      case 'MESSAGE_DELIVERED':
        return WebSocketMessageType.messageDelivered;
      case 'MESSAGE_DELIVERY_FAILED':
        return WebSocketMessageType.messageDeliveryFailed;

      // 群组事件
      case 'GROUP_CREATED':
        return WebSocketMessageType.groupCreated;
      case 'GROUP_UPDATED':
        return WebSocketMessageType.groupUpdated;
      case 'GROUP_DELETED':
        return WebSocketMessageType.groupDeleted;
      case 'GROUP_MEMBER_JOINED':
        return WebSocketMessageType.groupMemberJoined;
      case 'GROUP_MEMBER_LEFT':
        return WebSocketMessageType.groupMemberLeft;
      case 'GROUP_ADMIN_CHANGED':
        return WebSocketMessageType.groupAdminChanged;
      case 'GROUP_OWNERSHIP_TRANSFERRED':
        return WebSocketMessageType.groupOwnershipTransferred;

      // 联系人事件
      case 'CONTACT_ADDED':
        return WebSocketMessageType.contactAdded;
      case 'CONTACT_REMOVED':
        return WebSocketMessageType.contactRemoved;
      case 'CONTACT_REQUEST':
        return WebSocketMessageType.contactRequest;
      case 'CONTACT_REQUEST_ACCEPTED':
        return WebSocketMessageType.contactRequestAccepted;
      case 'CONTACT_REQUEST_REJECTED':
        return WebSocketMessageType.contactRequestRejected;
      case 'CONTACT_STATUS_CHANGED':
        return WebSocketMessageType.contactStatusChanged;

      // 通话信令
      case 'CALL_INVITE':
        return WebSocketMessageType.callInvite;
      case 'CALL_ACCEPT':
        return WebSocketMessageType.callAccept;
      case 'CALL_REJECT':
        return WebSocketMessageType.callReject;
      case 'CALL_CANCEL':
        return WebSocketMessageType.callCancel;
      case 'CALL_OFFER':
        return WebSocketMessageType.callOffer;
      case 'CALL_ANSWER':
        return WebSocketMessageType.callAnswer;
      case 'CALL_ICE_CANDIDATE':
        return WebSocketMessageType.callIceCandidate;
      case 'CALL_MUTE':
        return WebSocketMessageType.callMute;
      case 'CALL_VIDEO_TOGGLE':
        return WebSocketMessageType.callVideoToggle;
      case 'CALL_END':
        return WebSocketMessageType.callEnd;
      case 'CALL_TIMEOUT':
        return WebSocketMessageType.callTimeout;

      // 同步
      case 'SYNC_REQUEST':
        return WebSocketMessageType.syncRequest;
      case 'SYNC_RESPONSE':
        return WebSocketMessageType.syncResponse;

      // 用户状态
      case 'USER_ONLINE':
        return WebSocketMessageType.userOnline;
      case 'USER_OFFLINE':
        return WebSocketMessageType.userOffline;
      case 'USER_STATUS_CHANGED':
        return WebSocketMessageType.userStatusChanged;
      case 'USER_PROFILE_UPDATED':
        return WebSocketMessageType.userProfileUpdated;

      // 错误
      case 'ERROR':
        return WebSocketMessageType.error;

      default:
        return WebSocketMessageType.unknown;
    }
  }

  /// 将消息类型转换为字符串
  static String _typeToString(WebSocketMessageType type) {
    switch (type) {
      case WebSocketMessageType.chatMessage:
        return 'CHAT_MESSAGE';
      case WebSocketMessageType.messageRead:
        return 'MESSAGE_READ';
      case WebSocketMessageType.typing:
        return 'TYPING';
      case WebSocketMessageType.messageAck:
        return 'MESSAGE_ACK';
      case WebSocketMessageType.messageDelivered:
        return 'MESSAGE_DELIVERED';
      case WebSocketMessageType.messageDeliveryFailed:
        return 'MESSAGE_DELIVERY_FAILED';
      case WebSocketMessageType.groupCreated:
        return 'GROUP_CREATED';
      case WebSocketMessageType.groupUpdated:
        return 'GROUP_UPDATED';
      case WebSocketMessageType.groupDeleted:
        return 'GROUP_DELETED';
      case WebSocketMessageType.groupMemberJoined:
        return 'GROUP_MEMBER_JOINED';
      case WebSocketMessageType.groupMemberLeft:
        return 'GROUP_MEMBER_LEFT';
      case WebSocketMessageType.groupAdminChanged:
        return 'GROUP_ADMIN_CHANGED';
      case WebSocketMessageType.groupOwnershipTransferred:
        return 'GROUP_OWNERSHIP_TRANSFERRED';
      case WebSocketMessageType.contactAdded:
        return 'CONTACT_ADDED';
      case WebSocketMessageType.contactRemoved:
        return 'CONTACT_REMOVED';
      case WebSocketMessageType.contactRequest:
        return 'CONTACT_REQUEST';
      case WebSocketMessageType.contactRequestAccepted:
        return 'CONTACT_REQUEST_ACCEPTED';
      case WebSocketMessageType.contactRequestRejected:
        return 'CONTACT_REQUEST_REJECTED';
      case WebSocketMessageType.contactStatusChanged:
        return 'CONTACT_STATUS_CHANGED';
      case WebSocketMessageType.callInvite:
        return 'CALL_INVITE';
      case WebSocketMessageType.callAccept:
        return 'CALL_ACCEPT';
      case WebSocketMessageType.callReject:
        return 'CALL_REJECT';
      case WebSocketMessageType.callCancel:
        return 'CALL_CANCEL';
      case WebSocketMessageType.callOffer:
        return 'CALL_OFFER';
      case WebSocketMessageType.callAnswer:
        return 'CALL_ANSWER';
      case WebSocketMessageType.callIceCandidate:
        return 'CALL_ICE_CANDIDATE';
      case WebSocketMessageType.callMute:
        return 'CALL_MUTE';
      case WebSocketMessageType.callVideoToggle:
        return 'CALL_VIDEO_TOGGLE';
      case WebSocketMessageType.callEnd:
        return 'CALL_END';
      case WebSocketMessageType.callTimeout:
        return 'CALL_TIMEOUT';
      case WebSocketMessageType.syncRequest:
        return 'SYNC_REQUEST';
      case WebSocketMessageType.syncResponse:
        return 'SYNC_RESPONSE';
      case WebSocketMessageType.userOnline:
        return 'USER_ONLINE';
      case WebSocketMessageType.userOffline:
        return 'USER_OFFLINE';
      case WebSocketMessageType.userStatusChanged:
        return 'USER_STATUS_CHANGED';
      case WebSocketMessageType.userProfileUpdated:
        return 'USER_PROFILE_UPDATED';
      case WebSocketMessageType.error:
        return 'ERROR';
      case WebSocketMessageType.unknown:
        return 'UNKNOWN';
    }
  }

  /// 便捷方法：获取聊天消息的 chatId
  int? get chatId => payload['chatId'] as int?;

  /// 便捷方法：获取发送者 ID
  int? get senderId => payload['senderId'] as int?;

  /// 便捷方法：获取发送者昵称
  String? get senderNickname => payload['senderNickname'] as String?;

  /// 便捷方法：获取发送者头像
  String? get senderAvatar => payload['senderAvatar'] as String?;

  /// 便捷方法：获取消息内容
  String? get content => payload['content'] as String?;

  /// 便捷方法：获取消息类型
  String? get messageType => payload['messageType'] as String?;

  /// 便捷方法：获取用户 ID
  int? get userId => payload['userId'] as int?;

  @override
  String toString() {
    return 'WebSocketMessage{type: $type, payload: $payload}';
  }
}
