import '../../../core/network/dio_client.dart';

/// 应用更新信息模型
class AppUpdateModel {
  final bool hasUpdate;
  final String? versionName;
  final int? versionCode;
  final String? downloadUrl;
  final String? updateLog;
  final int? fileSize;
  final bool forceUpdate;

  AppUpdateModel({
    required this.hasUpdate,
    this.versionName,
    this.versionCode,
    this.downloadUrl,
    this.updateLog,
    this.fileSize,
    this.forceUpdate = false,
  });

  factory AppUpdateModel.fromJson(Map<String, dynamic> json) {
    return AppUpdateModel(
      hasUpdate: json['hasUpdate'] ?? false,
      versionName: json['versionName'],
      versionCode: json['versionCode'],
      downloadUrl: json['downloadUrl'],
      updateLog: json['updateLog'],
      fileSize: json['fileSize'],
      forceUpdate: json['forceUpdate'] ?? false,
    );
  }

  String get fileSizeDisplay {
    if (fileSize == null) return '';
    final mb = fileSize! / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
  }
}

/// 用户统计数据模型
class UserStatsModel {
  final int followingCount;
  final int followerCount;
  final int postCount;

  UserStatsModel({
    required this.followingCount,
    required this.followerCount,
    required this.postCount,
  });

  factory UserStatsModel.fromJson(Map<String, dynamic> json) {
    return UserStatsModel(
      followingCount: json['followingCount'] ?? 0,
      followerCount: json['followerCount'] ?? 0,
      postCount: json['postCount'] ?? 0,
    );
  }
}

/// 用户 API 服务
class UserApiService {
  final DioClient _dioClient = DioClient();

  /// 获取用户统计数据（关注数、粉丝数、动态数）
  Future<UserStatsModel> getUserStats(int userId) async {
    final response = await _dioClient.get('/api/users/$userId/stats');
    return UserStatsModel.fromJson(response.data);
  }

  /// 关注用户
  Future<void> followUser(int targetUserId, int currentUserId) async {
    await _dioClient.post(
      '/api/users/$targetUserId/follow',
      queryParameters: {'followerId': currentUserId},
    );
  }

  /// 取消关注
  Future<void> unfollowUser(int targetUserId, int currentUserId) async {
    await _dioClient.delete(
      '/api/users/$targetUserId/follow',
      queryParameters: {'followerId': currentUserId},
    );
  }

  /// 查询关注状态
  Future<bool> isFollowing(int targetUserId, int currentUserId) async {
    final response = await _dioClient.get(
      '/api/users/$targetUserId/follow/status',
      queryParameters: {'followerId': currentUserId},
    );
    return response.data['isFollowing'] == true;
  }

  /// 检查应用更新
  Future<AppUpdateModel> checkUpdate(int currentVersionCode) async {
    final response = await _dioClient.get(
      '/api/app/check-update',
      queryParameters: {'currentVersionCode': currentVersionCode},
    );
    return AppUpdateModel.fromJson(response.data);
  }
}
