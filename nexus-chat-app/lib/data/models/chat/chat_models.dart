/// 聊天类型
enum ChatType { direct, group }

/// 消息类型
enum MessageType { text, image, video, audio, file }

/// 聊天模型
class ChatModel {
  final int id;
  final ChatType type;
  final String? name;
  final String? description;
  final String? avatar;
  final bool isPrivate;
  final int? createdBy;
  final int memberCount;
  final DateTime? createdAt;
  final DateTime? lastMessageAt;
  final MessageModel? lastMessage;
  final int unreadCount;
  final List<ChatMemberModel> members;

  ChatModel({
    required this.id,
    required this.type,
    this.name,
    this.description,
    this.avatar,
    this.isPrivate = false,
    this.createdBy,
    this.memberCount = 0,
    this.createdAt,
    this.lastMessageAt,
    this.lastMessage,
    this.unreadCount = 0,
    this.members = const [],
  });

  factory ChatModel.fromJson(Map<String, dynamic> json) {
    // 支持大写和小写的类型值
    final typeStr = (json['type'] as String?)?.toLowerCase();
    return ChatModel(
      id: json['id'] ?? 0,
      type: typeStr == 'group' ? ChatType.group : ChatType.direct,
      name: json['name'],
      description: json['description'],
      avatar: json['avatar'],
      isPrivate: json['isPrivate'] ?? false,
      createdBy: json['createdBy'],
      memberCount: json['memberCount'] ?? 0,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'])
          : null,
      lastMessageAt: json['lastMessageAt'] != null
          ? DateTime.tryParse(json['lastMessageAt'])
          : null,
      lastMessage: json['lastMessage'] != null
          ? MessageModel.fromJson(json['lastMessage'])
          : null,
      unreadCount: json['unreadCount'] ?? 0,
      members: json['members'] != null
          ? (json['members'] as List)
              .map((e) => ChatMemberModel.fromJson(e))
              .toList()
          : [],
    );
  }

  /// 获取显示名称
  String get displayName {
    if (name != null && name!.isNotEmpty) {
      return name!;
    }
    if (members.isNotEmpty) {
      return members.first.nickname ?? members.first.username;
    }
    return '未知聊天';
  }

  /// 获取显示头像
  String? get displayAvatar {
    if (avatar != null && avatar!.isNotEmpty) {
      return avatar;
    }
    if (members.isNotEmpty) {
      return members.first.avatarUrl;
    }
    return null;
  }

  /// 是否为群聊
  bool get isGroup => type == ChatType.group;

  /// 获取最后消息时间的显示文本（参考微信设计）
  String get lastMessageTimeText {
    // 优先使用 lastMessageAt，如果为空则使用 lastMessage.createdAt
    final messageTime = lastMessageAt ?? lastMessage?.createdAt;
    if (messageTime == null) return '';

    final now = DateTime.now();
    final diff = now.difference(messageTime);

    // 判断是否是今天
    final isToday = now.year == messageTime.year &&
        now.month == messageTime.month &&
        now.day == messageTime.day;

    // 判断是否是昨天
    final yesterday = now.subtract(const Duration(days: 1));
    final isYesterday = yesterday.year == messageTime.year &&
        yesterday.month == messageTime.month &&
        yesterday.day == messageTime.day;

    // 格式化时间 HH:mm
    String formatTime(DateTime dt) {
      final hour = dt.hour.toString().padLeft(2, '0');
      final minute = dt.minute.toString().padLeft(2, '0');
      return '$hour:$minute';
    }

    if (isToday) {
      // 今天的消息
      if (diff.inMinutes < 1) {
        // 小于1分钟按1分钟算
        return '1分钟前';
      } else if (diff.inMinutes < 60) {
        // 1-59分钟
        return '${diff.inMinutes}分钟前';
      } else {
        // 超过1小时显示具体时间
        return formatTime(messageTime);
      }
    } else if (isYesterday) {
      // 昨天
      return '昨天 ${formatTime(messageTime)}';
    } else if (diff.inDays < 7) {
      // 一周内显示星期几
      const weekdays = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];
      return weekdays[messageTime.weekday - 1];
    } else if (now.year == messageTime.year) {
      // 今年内显示月/日
      return '${messageTime.month}月${messageTime.day}日';
    } else {
      // 跨年显示完整日期
      return '${messageTime.year}/${messageTime.month}/${messageTime.day}';
    }
  }
}

/// 消息模型
class MessageModel {
  final int id;
  final int chatId;
  final int senderId;
  final String? senderNickname;
  final String? senderAvatar;
  final String content;
  final MessageType messageType;
  final String? fileUrl;
  final DateTime? createdAt;
  final bool isRead;

  MessageModel({
    required this.id,
    required this.chatId,
    required this.senderId,
    this.senderNickname,
    this.senderAvatar,
    required this.content,
    this.messageType = MessageType.text,
    this.fileUrl,
    this.createdAt,
    this.isRead = false,
  });

  factory MessageModel.fromJson(Map<String, dynamic> json) {
    return MessageModel(
      id: json['id'] ?? 0,
      chatId: json['chatId'] ?? 0,
      senderId: json['senderId'] ?? 0,
      senderNickname: json['senderNickname'],
      senderAvatar: json['senderAvatar'],
      content: json['content'] ?? '',
      messageType: _parseMessageType(json['messageType']),
      fileUrl: json['fileUrl'],
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'])
          : null,
      isRead: json['isRead'] ?? false,
    );
  }

  static MessageType _parseMessageType(String? type) {
    switch (type) {
      case 'IMAGE':
        return MessageType.image;
      case 'VIDEO':
        return MessageType.video;
      case 'AUDIO':
        return MessageType.audio;
      case 'FILE':
        return MessageType.file;
      default:
        return MessageType.text;
    }
  }

  /// 获取消息预览文本
  String get previewText {
    switch (messageType) {
      case MessageType.image:
        return '[图片]';
      case MessageType.video:
        return '[视频]';
      case MessageType.audio:
        return '[语音]';
      case MessageType.file:
        return '[文件]';
      default:
        return content;
    }
  }
}

/// 聊天成员模型
class ChatMemberModel {
  final int id;
  final String username;
  final String? nickname;
  final String? avatarUrl;
  final bool isOnline;
  final DateTime? lastSeen;

  ChatMemberModel({
    required this.id,
    required this.username,
    this.nickname,
    this.avatarUrl,
    this.isOnline = false,
    this.lastSeen,
  });

  factory ChatMemberModel.fromJson(Map<String, dynamic> json) {
    return ChatMemberModel(
      id: json['id'] ?? 0,
      username: json['username'] ?? '',
      nickname: json['nickname'],
      avatarUrl: json['avatarUrl'],
      isOnline: json['isOnline'] ?? false,
      lastSeen: json['lastSeen'] != null
          ? DateTime.tryParse(json['lastSeen'])
          : null,
    );
  }
}
