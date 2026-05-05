import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/network/message_service.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/chat_repository.dart';
import '../../../data/models/auth/auth_models.dart';
import '../../../data/models/chat/chat_models.dart';
import '../chat/chat_page.dart';

/// 消息页面
class MessagesPage extends StatefulWidget {
  const MessagesPage({super.key});

  @override
  State<MessagesPage> createState() => _MessagesPageState();
}

class _MessagesPageState extends State<MessagesPage> with WidgetsBindingObserver {
  final AuthRepository _authRepository = AuthRepository();
  final ChatRepository _chatRepository = ChatRepository();
  final TextEditingController _searchController = TextEditingController();
  final MessageService _messageService = MessageService();

  UserModel? _currentUser;
  List<ChatModel> _chats = [];
  bool _isLoading = true;
  String? _errorMessage;

  StreamSubscription<int>? _chatUpdateSubscription;
  Timer? _uiRefreshTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadData();
    _setupListeners();
    _startUiRefresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 当应用从后台恢复时刷新聊天列表
    if (state == AppLifecycleState.resumed) {
      _refreshChats();
    }
  }

  void _setupListeners() {
    // 监听聊天更新通知（来自 WebSocket）
    _chatUpdateSubscription = _messageService.chatUpdateStream.listen((chatId) {
      debugPrint('📨 MessagesPage: 收到聊天更新通知 chatId=$chatId');
      _refreshChats();
    });
  }

  /// 启动UI刷新定时器（更新时间显示）
  void _startUiRefresh() {
    // 每分钟刷新一次UI，更新"X分钟前"的显示
    _uiRefreshTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      if (mounted) {
        setState(() {});
      }
    });
  }

  Future<void> _loadData() async {
    await _loadCurrentUser();
    await _loadChats();
  }

  Future<void> _loadCurrentUser() async {
    final user = await _authRepository.getCurrentUser();
    if (mounted) {
      setState(() {
        _currentUser = user;
      });
    }
  }

  Future<void> _loadChats() async {
    if (_currentUser == null) {
      setState(() {
        _isLoading = false;
      });
      return;
    }

    try {
      final chats = await _chatRepository.getUserChats(_currentUser!.id);
      if (mounted) {
        setState(() {
          _chats = chats;
          _isLoading = false;
          _errorMessage = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = e.toString();
        });
      }
    }
  }

  Future<void> _refreshChats({bool showLoading = false}) async {
    if (showLoading) {
      setState(() {
        _isLoading = true;
      });
    }
    await _loadChats();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _chatUpdateSubscription?.cancel();
    _uiRefreshTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Column(
      children: [
        // 顶部区域
        _buildHeader(context, isDark),

        // 消息列表
        Expanded(
          child: _buildMessageList(context, isDark),
        ),
      ],
    );
  }

  /// 构建顶部区域
  Widget _buildHeader(BuildContext context, bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          color: isDark
              ? Theme.of(context).scaffoldBackgroundColor.withValues(alpha: 0.8)
              : Theme.of(context).scaffoldBackgroundColor.withValues(alpha: 0.8),
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: Column(
                children: [
                  // 标题行
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        '消息',
                        style: Theme.of(context).textTheme.displayMedium?.copyWith(
                              fontSize: 28,
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      // 添加按钮
                      Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          color: AppTheme.primary,
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: IconButton(
                          onPressed: () {
                            // TODO: 新建聊天
                          },
                          icon: const Icon(Icons.add, color: Colors.black, size: 20),
                          padding: EdgeInsets.zero,
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // 搜索框
                  _buildSearchBar(isDark),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 构建搜索框
  Widget _buildSearchBar(bool isDark) {
    return Container(
      height: 44,
      decoration: BoxDecoration(
        color: isDark
            ? Colors.grey[800]?.withValues(alpha: 0.5)
            : Colors.grey[200]?.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(12),
      ),
      child: TextField(
        controller: _searchController,
        style: Theme.of(context).textTheme.bodyMedium,
        decoration: InputDecoration(
          hintText: '搜索聊天内容',
          hintStyle: TextStyle(
            color: isDark ? Colors.grey[500] : Colors.grey[500],
            fontSize: 14,
          ),
          prefixIcon: Icon(
            Icons.search,
            color: isDark ? Colors.grey[500] : Colors.grey[400],
            size: 20,
          ),
          border: InputBorder.none,
          contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        ),
      ),
    );
  }

  /// 构建消息列表
  Widget _buildMessageList(BuildContext context, bool isDark) {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(
          color: AppTheme.primary,
        ),
      );
    }

    if (_errorMessage != null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.error_outline,
              size: 48,
              color: Colors.grey[400],
            ),
            const SizedBox(height: 16),
            Text(
              '加载失败',
              style: TextStyle(
                fontSize: 16,
                color: Colors.grey[500],
              ),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: () => _refreshChats(showLoading: true),
              child: const Text('重试'),
            ),
          ],
        ),
      );
    }

    if (_chats.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.chat_bubble_outline,
              size: 64,
              color: Colors.grey[400],
            ),
            const SizedBox(height: 16),
            Text(
              '暂无聊天记录',
              style: TextStyle(
                fontSize: 16,
                color: Colors.grey[500],
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '开始和好友聊天吧',
              style: TextStyle(
                fontSize: 14,
                color: Colors.grey[400],
              ),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _refreshChats(showLoading: false),
      color: AppTheme.primary,
      child: ListView.builder(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: _chats.length + 1,
        itemBuilder: (context, index) {
          if (index == 0) {
            // 列表标题
            return Padding(
              padding: const EdgeInsets.only(top: 16, bottom: 8, left: 4),
              child: Text(
                '最近聊天',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.5,
                  color: isDark ? Colors.grey[500] : Colors.grey[400],
                ),
              ),
            );
          }
          return _buildChatItem(_chats[index - 1], isDark);
        },
      ),
    );
  }

  /// 构建聊天项
  Widget _buildChatItem(ChatModel chat, bool isDark) {
    final hasUnread = chat.unreadCount > 0;
    final isOnline = chat.members.isNotEmpty && chat.members.first.isOnline;

    return InkWell(
      onTap: () async {
        await Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => ChatPage(chat: chat),
          ),
        );
        _refreshChats();
      },
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16),
        decoration: BoxDecoration(
          border: Border(
            bottom: BorderSide(
              color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey.shade100,
            ),
          ),
        ),
        child: Row(
          children: [
            // 头像
            _buildAvatar(chat, isDark, isOnline),

            const SizedBox(width: 16),

            // 内容
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 名称
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Row(
                          children: [
                            Flexible(
                              child: Text(
                                chat.displayName,
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                  color: isDark ? Colors.white : Colors.grey[900],
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            if (chat.isGroup) ...[
                              const SizedBox(width: 4),
                              Icon(
                                Icons.group,
                                size: 14,
                                color: Colors.grey[500],
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 4),

                  // 最后消息
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          _getLastMessageText(chat),
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: hasUnread ? FontWeight.w600 : FontWeight.normal,
                            color: hasUnread
                                ? (isDark ? Colors.grey[300] : Colors.grey[700])
                                : Colors.grey[500],
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),

                      const SizedBox(width: 8),

                      // 未读数或状态
                      _buildTrailing(chat, isDark),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 获取最后消息文本
  String _getLastMessageText(ChatModel chat) {
    if (chat.lastMessage == null) {
      return '暂无消息';
    }

    final message = chat.lastMessage!;
    if (chat.isGroup && message.senderNickname != null) {
      return '${message.senderNickname}: ${message.previewText}';
    }
    return message.previewText;
  }

  /// 构建头像
  Widget _buildAvatar(ChatModel chat, bool isDark, bool isOnline) {
    final fullAvatarUrl = ApiConfig.getFullUrl(chat.displayAvatar);

    return Stack(
      children: [
        Container(
          width: 56,
          height: 56,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(
              color: isOnline
                  ? AppTheme.primary.withValues(alpha: 0.2)
                  : (isDark ? Colors.grey[700]! : Colors.grey[300]!),
              width: 2,
            ),
            color: isDark ? Colors.grey[800] : Colors.grey[200],
          ),
          child: ClipOval(
            child: fullAvatarUrl.isNotEmpty
                ? CachedNetworkImage(
                    imageUrl: fullAvatarUrl,
                    fit: BoxFit.cover,
                    placeholder: (context, url) => _buildDefaultAvatar(chat, isDark),
                    errorWidget: (context, url, error) => _buildDefaultAvatar(chat, isDark),
                  )
                : _buildDefaultAvatar(chat, isDark),
          ),
        ),
        // 在线状态
        if (isOnline)
          Positioned(
            right: 0,
            bottom: 0,
            child: Container(
              width: 14,
              height: 14,
              decoration: BoxDecoration(
                color: AppTheme.primary,
                shape: BoxShape.circle,
                border: Border.all(
                  color: isDark
                      ? Theme.of(context).scaffoldBackgroundColor
                      : Colors.white,
                  width: 2,
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// 构建默认头像
  Widget _buildDefaultAvatar(ChatModel chat, bool isDark) {
    return Container(
      color: isDark ? Colors.grey[700] : Colors.grey[300],
      child: Center(
        child: Text(
          chat.displayName.isNotEmpty ? chat.displayName[0].toUpperCase() : '?',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
            color: isDark ? Colors.white : Colors.grey[600],
          ),
        ),
      ),
    );
  }

  /// 构建尾部图标
  Widget _buildTrailing(ChatModel chat, bool isDark) {
    if (chat.unreadCount > 0) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        constraints: const BoxConstraints(minWidth: 20),
        decoration: BoxDecoration(
          color: AppTheme.primary,
          borderRadius: BorderRadius.circular(10),
          boxShadow: [
            BoxShadow(
              color: AppTheme.primary.withValues(alpha: 0.2),
              blurRadius: 4,
            ),
          ],
        ),
        child: Center(
          child: Text(
            chat.unreadCount > 99 ? '99+' : chat.unreadCount.toString(),
            style: const TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.bold,
              color: Colors.black,
            ),
          ),
        ),
      );
    }

    return Icon(
      Icons.chevron_right,
      size: 18,
      color: Colors.grey[400],
    );
  }
}
