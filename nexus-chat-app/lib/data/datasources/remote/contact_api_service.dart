import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/config/api_config.dart';
import '../../../core/network/dio_client.dart';
import '../../models/contact/contact_models.dart';

/// 联系人 API 服务
class ContactApiService {
  final DioClient _dioClient = DioClient();

  /// 获取联系人列表（详细信息）
  Future<List<ContactModel>> getContacts(int userId) async {
    try {
      debugPrint('👥 获取联系人列表: userId=$userId');
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/user/$userId/detailed',
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => ContactModel.fromJson(json)).toList();
    } on DioException catch (e) {
      debugPrint('👥 获取联系人失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 添加联系人（或发送好友申请）
  Future<AddContactResponse> addContact(AddContactRequest request) async {
    try {
      debugPrint('👥 添加联系人: ${request.toJson()}');
      final response = await _dioClient.post(
        ApiConfig.contacts,
        data: request.toJson(),
      );

      return AddContactResponse.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('👥 添加联系人失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 删除联系人
  Future<void> removeContact(int userId, int contactUserId) async {
    try {
      debugPrint('👥 删除联系人: userId=$userId, contactUserId=$contactUserId');
      await _dioClient.delete(
        ApiConfig.contacts,
        data: {
          'userId': userId,
          'contactUserId': contactUserId,
        },
      );
    } on DioException catch (e) {
      debugPrint('👥 删除联系人失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 检查是否为联系人
  Future<bool> isContact(int userId, int contactUserId) async {
    try {
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/check',
        queryParameters: {
          'userId': userId,
          'contactUserId': contactUserId,
        },
      );

      return response.data['isContact'] == true;
    } on DioException catch (e) {
      debugPrint('👥 检查联系人失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取待处理的好友申请
  Future<List<ContactRequestModel>> getPendingRequests(int userId) async {
    try {
      debugPrint('👥 获取待处理好友申请: userId=$userId');
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/requests/pending/$userId',
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => ContactRequestModel.fromJson(json)).toList();
    } on DioException catch (e) {
      debugPrint('👥 获取好友申请失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取已发送的好友申请
  Future<List<ContactRequestModel>> getSentRequests(int userId) async {
    try {
      debugPrint('👥 获取已发送好友申请: userId=$userId');
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/requests/sent/$userId',
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => ContactRequestModel.fromJson(json)).toList();
    } on DioException catch (e) {
      debugPrint('👥 获取已发送申请失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取待处理申请数量
  Future<int> getPendingRequestCount(int userId) async {
    try {
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/requests/count/$userId',
      );

      return response.data['count'] ?? 0;
    } on DioException catch (e) {
      debugPrint('👥 获取申请数量失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 接受好友申请
  Future<ContactModel> acceptRequest(int requestId, int userId) async {
    try {
      debugPrint('👥 接受好友申请: requestId=$requestId, userId=$userId');
      final response = await _dioClient.post(
        '${ApiConfig.contacts}/requests/$requestId/accept',
        queryParameters: {'userId': userId},
      );

      return ContactModel.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('👥 接受申请失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 拒绝好友申请
  Future<void> rejectRequest(int requestId, int userId) async {
    try {
      debugPrint('👥 拒绝好友申请: requestId=$requestId, userId=$userId');
      await _dioClient.post(
        '${ApiConfig.contacts}/requests/$requestId/reject',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('👥 拒绝申请失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取共同好友
  Future<List<ContactModel>> getMutualContacts(int userId1, int userId2) async {
    try {
      final response = await _dioClient.get(
        '${ApiConfig.contacts}/mutual',
        queryParameters: {
          'userId1': userId1,
          'userId2': userId2,
        },
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => ContactModel.fromJson(json)).toList();
    } on DioException catch (e) {
      debugPrint('👥 获取共同好友失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 搜索用户
  Future<List<Map<String, dynamic>>> searchUsers(String query) async {
    try {
      debugPrint('👥 搜索用户: query=$query');
      final response = await _dioClient.get(
        '/api/users/search',
        queryParameters: {'query': query},
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => json as Map<String, dynamic>).toList();
    } on DioException catch (e) {
      debugPrint('👥 搜索用户失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取随机推荐用户
  Future<List<Map<String, dynamic>>> getRandomUsers(int userId, {int limit = 4}) async {
    try {
      debugPrint('👥 获取随机推荐用户: userId=$userId, limit=$limit');
      final response = await _dioClient.get(
        '/api/users/recommended',
        queryParameters: {
          'userId': userId,
          'limit': limit,
        },
      );

      final List<dynamic> data = response.data ?? [];
      return data.map((json) => json as Map<String, dynamic>).toList();
    } on DioException catch (e) {
      debugPrint('👥 获取随机用户失败: ${e.message}');
      // 如果接口不存在，返回空列表
      return [];
    }
  }

  /// 处理错误
  String _handleError(DioException error) {
    if (error.response != null) {
      final data = error.response?.data;
      if (data is Map) {
        if (data.containsKey('error')) {
          return data['error'];
        }
        if (data.containsKey('message')) {
          return data['message'];
        }
      }
      switch (error.response?.statusCode) {
        case 400:
          return '请求参数错误';
        case 401:
          return '未授权，请重新登录';
        case 403:
          return '禁止访问';
        case 404:
          return '资源不存在';
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
