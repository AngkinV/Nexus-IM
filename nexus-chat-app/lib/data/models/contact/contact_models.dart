/// 联系人模型
class ContactModel {
  final int id;
  final int userId;
  final String username;
  final String? nickname;
  final String? email;
  final String? phone;
  final String? avatarUrl;
  final bool isOnline;
  final DateTime? lastSeen;
  final DateTime? addedAt;

  ContactModel({
    required this.id,
    required this.userId,
    required this.username,
    this.nickname,
    this.email,
    this.phone,
    this.avatarUrl,
    this.isOnline = false,
    this.lastSeen,
    this.addedAt,
  });

  factory ContactModel.fromJson(Map<String, dynamic> json) {
    return ContactModel(
      id: json['id'] ?? 0,
      userId: json['userId'] ?? json['id'] ?? 0,
      username: json['username'] ?? '',
      nickname: json['nickname'],
      email: json['email'],
      phone: json['phone'],
      avatarUrl: json['avatarUrl'],
      isOnline: json['isOnline'] ?? false,
      lastSeen: json['lastSeen'] != null
          ? DateTime.tryParse(json['lastSeen'])
          : null,
      addedAt: json['addedAt'] != null
          ? DateTime.tryParse(json['addedAt'])
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'userId': userId,
      'username': username,
      'nickname': nickname,
      'email': email,
      'phone': phone,
      'avatarUrl': avatarUrl,
      'isOnline': isOnline,
      'lastSeen': lastSeen?.toIso8601String(),
      'addedAt': addedAt?.toIso8601String(),
    };
  }

  /// 显示名称 (优先使用昵称)
  String get displayName => nickname ?? username;

  /// 获取用于拼音排序的首字母
  String get sortLetter {
    final name = displayName;
    if (name.isEmpty) return '#';

    final firstChar = name[0].toUpperCase();
    // 检查是否为英文字母
    if (RegExp(r'[A-Z]').hasMatch(firstChar)) {
      return firstChar;
    }
    // 中文拼音映射（简化版，实际应用可使用 lpinyin 库）
    return _getChinesePinyinInitial(name[0]);
  }

  /// 获取中文拼音首字母（简化实现）
  String _getChinesePinyinInitial(String char) {
    // 常用汉字拼音首字母映射表（简化版）
    final pinyinMap = {
      '阿': 'A', '啊': 'A', '艾': 'A', '安': 'A', '奥': 'A',
      '白': 'B', '百': 'B', '包': 'B', '鲍': 'B', '北': 'B', '本': 'B',
      '陈': 'C', '程': 'C', '崔': 'C', '蔡': 'C', '曹': 'C', '常': 'C',
      '邓': 'D', '丁': 'D', '董': 'D', '杜': 'D', '戴': 'D', '段': 'D',
      '范': 'F', '方': 'F', '冯': 'F', '傅': 'F', '付': 'F', '樊': 'F',
      '高': 'G', '郭': 'G', '龚': 'G', '顾': 'G', '葛': 'G', '耿': 'G',
      '韩': 'H', '何': 'H', '胡': 'H', '黄': 'H', '侯': 'H', '贺': 'H',
      '贾': 'J', '江': 'J', '金': 'J', '姜': 'J', '蒋': 'J', '焦': 'J',
      '孔': 'K', '康': 'K', '柯': 'K', '匡': 'K',
      '李': 'L', '刘': 'L', '林': 'L', '梁': 'L', '吕': 'L', '罗': 'L', '雷': 'L', '陆': 'L', '卢': 'L',
      '马': 'M', '毛': 'M', '孟': 'M', '苗': 'M', '梅': 'M', '莫': 'M', '穆': 'M',
      '牛': 'N', '聂': 'N', '倪': 'N', '宁': 'N',
      '欧': 'O',
      '潘': 'P', '彭': 'P', '浦': 'P', '裴': 'P',
      '钱': 'Q', '秦': 'Q', '邱': 'Q', '乔': 'Q', '齐': 'Q',
      '任': 'R', '荣': 'R', '阮': 'R',
      '孙': 'S', '宋': 'S', '沈': 'S', '石': 'S', '施': 'S', '苏': 'S', '司': 'S', '史': 'S', '邵': 'S',
      '唐': 'T', '田': 'T', '谭': 'T', '陶': 'T', '汤': 'T', '童': 'T',
      '王': 'W', '吴': 'W', '魏': 'W', '文': 'W', '翁': 'W', '武': 'W', '万': 'W', '汪': 'W', '韦': 'W',
      '徐': 'X', '许': 'X', '谢': 'X', '夏': 'X', '肖': 'X', '萧': 'X', '熊': 'X', '薛': 'X', '辛': 'X', '邢': 'X', '晓': 'X', '小': 'X',
      '杨': 'Y', '叶': 'Y', '姚': 'Y', '尹': 'Y', '于': 'Y', '袁': 'Y', '余': 'Y', '俞': 'Y', '严': 'Y', '颜': 'Y', '燕': 'Y', '闫': 'Y',
      '张': 'Z', '赵': 'Z', '周': 'Z', '郑': 'Z', '朱': 'Z', '曾': 'Z', '钟': 'Z', '邹': 'Z', '庄': 'Z', '左': 'Z', '祝': 'Z',
    };

    return pinyinMap[char] ?? '#';
  }
}

