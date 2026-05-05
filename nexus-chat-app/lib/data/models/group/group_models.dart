/// 群组成员角色
enum MemberRole { owner, admin, member }

/// 群组成员模型
class GroupMemberModel {
  final int id;
  final String username;
  final String? nickname;
  final String? avatarUrl;
  final bool isOnline;
  final MemberRole role;
  final bool isAdmin;
  final DateTime? joinedAt;
  final DateTime? lastSeen;

  GroupMemberModel({
    required this.id,
    required this.username,
    this.nickname,
    this.avatarUrl,
    this.isOnline = false,
    this.role = MemberRole.member,
    this.isAdmin = false,
    this.joinedAt,
    this.lastSeen,
  });

  factory GroupMemberModel.fromJson(Map<String, dynamic> json) {
    return GroupMemberModel(
      id: json['id'] ?? 0,
      username: json['username'] ?? '',
      nickname: json['nickname'],
      avatarUrl: json['avatarUrl'],
      isOnline: json['isOnline'] ?? false,
      role: _parseRole(json['role']),
      isAdmin: json['isAdmin'] ?? false,
      joinedAt: json['joinedAt'] != null
          ? DateTime.tryParse(json['joinedAt'])
          : null,
      lastSeen: json['lastSeen'] != null
          ? DateTime.tryParse(json['lastSeen'])
          : null,
    );
  }

  static MemberRole _parseRole(String? role) {
    switch (role?.toLowerCase()) {
      case 'owner':
        return MemberRole.owner;
      case 'admin':
        return MemberRole.admin;
      default:
        return MemberRole.member;
    }
  }

  /// 获取显示名称
  String get displayName => nickname ?? username;

  /// 是否为群主
  bool get isOwner => role == MemberRole.owner;
}

/// 群组详情模型
class GroupDetailModel {
  final int id;
  final String? name;
  final String? description;
  final String? avatar;
  final bool isPrivate;
  final int creatorId;
  final int memberCount;
  final List<GroupMemberModel> members;
  final String? lastMessage;
  final DateTime? lastMessageTime;
  final int unreadCount;
  final DateTime? createdAt;

  GroupDetailModel({
    required this.id,
    this.name,
    this.description,
    this.avatar,
    this.isPrivate = false,
    required this.creatorId,
    this.memberCount = 0,
    this.members = const [],
    this.lastMessage,
    this.lastMessageTime,
    this.unreadCount = 0,
    this.createdAt,
  });

  factory GroupDetailModel.fromJson(Map<String, dynamic> json) {
    return GroupDetailModel(
      id: json['id'] ?? 0,
      name: json['name'],
      description: json['description'],
      avatar: json['avatar'],
      isPrivate: json['isPrivate'] ?? false,
      creatorId: json['creatorId'] ?? 0,
      memberCount: json['memberCount'] ?? 0,
      members: json['members'] != null
          ? (json['members'] as List)
              .map((e) => GroupMemberModel.fromJson(e))
              .toList()
          : [],
      lastMessage: json['lastMessage'],
      lastMessageTime: json['lastMessageTime'] != null
          ? DateTime.tryParse(json['lastMessageTime'])
          : null,
      unreadCount: json['unreadCount'] ?? 0,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'])
          : null,
    );
  }

  /// 获取群主
  GroupMemberModel? get owner {
    try {
      return members.firstWhere((m) => m.isOwner);
    } catch (_) {
      return null;
    }
  }

  /// 获取管理员列表
  List<GroupMemberModel> get admins {
    return members.where((m) => m.isAdmin && !m.isOwner).toList();
  }

  /// 获取普通成员列表
  List<GroupMemberModel> get regularMembers {
    return members.where((m) => !m.isAdmin).toList();
  }
}
