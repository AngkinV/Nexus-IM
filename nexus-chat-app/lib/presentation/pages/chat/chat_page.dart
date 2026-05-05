import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/network/message_service.dart';
import '../../../data/models/chat/chat_models.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/chat_repository.dart';
import '../group/group_settings_page.dart';
import '../user/user_profile_page.dart';

/// 聊天页面
class ChatPage extends StatefulWidget {
  final ChatModel chat;

  const ChatPage({super.key, required this.chat});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  final AuthRepository _authRepository = AuthRepository();
  final ChatRepository _chatRepository = ChatRepository();
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final MessageService _messageService = MessageService();

  List<MessageModel> _messages = [];
  bool _isLoading = true;
  bool _isSending = false;
  int? _currentUserId;

  StreamSubscription<int>? _messageSubscription;

  // 判断是否为群聊
  bool get _isGroup => widget.chat.isGroup;

  @override
  void initState() {
    super.initState();
    _loadData();
    _setupMessageListener();
    // 设置当前活跃聊天，用于通知判断
    _messageService.setActiveChatId(widget.chat.id);
  }

  @override
  void dispose() {
    // 清除活跃聊天
    _messageService.setActiveChatId(null);
    _messageController.dispose();
    _scrollController.dispose();
    _messageSubscription?.cancel();
    super.dispose();
  }

  void _setupMessageListener() {
    _messageSubscription = _messageService.messageUpdateStream.listen((chatId) {
      if (chatId == widget.chat.id) {
        debugPrint('📨 ChatPage: 收到消息更新通知');
        _loadNewMessages();
      }
    });
  }

