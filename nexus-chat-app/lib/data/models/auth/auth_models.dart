/// 用户模型
class UserModel {
  final int id;
  final String username;
  final String? nickname;
  final String? email;
  final String? phone;
  final String? avatarUrl;
  final String? bio;
  final String? profileBackground;
  final bool isOnline;
  final DateTime? lastSeen;

  UserModel({
    required this.id,
    required this.username,
    this.nickname,
    this.email,
    this.phone,
    this.avatarUrl,
    this.bio,
    this.profileBackground,
    this.isOnline = false,
    this.lastSeen,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      id: json['userId'] ?? json['id'],
      username: json['username'] ?? '',
      nickname: json['nickname'],
      email: json['email'],
      phone: json['phone'],
      avatarUrl: json['avatarUrl'],
      bio: json['bio'],
      profileBackground: json['profileBackground'],
      isOnline: json['isOnline'] ?? false,
      lastSeen: json['lastSeen'] != null
          ? DateTime.tryParse(json['lastSeen'])
          : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'username': username,
      'nickname': nickname,
      'email': email,
      'phone': phone,
      'avatarUrl': avatarUrl,
      'bio': bio,
      'profileBackground': profileBackground,
      'isOnline': isOnline,
      'lastSeen': lastSeen?.toIso8601String(),
    };
  }

  /// 显示名称 (优先使用昵称)
  String get displayName => nickname ?? username;

  UserModel copyWith({
    int? id,
    String? username,
    String? nickname,
    String? email,
    String? phone,
    String? avatarUrl,
    String? bio,
    String? profileBackground,
    bool? isOnline,
    DateTime? lastSeen,
  }) {
    return UserModel(
      id: id ?? this.id,
      username: username ?? this.username,
      nickname: nickname ?? this.nickname,
      email: email ?? this.email,
      phone: phone ?? this.phone,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      bio: bio ?? this.bio,
      profileBackground: profileBackground ?? this.profileBackground,
      isOnline: isOnline ?? this.isOnline,
      lastSeen: lastSeen ?? this.lastSeen,
    );
  }
}

/// 认证响应模型
class AuthResponse {
  final String token;
  final UserModel user;

  AuthResponse({
    required this.token,
    required this.user,
  });

  factory AuthResponse.fromJson(Map<String, dynamic> json) {
    return AuthResponse(
      token: json['token'] ?? '',
      user: UserModel.fromJson(json),
    );
  }
}

/// 登录请求模型
class LoginRequest {
  final String usernameOrEmail;
  final String password;

  LoginRequest({
    required this.usernameOrEmail,
    required this.password,
  });

  Map<String, dynamic> toJson() {
    return {
      'usernameOrEmail': usernameOrEmail,
      'password': password,
    };
  }
}

/// 注册请求模型
class RegisterRequest {
  final String email;
  final String username;
  final String password;
  final String verificationCode;
  final String? nickname;

  RegisterRequest({
    required this.email,
    required this.username,
    required this.password,
    required this.verificationCode,
    this.nickname,
  });

  Map<String, dynamic> toJson() {
    return {
      'email': email,
      'username': username,
      'password': password,
      'verificationCode': verificationCode,
      if (nickname != null) 'nickname': nickname,
    };
  }
}

/// 发送验证码请求模型
class SendCodeRequest {
  final String email;
  final String type; // REGISTER, RESET_PASSWORD, CHANGE_EMAIL

  SendCodeRequest({
    required this.email,
    required this.type,
  });

  Map<String, dynamic> toJson() {
    return {
      'email': email,
      'type': type,
    };
  }
}
