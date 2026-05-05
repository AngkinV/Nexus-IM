import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/network/websocket_service.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/contact_repository.dart';
import '../../../data/models/contact/contact_models.dart';
import '../../../data/models/websocket/websocket_message.dart';
import 'add_contact_page.dart';
import 'friend_requests_page.dart';
import 'create_group_page.dart';
import '../chat/chat_page.dart';
import '../../../data/repositories/chat_repository.dart';

/// 联系人页面
class ContactsPage extends StatefulWidget {
  const ContactsPage({super.key});

  @override
  State<ContactsPage> createState() => _ContactsPageState();
}

class _ContactsPageState extends State<ContactsPage> {
  final AuthRepository _authRepository = AuthRepository();
  final ContactRepository _contactRepository = ContactRepository();
  final ChatRepository _chatRepository = ChatRepository();
  final WebSocketService _webSocketService = WebSocketService();
  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  List<ContactGroup> _contactGroups = [];
  List<String> _availableLetters = [];
  bool _isLoading = true;
  String? _errorMessage;
  int? _currentUserId;
  int _pendingRequestCount = 0;
  String? _currentHighlightLetter;

  // 字母索引键映射
  final Map<String, GlobalKey> _sectionKeys = {};

  // WebSocket 订阅
  StreamSubscription<WebSocketMessage>? _wsSubscription;

  // 头像版本缓存（用于强制刷新）
  final Map<int, int> _avatarVersions = {};

  @override
  void initState() {
    super.initState();
    _loadData();
    _scrollController.addListener(_onScroll);
    _subscribeToWebSocket();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _scrollController.dispose();
    _wsSubscription?.cancel();
    super.dispose();
  }

  /// 订阅 WebSocket 消息
  void _subscribeToWebSocket() {
    _wsSubscription = _webSocketService.messageStream.listen(_onWebSocketMessage);
  }

  /// 处理 WebSocket 消息
  void _onWebSocketMessage(WebSocketMessage message) {
    switch (message.type) {
      case WebSocketMessageType.userProfileUpdated:
        _handleUserProfileUpdated(message);
        break;
      case WebSocketMessageType.contactAdded:
      case WebSocketMessageType.contactRemoved:
      case WebSocketMessageType.contactRequestAccepted:
        // 联系人变化时刷新列表
        _refreshContacts();
        break;
      default:
        break;
    }
  }

  /// 处理用户资料更新（头像/昵称变化）
  Future<void> _handleUserProfileUpdated(WebSocketMessage message) async {
    final userId = message.payload['userId'] as int?;
    final avatarUrl = message.payload['avatarUrl'] as String?;

    if (userId == null) return;

    // 如果有旧头像URL，清除缓存
    if (avatarUrl != null) {
      final fullUrl = ApiConfig.getFullUrl(avatarUrl);
      await CachedNetworkImage.evictFromCache(fullUrl);
    }

    // 更新头像版本号，强制刷新
    _avatarVersions[userId] = DateTime.now().millisecondsSinceEpoch;

    // 刷新联系人列表
    if (mounted) {
      await _loadContacts();
    }

    debugPrint('ContactsPage: 用户 $userId 资料已更新，头像缓存已清除');
  }

  Future<void> _loadData() async {
    final userId = await _authRepository.getCurrentUserId();
    if (userId == null) {
      setState(() {
        _isLoading = false;
        _errorMessage = '未登录';
      });
      return;
    }

    _currentUserId = userId;
    await Future.wait([
      _loadContacts(),
      _loadPendingRequestCount(),
    ]);
  }

