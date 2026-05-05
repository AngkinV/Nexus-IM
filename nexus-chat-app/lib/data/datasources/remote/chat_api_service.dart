import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/config/api_config.dart';
import '../../../core/network/dio_client.dart';
import '../../models/chat/chat_models.dart';

/// 聊天 API 服务
class ChatApiService {
  final DioClient _dioClient = DioClient();

  /// 获取用户的聊天列表
  Future<List<ChatModel>> getUserChats(int userId) async {
    try {
      debugPrint('💬 获取聊天列表: userId=$userId');

      final response = await _dioClient.get(
        '${ApiConfig.chats}/user/$userId',
      );

      debugPrint('💬 聊天列表响应: ${response.data}');

      if (response.data is List) {
        return (response.data as List)
            .map((e) => ChatModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }

      return [];
    } on DioException catch (e) {
      debugPrint('💬 获取聊天列表失败: ${e.message}');
      debugPrint('💬 响应状态码: ${e.response?.statusCode}');
      debugPrint('💬 响应数据: ${e.response?.data}');
      throw _handleError(e);
    }
  }

  /// 获取聊天详情
  Future<ChatModel> getChatById(int chatId, int userId) async {
    try {
      final response = await _dioClient.get(
        '${ApiConfig.chats}/$chatId',
        queryParameters: {'userId': userId},
      );

      return ChatModel.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 创建私聊
  Future<ChatModel> createDirectChat(int userId, int contactId) async {
    try {
      final response = await _dioClient.post(
        '${ApiConfig.chats}/direct',
        queryParameters: {
          'userId': userId,
          'contactId': contactId,
        },
      );

      return ChatModel.fromJson(response.data);
    } on DioException catch (e) {
      throw _handleError(e);
    }
  }

  /// 创建群聊
  Future<ChatModel> createGroupChat({
    required int userId,
    required String name,
    String? description,
    String? avatar,
    bool isPrivate = false,
    required List<int> memberIds,
  }) async {
    try {
      debugPrint('💬 创建群聊: userId=$userId, name=$name');
      final response = await _dioClient.post(
        '${ApiConfig.chats}/group',
        queryParameters: {'userId': userId},
        data: {
          'name': name,
          'description': description,
          'avatar': avatar,
          'isPrivate': isPrivate,
          'memberIds': memberIds,
        },
      );

      return ChatModel.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('💬 创建群聊失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 获取聊天消息
  Future<List<MessageModel>> getChatMessages(int chatId, int userId, {int page = 0, int size = 50}) async {
    try {
      debugPrint('💬 获取消息: chatId=$chatId, userId=$userId');
      final response = await _dioClient.get(
        '/api/messages/chat/$chatId',
        queryParameters: {
          'userId': userId,
          'page': page,
          'size': size,
        },
      );

      if (response.data is List) {
        return (response.data as List)
            .map((e) => MessageModel.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      return [];
    } on DioException catch (e) {
      debugPrint('💬 获取消息失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 发送消息
  Future<MessageModel> sendMessage({
    required int chatId,
    required int senderId,
    required String content,
    String messageType = 'text',
    String? fileUrl,
  }) async {
    try {
      debugPrint('💬 发送消息: chatId=$chatId, senderId=$senderId');
      final response = await _dioClient.post(
        '/api/messages',
        data: {
          'chatId': chatId,
          'senderId': senderId,
          'content': content,
          'messageType': messageType,
          'fileUrl': fileUrl,
        },
      );

      return MessageModel.fromJson(response.data);
    } on DioException catch (e) {
      debugPrint('💬 发送消息失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 标记消息已读
  Future<void> markMessageAsRead(int messageId, int userId) async {
    try {
      await _dioClient.put(
        '/api/messages/$messageId/read',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('💬 标记已读失败: ${e.message}');
      throw _handleError(e);
    }
  }

  /// 标记聊天所有消息已读
  Future<void> markChatMessagesAsRead(int chatId, int userId) async {
    try {
      await _dioClient.put(
        '/api/messages/chat/$chatId/read',
        queryParameters: {'userId': userId},
      );
    } on DioException catch (e) {
      debugPrint('💬 标记聊天已读失败: ${e.message}');
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
