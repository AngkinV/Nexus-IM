import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/network/dio_client.dart';
import '../../models/group/group_models.dart';

/// 群组 API 服务
class GroupApiService {
  final DioClient _dioClient = DioClient();

  /// 获取群组详情
  Future<GroupDetailModel> getGroupById(int groupId) async {
    try {
      debugPrint('👥 获取群组详情: groupId=$groupId');
      final response = await _dioClient.get('/api/groups/$groupId');
      debugPrint('👥 群组详情响应: ${response.data}');
      return GroupDetailModel.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('👥 获取群组详情失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取群组成员列表
  Future<List<GroupMemberModel>> getGroupMembers(int groupId) async {
    try {
      debugPrint('👥 获取群组成员: groupId=$groupId');
      final response = await _dioClient.get('/api/groups/$groupId/members');

      if (response.data is List) {
        return (response.data as List)
            .map((e) => GroupMemberModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      return [];
    } on DioException catch (e) {
      debugPrint('👥 获取群组成员失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 更新群组信息
  Future<GroupDetailModel> updateGroup({
    required int groupId,
    required int userId,
    String? name,
    String? description,
    String? avatar,
    bool? isPrivate,
  }) async {
    try {
      debugPrint('👥 更新群组: groupId=$groupId');
      final response = await _dioClient.put(
        '/api/groups/$groupId',
        queryParameters: {'userId': userId},
        data: {
          if (name != null) 'name': name,
          if (description != null) 'description': description,
          if (avatar != null) 'avatar': avatar,
          if (isPrivate != null) 'isPrivate': isPrivate,
        },
      );
      return GroupDetailModel.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('👥 更新群组失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 退出群组
  Future<void> leaveGroup(int groupId, int userId) async {
    try {
      debugPrint('👥 退出群组: groupId=$groupId, userId=$userId');
      await _dioClient.post(
        '/api/groups/$groupId/leave',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('👥 退出群组失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 解散群组（仅群主）
  Future<void> deleteGroup(int groupId, int userId) async {
    try {
      debugPrint('👥 解散群组: groupId=$groupId, userId=$userId');
      await _dioClient.delete(
        '/api/groups/$groupId',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('👥 解散群组失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 添加群成员
  Future<void> addMembers(int groupId, int userId, List<int> userIds) async {
    try {
      debugPrint('👥 添加群成员: groupId=$groupId');
      await _dioClient.post(
        '/api/groups/$groupId/members',
        queryParameters: {'userId': userId},
        data: {'userIds': userIds},
      );
    } on DioException catch (e) {
      debugPrint('👥 添加群成员失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 移除群成员
  Future<void> removeMember(int groupId, int userId, int memberId) async {
    try {
      debugPrint('👥 移除群成员: groupId=$groupId, memberId=$memberId');
      await _dioClient.delete(
        '/api/groups/$groupId/members/$memberId',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('👥 移除群成员失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 设置/取消管理员
  Future<void> setAdmin(int groupId, int userId, int memberId, bool isAdmin) async {
    try {
      debugPrint('👥 设置管理员: groupId=$groupId, memberId=$memberId, isAdmin=$isAdmin');
      await _dioClient.put(
        '/api/groups/$groupId/members/$memberId/admin',
        queryParameters: {
          'userId': userId,
          'isAdmin': isAdmin,
        },
      );
    } on DioException catch (e) {
      debugPrint('👥 设置管理员失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 转让群主
  Future<void> transferOwnership(int groupId, int userId, int newOwnerId) async {
    try {
      debugPrint('👥 转让群主: groupId=$groupId, newOwnerId=$newOwnerId');
      await _dioClient.post(
        '/api/groups/$groupId/transfer',
        queryParameters: {
          'userId': userId,
          'newOwnerId': newOwnerId,
        },
      );
    } on DioException catch (e) {
      debugPrint('👥 转让群主失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 处理错误
  String _handleError(DioException error) {
    if (error.response != null) {
      final data = error.response?.data;
      if (data is Map && data.containsKey('message')) {
        return data['message'];
      }
      switch (error.response?.statusCode) {
        case 400:
          return '请求参数错误';
        case 401:
          return '未授权，请重新登录';
        case 403:
          return '没有权限执行此操作';
        case 404:
          return '群组不存在';
        case 500:
          return '服务器内部错误';
        default:
          return '请求失败';
      }
    }
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
        return '连接超时，请检查网络';
      case DioExceptionType.sendTimeout:
        return '发送超时，请稍后重试';
      case DioExceptionType.receiveTimeout:
        return '接收超时，请稍后重试';
      case DioExceptionType.connectionError:
        return '网络连接失败，请检查网络';
      default:
        return '网络错误，请稍后重试';
    }
  }
}