  Future<void> _loadContacts() async {
    if (_currentUserId == null) return;

    try {
      final groups = await _contactRepository.getGroupedContacts(_currentUserId!);
      final letters = groups.map((g) => g.letter).toList();

      // 为每个分组创建 GlobalKey
      for (final letter in letters) {
        _sectionKeys[letter] = GlobalKey();
      }

      if (mounted) {
        setState(() {
          _contactGroups = groups;
          _availableLetters = letters;
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

  Future<void> _loadPendingRequestCount() async {
    if (_currentUserId == null) return;

    try {
      final count = await _contactRepository.getPendingRequestCount(_currentUserId!);
      if (mounted) {
        setState(() {
          _pendingRequestCount = count;
        });
      }
    } catch (_) {
      // 忽略错误
    }
  }

  Future<void> _refreshContacts() async {
    setState(() {
      _isLoading = true;
    });
    await Future.wait([
      _loadContacts(),
      _loadPendingRequestCount(),
    ]);
  }

  void _onScroll() {
    // 根据滚动位置更新当前高亮的字母
    for (final letter in _availableLetters) {
      final key = _sectionKeys[letter];
      if (key?.currentContext != null) {
        final box = key!.currentContext!.findRenderObject() as RenderBox?;
        if (box != null) {
          final position = box.localToGlobal(Offset.zero);
          if (position.dy >= 0 && position.dy < 200) {
            if (_currentHighlightLetter != letter) {
              setState(() {
                _currentHighlightLetter = letter;
              });
            }
            break;
          }
        }
      }
    }
  }

  void _scrollToLetter(String letter) {
    final key = _sectionKeys[letter];
    if (key?.currentContext != null) {
      Scrollable.ensureVisible(
        key!.currentContext!,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
      HapticFeedback.lightImpact();
    }
  }

  Future<void> _startChat(ContactModel contact) async {
    if (_currentUserId == null) return;

    try {
      // 创建或获取私聊
      final chat = await _chatRepository.createDirectChat(
        _currentUserId!,
        contact.userId,
      );

      if (mounted) {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => ChatPage(chat: chat),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('打开聊天失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      body: Stack(
        children: [
          Column(
            children: [
              // 顶部区域
              _buildHeader(context, isDark),

              // 搜索框
              _buildSearchBar(isDark),

              // 内容区域
              Expanded(
                child: _buildContent(context, isDark),
              ),
            ],
          ),

          // 字母索引栏
          if (_availableLetters.isNotEmpty)
            _buildAlphabetSidebar(isDark),
        ],
      ),
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
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '联系人',
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
                      color: Colors.transparent,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: IconButton(
                      onPressed: () async {
                        final result = await Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => const AddContactPage(),
                          ),
                        );
                        if (result == true) {
                          _refreshContacts();
                        }
                      },
                      icon: Icon(
                        Icons.person_add_outlined,
                        color: isDark ? Colors.white : Colors.black,
                        size: 24,
                      ),
                      padding: EdgeInsets.zero,
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

  /// 构建搜索框
  Widget _buildSearchBar(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Container(
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
            hintText: '搜索联系人',
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
          onChanged: (value) {
            // TODO: 实现搜索功能
          },
        ),
      ),
    );
  }

  /// 构建内容区域
  Widget _buildContent(BuildContext context, bool isDark) {
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
              onPressed: _refreshContacts,
              child: const Text('重试'),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _refreshContacts,
      color: AppTheme.primary,
      child: ListView(
        controller: _scrollController,
        padding: const EdgeInsets.only(bottom: 100, right: 24),
        children: [
          // 功能入口
          _buildFunctionalEntries(isDark),

          // 联系人列表
          ..._buildContactSections(isDark),
        ],
      ),
    );
  }

  /// 构建功能入口
  Widget _buildFunctionalEntries(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: [
          // 新的朋友
          _buildFunctionalItem(
            icon: Icons.person_add_alt,
            title: '新的朋友',
            badge: _pendingRequestCount > 0 ? _pendingRequestCount : null,
            isDark: isDark,
            onTap: () async {
              final result = await Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => const FriendRequestsPage(),
                ),
              );
              // 返回时刷新联系人列表和待处理数量
              if (result == true) {
                _refreshContacts();
              } else {
                _loadPendingRequestCount();
              }
            },
          ),

          // 群聊
          _buildFunctionalItem(
            icon: Icons.groups,
            title: '群聊',
            isDark: isDark,
            onTap: () async {
              await Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => const CreateGroupPage(),
                ),
              );
            },
          ),

          // 标签
          _buildFunctionalItem(
            icon: Icons.sell_outlined,
            title: '标签',
            isDark: isDark,
            onTap: () {
              // TODO: 跳转到标签管理
            },
          ),
        ],
      ),
    );
  }

  /// 构建功能入口项
  Widget _buildFunctionalItem({
    required IconData icon,
    required String title,
    int? badge,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            // 图标容器
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(
                icon,
                color: AppTheme.primary,
                size: 24,
              ),
            ),

            const SizedBox(width: 16),

            // 标题
            Expanded(
              child: Text(
                title,
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w500,
                  color: isDark ? Colors.white : Colors.grey[900],
                ),
              ),
            ),

            // 徽章
            if (badge != null && badge > 0)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.red,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  badge > 99 ? '99+' : badge.toString(),
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
              ),

            const SizedBox(width: 8),

            // 箭头
            Icon(
              Icons.chevron_right,
              color: Colors.grey[400],
              size: 20,
            ),
          ],
        ),
      ),
    );
  }

  /// 构建联系人分组列表
  List<Widget> _buildContactSections(bool isDark) {
    if (_contactGroups.isEmpty) {
      return [
        Padding(
          padding: const EdgeInsets.only(top: 80),
          child: Center(
            child: Column(
              children: [
                Icon(
                  Icons.people_outline,
                  size: 64,
                  color: Colors.grey[400],
                ),
                const SizedBox(height: 16),
                Text(
                  '暂无联系人',
                  style: TextStyle(
                    fontSize: 16,
                    color: Colors.grey[500],
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '添加好友开始聊天吧',
                  style: TextStyle(
                    fontSize: 14,
                    color: Colors.grey[400],
                  ),
                ),
              ],
            ),
          ),
        ),
      ];
    }

    final List<Widget> sections = [];

    for (final group in _contactGroups) {
      // 分组标题
      sections.add(
        Container(
          key: _sectionKeys[group.letter],
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: Text(
            group.letter,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.bold,
              color: isDark ? Colors.grey[400] : Colors.grey[500],
            ),
          ),
        ),
      );

      // 联系人列表
      for (int i = 0; i < group.contacts.length; i++) {
        final contact = group.contacts[i];
        final isLast = i == group.contacts.length - 1;
        sections.add(
          _buildContactItem(contact, isDark, showDivider: !isLast),
        );
      }
    }

    return sections;
  }

  /// 构建联系人项
  Widget _buildContactItem(ContactModel contact, bool isDark, {bool showDivider = true}) {
    return InkWell(
      onTap: () => _startChat(contact),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: showDivider
              ? BoxDecoration(
                  border: Border(
                    bottom: BorderSide(
                      color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey.shade100,
                    ),
                  ),
                )
              : null,
          child: Row(
            children: [
              // 头像
              _buildAvatar(contact, isDark),

              const SizedBox(width: 16),

              // 名称
              Expanded(
                child: Text(
                  contact.displayName,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: isDark ? Colors.white : Colors.grey[900],
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 构建头像
  Widget _buildAvatar(ContactModel contact, bool isDark) {
    final fullAvatarUrl = ApiConfig.getFullUrl(contact.avatarUrl);
    // 使用版本号作为缓存key，当版本号变化时强制刷新
    final version = _avatarVersions[contact.userId] ?? 0;
    final cacheKey = '${contact.userId}_$version';

    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(10),
        color: isDark ? Colors.grey[800] : Colors.grey[200],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: fullAvatarUrl.isNotEmpty
            ? CachedNetworkImage(
                imageUrl: fullAvatarUrl,
                cacheKey: cacheKey,
                fit: BoxFit.cover,
                placeholder: (context, url) => _buildDefaultAvatar(contact, isDark),
                errorWidget: (context, url, error) => _buildDefaultAvatar(contact, isDark),
              )
            : _buildDefaultAvatar(contact, isDark),
      ),
    );
  }

  /// 构建默认头像
  Widget _buildDefaultAvatar(ContactModel contact, bool isDark) {
    final name = contact.displayName;
    final initials = name.isNotEmpty ? name[0].toUpperCase() : '?';

    return Container(
      color: AppTheme.primary,
      child: Center(
        child: Text(
          initials,
          style: const TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
        ),
      ),
    );
  }

  /// 构建字母索引栏
  Widget _buildAlphabetSidebar(bool isDark) {
    const allLetters = ['↑', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '#'];

    return Positioned(
      right: 2,
      top: 0,
      bottom: 0,
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: allLetters.map((letter) {
              final isAvailable = letter == '↑' || _availableLetters.contains(letter);
              final isHighlighted = letter == _currentHighlightLetter;

              return GestureDetector(
                onTap: () {
                  if (letter == '↑') {
                    _scrollController.animateTo(
                      0,
                      duration: const Duration(milliseconds: 300),
                      curve: Curves.easeOut,
                    );
                    HapticFeedback.lightImpact();
                  } else if (isAvailable) {
                    _scrollToLetter(letter);
                  }
                },
                child: Container(
                  width: 16,
                  height: 16,
                  margin: const EdgeInsets.symmetric(vertical: 0.5),
                  decoration: BoxDecoration(
                    color: isHighlighted ? AppTheme.primary : Colors.transparent,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Center(
                    child: letter == '↑'
                        ? const Icon(
                            Icons.arrow_upward,
                            size: 10,
                            color: AppTheme.primary,
                          )
                        : Text(
                            letter,
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.bold,
                              color: isHighlighted
                                  ? Colors.white
                                  : isAvailable
                                      ? (isDark ? Colors.white : Colors.black)
                                      : AppTheme.primary.withValues(alpha: 0.4),
                            ),
                          ),
                  ),
                ),
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}