/// 好友申请模型
class ContactRequestModel {
  final int id;
  final int fromUserId;
  final String? fromUsername;
  final String? fromNickname;
  final String? fromAvatarUrl;
  final bool fromIsOnline;
  final int toUserId;
  final String? toUsername;
  final String? toNickname;
  final String? toAvatarUrl;
  final String? message;
  final ContactRequestStatus status;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  ContactRequestModel({
    required this.id,
    required this.fromUserId,
    this.fromUsername,
    this.fromNickname,
    this.fromAvatarUrl,
    this.fromIsOnline = false,
    required this.toUserId,
    this.toUsername,
    this.toNickname,
    this.toAvatarUrl,
    this.message,
    this.status = ContactRequestStatus.pending,
    this.createdAt,
    this.updatedAt,
  });

  factory ContactRequestModel.fromJson(Map<String, dynamic> json) {
    return ContactRequestModel(
      id: json['id'] ?? 0,
      fromUserId: json['fromUserId'] ?? 0,
      fromUsername: json['fromUsername'],
      fromNickname: json['fromNickname'],
      fromAvatarUrl: json['fromAvatarUrl'],
      fromIsOnline: json['fromIsOnline'] ?? false,
      toUserId: json['toUserId'] ?? 0,
      toUsername: json['toUsername'],
      toNickname: json['toNickname'],
      toAvatarUrl: json['toAvatarUrl'],
      message: json['message'],
      status: ContactRequestStatus.fromString(json['status']),
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'])
          : null,
      updatedAt: json['updatedAt'] != null
          ? DateTime.tryParse(json['updatedAt'])
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'fromUserId': fromUserId,
      'fromUsername': fromUsername,
      'fromNickname': fromNickname,
      'fromAvatarUrl': fromAvatarUrl,
      'fromIsOnline': fromIsOnline,
      'toUserId': toUserId,
      'toUsername': toUsername,
      'toNickname': toNickname,
      'toAvatarUrl': toAvatarUrl,
      'message': message,
      'status': status.value,
      'createdAt': createdAt?.toIso8601String(),
      'updatedAt': updatedAt?.toIso8601String(),
    };
  }

  /// 获取发起者显示名称
  String get fromDisplayName => fromNickname ?? fromUsername ?? '未知用户';

  /// 获取接收者显示名称
  String get toDisplayName => toNickname ?? toUsername ?? '未知用户';
}

/// 好友申请状态枚举
enum ContactRequestStatus {
  pending('PENDING'),
  accepted('ACCEPTED'),
  rejected('REJECTED');

  final String value;
  const ContactRequestStatus(this.value);

  static ContactRequestStatus fromString(String? value) {
    switch (value?.toUpperCase()) {
      case 'ACCEPTED':
        return ContactRequestStatus.accepted;
      case 'REJECTED':
        return ContactRequestStatus.rejected;
      default:
        return ContactRequestStatus.pending;
    }
  }
}

/// 添加联系人请求
class AddContactRequest {
  final int userId;
  final int contactUserId;
  final String? message;

  AddContactRequest({
    required this.userId,
    required this.contactUserId,
    this.message,
  });

  Map<String, dynamic> toJson() {
    return {
      'userId': userId,
      'contactUserId': contactUserId,
      if (message != null) 'message': message,
    };
  }
}

/// 添加联系人响应
class AddContactResponse {
  final String type; // 'direct' 或 'request'
  final ContactModel? contact;
  final ContactRequestModel? request;

  AddContactResponse({
    required this.type,
    this.contact,
    this.request,
  });

  factory AddContactResponse.fromJson(Map<String, dynamic> json) {
    final type = json['type'] as String? ?? 'direct';
    final data = json['data'] as Map<String, dynamic>?;

    return AddContactResponse(
      type: type,
      contact: type == 'direct' && data != null
          ? ContactModel.fromJson(data)
          : null,
      request: type == 'request' && data != null
          ? ContactRequestModel.fromJson(data)
          : null,
    );
  }

  bool get isDirect => type == 'direct';
  bool get isRequest => type == 'request';
}

/// 联系人分组模型（按首字母分组）
class ContactGroup {
  final String letter;
  final List<ContactModel> contacts;

  ContactGroup({
    required this.letter,
    required this.contacts,
  });
}