  /// 加载新消息（WebSocket 触发）
  Future<void> _loadNewMessages() async {
    if (_currentUserId == null) return;

    try {
      final messages = await _chatRepository.getChatMessages(
        widget.chat.id,
        _currentUserId!,
      );

      messages.sort((a, b) {
        final aTime = a.createdAt ?? DateTime(1970);
        final bTime = b.createdAt ?? DateTime(1970);
        return aTime.compareTo(bTime);
      });

      bool hasNew = false;
      for (final msg in messages) {
        if (!_messages.any((m) => m.id == msg.id)) {
          _messages.add(msg);
          hasNew = true;
        }
      }

      if (hasNew && mounted) {
        setState(() {});
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _scrollToBottom();
        });
        // 标记消息已读
        await _chatRepository.markChatMessagesAsRead(widget.chat.id, _currentUserId!);
      }
    } catch (e) {
      debugPrint('📨 ChatPage: 加载新消息失败 $e');
    }
  }

  Future<void> _loadData() async {
    final userId = await _authRepository.getCurrentUserId();
    if (userId == null) {
      setState(() => _isLoading = false);
      return;
    }

    _currentUserId = userId;
    await _loadMessages();
  }

  Future<void> _loadMessages() async {
    if (_currentUserId == null) return;

    try {
      final messages = await _chatRepository.getChatMessages(
        widget.chat.id,
        _currentUserId!,
      );

      messages.sort((a, b) {
        final aTime = a.createdAt ?? DateTime(1970);
        final bTime = b.createdAt ?? DateTime(1970);
        return aTime.compareTo(bTime);
      });

      setState(() {
        _messages = messages;
        _isLoading = false;
      });

      WidgetsBinding.instance.addPostFrameCallback((_) {
        _scrollToBottom();
      });

      await _chatRepository.markChatMessagesAsRead(widget.chat.id, _currentUserId!);
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载失败: $e')),
        );
      }
    }
  }

  void _scrollToBottom() {
    if (_scrollController.hasClients) {
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
    }
  }

  Future<void> _sendMessage() async {
    if (_currentUserId == null) return;

    final content = _messageController.text.trim();
    if (content.isEmpty) return;

    setState(() => _isSending = true);
    _messageController.clear();

    try {
      final message = await _chatRepository.sendMessage(
        chatId: widget.chat.id,
        senderId: _currentUserId!,
        content: content,
      );

      setState(() {
        if (!_messages.any((m) => m.id == message.id)) {
          _messages.add(message);
        }
        _isSending = false;
      });

      WidgetsBinding.instance.addPostFrameCallback((_) {
        _scrollToBottom();
      });

      _messageService.notifyNewMessage(widget.chat.id);
    } catch (e) {
      setState(() => _isSending = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('发送失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0A0A0A) : Colors.white,
      body: Column(
        children: [
          // 头部 - 根据是否群聊使用不同样式
          _isGroup ? _buildGroupHeader(context, isDark) : _buildPrivateHeader(context, isDark),

          // 消息列表
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _buildMessageList(isDark),
          ),

          // 输入区域
          _buildInputArea(isDark),
        ],
      ),
    );
  }

  // ==================== 群聊头部 ====================
  Widget _buildGroupHeader(BuildContext context, bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          color: isDark
              ? const Color(0xFF0A0A0A).withValues(alpha: 0.8)
              : Colors.white.withValues(alpha: 0.8),
          child: SafeArea(
            bottom: false,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark ? Colors.grey[800]! : Colors.grey.shade100,
                  ),
                ),
              ),
              child: Row(
                children: [
                  // 左侧：返回按钮 + 群名称
                  Expanded(
                    child: Row(
                      children: [
                        // 返回按钮
                        GestureDetector(
                          onTap: () => Navigator.pop(context),
                          child: Padding(
                            padding: const EdgeInsets.only(right: 8),
                            child: Icon(
                              Icons.arrow_back_ios_new,
                              color: AppTheme.primary,
                              size: 24,
                            ),
                          ),
                        ),
                        // 群名称和成员数（无头像）
                        Expanded(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                widget.chat.displayName,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                  fontSize: 16,
                                  height: 1.2,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                              const SizedBox(height: 2),
                              Text(
                                '${widget.chat.memberCount} 位成员',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w500,
                                  color: Colors.grey[500],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),

                  // 右侧：搜索 + 更多（无语音视频按钮）
                  Row(
                    children: [
                      GestureDetector(
                        onTap: () {
                          // TODO: 搜索群消息
                        },
                        child: Padding(
                          padding: const EdgeInsets.all(8),
                          child: Icon(
                            Icons.search,
                            color: isDark ? Colors.grey[400] : Colors.grey[500],
                            size: 24,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      GestureDetector(
                        onTap: () {
                          // 跳转到群组设置页面
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => GroupSettingsPage(
                                groupId: widget.chat.id,
                                groupName: widget.chat.displayName,
                              ),
                            ),
                          );
                        },
                        child: Padding(
                          padding: const EdgeInsets.all(8),
                          child: Icon(
                            Icons.more_horiz,
                            color: isDark ? Colors.grey[400] : Colors.grey[500],
                            size: 24,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  // ==================== 私聊头部 ====================
  Widget _buildPrivateHeader(BuildContext context, bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          color: isDark
              ? Theme.of(context).scaffoldBackgroundColor.withValues(alpha: 0.8)
              : Colors.white.withValues(alpha: 0.8),
          child: SafeArea(
            bottom: false,
            child: Container(
              height: 56,
              padding: const EdgeInsets.symmetric(horizontal: 8),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark ? Colors.white.withValues(alpha: 0.1) : Colors.grey.shade200,
                  ),
                ),
              ),
              child: Row(
                children: [
                  // 返回按钮
                  IconButton(
                    onPressed: () => Navigator.pop(context),
                    icon: Icon(
                      Icons.chevron_left,
                      color: AppTheme.primary,
                      size: 28,
                    ),
                  ),

                  // 头像
                  _buildChatAvatar(),

                  const SizedBox(width: 12),

                  // 名称和状态
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.chat.displayName,
                          style: const TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (widget.chat.members.isNotEmpty)
                          Text(
                            widget.chat.members.first.isOnline ? '在线' : '离线',
                            style: TextStyle(
                              fontSize: 12,
                              color: widget.chat.members.first.isOnline
                                  ? AppTheme.primary
                                  : Colors.grey,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                      ],
                    ),
                  ),

                  // 私聊操作按钮
                  IconButton(
                    onPressed: () {
                      // TODO: 语音通话
                    },
                    icon: Icon(
                      Icons.call,
                      color: isDark ? Colors.grey[300] : Colors.grey[600],
                    ),
                  ),
                  IconButton(
                    onPressed: () {
                      // TODO: 视频通话
                    },
                    icon: Icon(
                      Icons.videocam,
                      color: isDark ? Colors.grey[300] : Colors.grey[600],
                    ),
                  ),
                  // 更多选项按钮
                  IconButton(
                    onPressed: () {
                      // 跳转到用户资料页面
                      if (widget.chat.members.isNotEmpty) {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (context) => UserProfilePage(
                              user: widget.chat.members.first,
                              chatId: widget.chat.id,
                            ),
                          ),
                        );
                      }
                    },
                    icon: Icon(
                      Icons.more_vert,
                      color: isDark ? Colors.grey[300] : Colors.grey[600],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildChatAvatar() {
    final displayName = widget.chat.displayName;
    final fullAvatarUrl = ApiConfig.getFullUrl(widget.chat.displayAvatar);

    return Stack(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            color: AppTheme.primary,
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: fullAvatarUrl.isNotEmpty
                ? CachedNetworkImage(
                    imageUrl: fullAvatarUrl,
                    fit: BoxFit.cover,
                    placeholder: (_, __) => _buildDefaultAvatar(displayName),
                    errorWidget: (_, __, ___) => _buildDefaultAvatar(displayName),
                  )
                : _buildDefaultAvatar(displayName),
          ),
        ),
        // 在线状态指示器
        if (widget.chat.members.isNotEmpty && widget.chat.members.first.isOnline)
          Positioned(
            bottom: 0,
            right: 0,
            child: Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                color: AppTheme.primary,
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white, width: 2),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildDefaultAvatar(String name) {
    return Center(
      child: Text(
        name.isNotEmpty ? name[0].toUpperCase() : '?',
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.bold,
          fontSize: 18,
        ),
      ),
    );
  }

  // ==================== 消息列表 ====================
  Widget _buildMessageList(bool isDark) {
    if (_messages.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.chat_bubble_outline, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '暂无消息',
              style: TextStyle(color: Colors.grey[500], fontSize: 16),
            ),
            const SizedBox(height: 8),
            Text(
              '发送消息开始聊天吧',
              style: TextStyle(color: Colors.grey[400], fontSize: 14),
            ),
          ],
        ),
      );
    }

    return ListView.builder(
      controller: _scrollController,
      padding: EdgeInsets.symmetric(
        horizontal: 16,
        vertical: _isGroup ? 24 : 12,
      ),
      itemCount: _messages.length,
      itemBuilder: (context, index) {
        final message = _messages[index];
        final isMine = message.senderId == _currentUserId;

        final showDateDivider = index == 0 ||
            !_isSameDay(_messages[index - 1].createdAt, message.createdAt);

        return Column(
          children: [
            if (showDateDivider)
              _isGroup
                  ? _buildGroupDateDivider(message.createdAt, isDark)
                  : _buildPrivateDateDivider(message.createdAt, isDark),
            _isGroup
                ? _buildGroupMessageBubble(message, isMine, isDark)
                : _buildPrivateMessageBubble(message, isMine, isDark),
          ],
        );
      },
    );
  }

  bool _isSameDay(DateTime? a, DateTime? b) {
    if (a == null || b == null) return false;
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }

  // ==================== 群聊日期分隔符 ====================
  Widget _buildGroupDateDivider(DateTime? dateTime, bool isDark) {
    if (dateTime == null) return const SizedBox.shrink();

    final now = DateTime.now();
    String dateText;
    final timeText = _formatTime(dateTime);

    if (_isSameDay(dateTime, now)) {
      dateText = '今天';
    } else if (_isSameDay(dateTime, now.subtract(const Duration(days: 1)))) {
      dateText = '昨天';
    } else {
      dateText = '${dateTime.month}月${dateTime.day}日';
    }

    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 20),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1F2937) : const Color(0xFFF3F4F6),
            borderRadius: BorderRadius.circular(12), // 更圆润的药丸形状
          ),
          child: Text(
            '$dateText $timeText',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w400,
              color: isDark ? const Color(0xFF6B7280) : const Color(0xFF9CA3AF),
            ),
          ),
        ),
      ),
    );
  }

  // ==================== 私聊日期分隔符 ====================
  Widget _buildPrivateDateDivider(DateTime? dateTime, bool isDark) {
    if (dateTime == null) return const SizedBox.shrink();

    final now = DateTime.now();
    String text;

    if (_isSameDay(dateTime, now)) {
      text = '今天';
    } else if (_isSameDay(dateTime, now.subtract(const Duration(days: 1)))) {
      text = '昨天';
    } else {
      text = '${dateTime.month}月${dateTime.day}日';
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey[100],
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          text,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: isDark ? Colors.grey[400] : Colors.grey[500],
            letterSpacing: 0.5,
          ),
        ),
      ),
    );
  }

  // ==================== 群聊消息气泡 ====================
  Widget _buildGroupMessageBubble(MessageModel message, bool isMine, bool isDark) {
    // 判断是否为管理员 (简单判断：可以根据后端返回的角色判断)
    final isAdmin = message.senderNickname?.contains('管理员') ?? false;

    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: isMine
          ? Align(
              alignment: Alignment.centerRight,
              child: _buildGroupSentMessage(message, isDark),
            )
          : Align(
              alignment: Alignment.centerLeft,
              child: _buildGroupReceivedMessage(message, isDark, isAdmin),
            ),
    );
  }

  Widget _buildGroupSentMessage(MessageModel message, bool isDark) {
    // 模拟已读人数（实际应从后端获取 readCount）
    final readCount = message.isRead ? (widget.chat.memberCount > 1 ? widget.chat.memberCount - 1 : 1) : 0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        // 消息气泡 - 渐变背景，rounded-tr-none
        Container(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.75,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Color(0xFF10B981), // #10B981
                Color(0xFF059669), // #059669
              ],
            ),
            borderRadius: const BorderRadius.only(
              topLeft: Radius.circular(16),
              topRight: Radius.circular(4), // rounded-tr-none 效果
              bottomLeft: Radius.circular(16),
              bottomRight: Radius.circular(16),
            ),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFF10B981).withValues(alpha: 0.1),
                blurRadius: 8,
                offset: const Offset(0, 2),
              ),
            ],
          ),
          child: Text(
            message.content,
            style: const TextStyle(
              fontSize: 15,
              height: 1.4,
              color: Colors.white,
            ),
          ),
        ),

        const SizedBox(height: 4),

        // 已读状态 - 群聊显示已读人数
        Padding(
          padding: const EdgeInsets.only(right: 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                message.isRead ? '$readCount人已读' : '已发送',
                style: TextStyle(
                  fontSize: 10,
                  color: isDark ? Colors.grey[600] : Colors.grey[400],
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                Icons.done_all,
                size: 14,
                color: message.isRead ? AppTheme.primary : (isDark ? Colors.grey[600] : Colors.grey[400]),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildGroupReceivedMessage(MessageModel message, bool isDark, bool isAdmin) {
    // 生成头像背景色
    final avatarColor = _getAvatarColor(message.senderNickname ?? '');
    final fullAvatarUrl = ApiConfig.getFullUrl(message.senderAvatar);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 头像 - 与名称行对齐
        Container(
          margin: const EdgeInsets.only(top: 20), // 对齐发送者名称
          width: 32, // w-8 = 32px
          height: 32, // h-8 = 32px
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: isAdmin
                ? const Color(0xFFD1FAE5) // emerald-100
                : avatarColor.withValues(alpha: 0.15),
          ),
          child: ClipOval(
            child: fullAvatarUrl.isNotEmpty
                ? CachedNetworkImage(
                    imageUrl: fullAvatarUrl,
                    fit: BoxFit.cover,
                    placeholder: (_, __) => Center(
                      child: Text(
                        (message.senderNickname ?? '?')[0].toUpperCase(),
                        style: TextStyle(
                          color: isAdmin ? const Color(0xFF047857) : avatarColor,
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                        ),
                      ),
                    ),
                    errorWidget: (_, __, ___) => Center(
                      child: Text(
                        (message.senderNickname ?? '?')[0].toUpperCase(),
                        style: TextStyle(
                          color: isAdmin ? const Color(0xFF047857) : avatarColor,
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  )
                : Center(
                    child: Text(
                      (message.senderNickname ?? '?')[0].toUpperCase(),
                      style: TextStyle(
                        color: isAdmin ? const Color(0xFF047857) : avatarColor,
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                      ),
                    ),
                  ),
          ),
        ),

        const SizedBox(width: 10), // space-x-2.5 = 10px

        // 消息内容区域
        Flexible(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              // 发送者名称
              Padding(
                padding: const EdgeInsets.only(left: 4, bottom: 4),
                child: Text(
                  isAdmin
                      ? '管理员 · ${message.senderNickname ?? '未知'}'
                      : message.senderNickname ?? '未知',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                    color: isAdmin ? const Color(0xFF059669) : Colors.grey[400],
                  ),
                ),
              ),

              // 消息气泡
              Container(
                constraints: BoxConstraints(
                  maxWidth: MediaQuery.of(context).size.width * 0.65,
                ),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  color: isDark ? const Color(0xFF1F2937) : const Color(0xFFF3F4F6),
                  borderRadius: const BorderRadius.only(
                    topLeft: Radius.circular(4), // rounded-tl-none 效果
                    topRight: Radius.circular(16),
                    bottomLeft: Radius.circular(16),
                    bottomRight: Radius.circular(16),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.04),
                      blurRadius: 4,
                      offset: const Offset(0, 1),
                    ),
                  ],
                ),
                child: Text(
                  message.content,
                  style: TextStyle(
                    fontSize: 15,
                    height: 1.5,
                    color: isDark ? const Color(0xFFE5E7EB) : const Color(0xFF1F2937),
                  ),
                ),
              ),

              // 时间
              Padding(
                padding: const EdgeInsets.only(left: 4, top: 4),
                child: Text(
                  _formatTime(message.createdAt),
                  style: TextStyle(
                    fontSize: 10,
                    color: isDark ? Colors.grey[600] : Colors.grey[400],
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Color _getAvatarColor(String name) {
    if (name.isEmpty) return Colors.blue;
    final colors = [
      Colors.blue,
      Colors.green,
      Colors.orange,
      Colors.purple,
      Colors.teal,
      Colors.pink,
      Colors.indigo,
      Colors.amber,
    ];
    return colors[name.codeUnitAt(0) % colors.length];
  }

  // ==================== 私聊消息气泡 ====================
  Widget _buildPrivateMessageBubble(MessageModel message, bool isMine, bool isDark) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Flexible(
                child: Container(
                  constraints: BoxConstraints(
                    maxWidth: MediaQuery.of(context).size.width * 0.75,
                  ),
                  child: _buildPrivateMessageContent(message, isMine, isDark),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          // 时间和已读状态
          Row(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
            children: [
              Text(
                _formatTime(message.createdAt),
                style: TextStyle(
                  fontSize: 10,
                  color: isDark ? Colors.grey[500] : Colors.grey[400],
                ),
              ),
              if (isMine) ...[
                const SizedBox(width: 4),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      message.isRead ? '已读' : '已发送',
                      style: TextStyle(
                        fontSize: 10,
                        color: message.isRead ? AppTheme.primary : Colors.grey[500],
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(width: 2),
                    Icon(
                      message.isRead ? Icons.done_all : Icons.done,
                      size: 14,
                      color: message.isRead ? AppTheme.primary : Colors.grey[500],
                    ),
                  ],
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildPrivateMessageContent(MessageModel message, bool isMine, bool isDark) {
    if (message.messageType == MessageType.image && message.fileUrl != null) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: Image.network(
          message.fileUrl!,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.grey[300],
              borderRadius: BorderRadius.circular(16),
            ),
            child: const Icon(Icons.image_not_supported),
          ),
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: isMine
            ? AppTheme.primary
            : (isDark ? Colors.white.withValues(alpha: 0.1) : Colors.grey[200]),
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(16),
          topRight: const Radius.circular(16),
          bottomLeft: Radius.circular(isMine ? 16 : 4),
          bottomRight: Radius.circular(isMine ? 4 : 16),
        ),
      ),
      child: Text(
        message.content,
        style: TextStyle(
          fontSize: 14,
          height: 1.4,
          color: isMine
              ? Colors.black87
              : (isDark ? Colors.white : Colors.grey[800]),
          fontWeight: isMine ? FontWeight.w500 : FontWeight.normal,
        ),
      ),
    );
  }

  // ==================== 输入区域 ====================
  Widget _buildInputArea(bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          padding: EdgeInsets.only(
            left: 16,
            right: 16,
            top: 12,
            bottom: MediaQuery.of(context).padding.bottom + 32,
          ),
          decoration: BoxDecoration(
            color: isDark
                ? const Color(0xFF0A0A0A).withValues(alpha: 0.8)
                : Colors.white.withValues(alpha: 0.8),
            border: Border(
              top: BorderSide(
                color: isDark ? const Color(0xFF1F2937) : const Color(0xFFF3F4F6),
              ),
            ),
          ),
          child: Row(
            children: [
              // 表情按钮
              GestureDetector(
                onTap: () {
                  // TODO: 表情选择器
                },
                child: Icon(
                  Icons.sentiment_satisfied_outlined,
                  color: isDark ? const Color(0xFF6B7280) : const Color(0xFF9CA3AF),
                  size: 26,
                ),
              ),

              const SizedBox(width: 12),

              // 输入框
              Expanded(
                child: Container(
                  decoration: BoxDecoration(
                    color: isDark ? const Color(0xFF1F2937) : const Color(0xFFF3F4F6),
                    borderRadius: BorderRadius.circular(22),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _messageController,
                          decoration: InputDecoration(
                            hintText: '请输入消息...',
                            hintStyle: TextStyle(
                              color: isDark ? const Color(0xFF6B7280) : const Color(0xFF9CA3AF),
                              fontSize: 15,
                            ),
                            border: InputBorder.none,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 10,
                            ),
                          ),
                          style: TextStyle(
                            color: isDark ? Colors.white : const Color(0xFF1F2937),
                            fontSize: 15,
                          ),
                          maxLines: 4,
                          minLines: 1,
                          onSubmitted: (_) => _sendMessage(),
                        ),
                      ),
                      GestureDetector(
                        onTap: () {
                          // TODO: 附件选择器
                        },
                        child: Padding(
                          padding: const EdgeInsets.only(right: 12),
                          child: Icon(
                            Icons.attach_file,
                            color: isDark ? const Color(0xFF6B7280) : const Color(0xFF9CA3AF),
                            size: 22,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              const SizedBox(width: 12),

              // 发送按钮
              GestureDetector(
                onTap: _isSending ? null : _sendMessage,
                child: Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: AppTheme.primary,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: AppTheme.primary.withValues(alpha: 0.25),
                        blurRadius: 12,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Center(
                    child: _isSending
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : Transform.rotate(
                            angle: -0.52, // 约 -30度
                            child: const Padding(
                              padding: EdgeInsets.only(left: 2, bottom: 2),
                              child: Icon(
                                Icons.send,
                                color: Colors.white,
                                size: 20,
                              ),
                            ),
                          ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatTime(DateTime? dateTime) {
    if (dateTime == null) return '';

    final hour = dateTime.hour;
    final minute = dateTime.minute.toString().padLeft(2, '0');

    if (hour < 12) {
      return '上午 $hour:$minute';
    } else if (hour == 12) {
      return '下午 12:$minute';
    } else {
      return '下午 ${hour - 12}:$minute';
    }
  }
}
