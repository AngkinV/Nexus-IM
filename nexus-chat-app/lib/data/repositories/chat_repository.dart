import '../datasources/remote/chat_api_service.dart';
import '../models/chat/chat_models.dart';

/// 聊天仓库
class ChatRepository {
  final ChatApiService _chatApi = ChatApiService();

  /// 获取用户的聊天列表
  Future<List<ChatModel>> getUserChats(int userId) async {
    return await _chatApi.getUserChats(userId);
  }

  /// 获取聊天详情
  Future<ChatModel> getChatById(int chatId, int userId) async {
    return await _chatApi.getChatById(chatId, userId);
  }

  /// 创建私聊
  Future<ChatModel> createDirectChat(int userId, int contactId) async {
    return await _chatApi.createDirectChat(userId, contactId);
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
    return await _chatApi.createGroupChat(
      userId: userId,
      name: name,
      description: description,
      avatar: avatar,
      isPrivate: isPrivate,
      memberIds: memberIds,
    );
  }

  /// 获取聊天消息
  Future<List<MessageModel>> getChatMessages(int chatId, int userId, {int page = 0, int size = 50}) async {
    return await _chatApi.getChatMessages(chatId, userId, page: page, size: size);
  }

  /// 发送消息
  Future<MessageModel> sendMessage({
    required int chatId,
    required int senderId,
    required String content,
    String messageType = 'text',
    String? fileUrl,
  }) async {
    return await _chatApi.sendMessage(
      chatId: chatId,
      senderId: senderId,
      content: content,
      messageType: messageType,
      fileUrl: fileUrl,
    );
  }

  /// 标记消息已读
  Future<void> markMessageAsRead(int messageId, int userId) async {
    await _chatApi.markMessageAsRead(messageId, userId);
  }

  /// 标记聊天所有消息已读
  Future<void> markChatMessagesAsRead(int chatId, int userId) async {
    await _chatApi.markChatMessagesAsRead(chatId, userId);
  }
}
