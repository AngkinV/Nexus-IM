import 'package:flutter/material.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/models/contact/contact_models.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/contact_repository.dart';
import 'add_contact_page.dart';

/// 好友申请页面
class FriendRequestsPage extends StatefulWidget {
  const FriendRequestsPage({super.key});

  @override
  State<FriendRequestsPage> createState() => _FriendRequestsPageState();
}

class _FriendRequestsPageState extends State<FriendRequestsPage> {
  final AuthRepository _authRepository = AuthRepository();
  final ContactRepository _contactRepository = ContactRepository();

  List<ContactRequestModel> _pendingRequests = [];
  List<ContactRequestModel> _sentRequests = [];
  List<Map<String, dynamic>> _recommendedUsers = [];
  bool _isLoading = true;
  int? _currentUserId;
  int _currentTabIndex = 0;

  bool _hasAcceptedRequest = false;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    final userId = await _authRepository.getCurrentUserId();
    if (userId == null) {
      setState(() => _isLoading = false);
      return;
    }

    _currentUserId = userId;
    await Future.wait([
      _loadRequests(),
      _loadRecommendedUsers(),
    ]);
  }

  Future<void> _loadRequests() async {
    if (_currentUserId == null) return;

    try {
      final pending = await _contactRepository.getPendingRequests(_currentUserId!);
      final sent = await _contactRepository.getSentRequests(_currentUserId!);

      if (mounted) {
        setState(() {
          _pendingRequests = pending;
          _sentRequests = sent;
          _isLoading = false;
        });
      }
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载失败: $e')),
        );
      }
    }
  }

  Future<void> _loadRecommendedUsers() async {
    if (_currentUserId == null) return;

    try {
      // 获取随机推荐用户
      final users = await _contactRepository.getRandomUsers(_currentUserId!, limit: 4);

      if (mounted) {
        setState(() {
          _recommendedUsers = users;
        });
      }
    } catch (_) {
      // 忽略错误，不显示推荐区域
      if (mounted) {
        setState(() {
          _recommendedUsers = [];
        });
      }
    }
  }

  Future<void> _acceptRequest(ContactRequestModel request) async {
    if (_currentUserId == null) return;

    try {
      await _contactRepository.acceptRequest(request.id, _currentUserId!);
      if (mounted) {
        setState(() {
          _hasAcceptedRequest = true;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('已添加为好友')),
        );
        _loadRequests();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败: $e')),
        );
      }
    }
  }

  Future<void> _rejectRequest(ContactRequestModel request) async {
    if (_currentUserId == null) return;

    try {
      await _contactRepository.rejectRequest(request.id, _currentUserId!);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('已拒绝')),
        );
        _loadRequests();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败: $e')),
        );
      }
    }
  }

  Future<void> _addRecommendedUser(Map<String, dynamic> user) async {
    if (_currentUserId == null) return;

    final userId = user['id'] as int;

    try {
      final response = await _contactRepository.addContact(
        userId: _currentUserId!,
        contactUserId: userId,
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(response.isDirect ? '添加成功' : '好友申请已发送'),
          ),
        );
        // 从推荐列表中移除
        setState(() {
          _recommendedUsers.removeWhere((u) => u['id'] == userId);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('添加失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF111111) : Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            // 头部
            _buildHeader(isDark),

            // 标签导航
            _buildTabNav(isDark),

            // 内容区域
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : RefreshIndicator(
                      onRefresh: _loadData,
                      child: SingleChildScrollView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 请求列表
                            _currentTabIndex == 0
                                ? _buildPendingContent(isDark)
                                : _buildSentContent(isDark),

                            // 分割区域
                            if (_currentTabIndex == 0 && _pendingRequests.isEmpty)
                              Container(
                                height: 8,
                                color: isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F7F7),
                              ),

                            // 可能认识的人
                            if (_currentTabIndex == 0 && _recommendedUsers.isNotEmpty)
                              _buildRecommendedSection(isDark),

                            // 搜索卡片
                            if (_currentTabIndex == 0)
                              _buildSearchCard(isDark),

                            const SizedBox(height: 40),
                          ],
                        ),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return Container(
      height: 56,
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // 返回按钮
          IconButton(
            onPressed: () => Navigator.pop(context, _hasAcceptedRequest),
            icon: Icon(
              Icons.arrow_back_ios_new,
              size: 22,
              color: isDark ? Colors.white : Colors.black,
            ),
          ),

          // 标题
          const Text(
            '新的朋友',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.bold,
              letterSpacing: -0.3,
            ),
          ),

          // 添加按钮
          IconButton(
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const AddContactPage()),
              );
            },
            icon: Icon(
              Icons.person_add_alt,
              size: 24,
              color: AppTheme.primary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTabNav(bool isDark) {
    return Container(
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(
            color: isDark ? Colors.grey[800]! : Colors.grey[100]!,
          ),
        ),
      ),
      child: Row(
        children: [
          // 收到的申请
          Expanded(
            child: GestureDetector(
              onTap: () => setState(() => _currentTabIndex = 0),
              child: Container(
                height: 48,
                alignment: Alignment.center,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      '收到的申请 (${_pendingRequests.length})',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: _currentTabIndex == 0
                            ? FontWeight.w600
                            : FontWeight.w500,
                        color: _currentTabIndex == 0
                            ? AppTheme.primary
                            : (isDark ? Colors.grey[500] : Colors.grey[400]),
                      ),
                    ),
                    const SizedBox(height: 8),
                    // 指示器
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      width: 20,
                      height: 3,
                      decoration: BoxDecoration(
                        color: _currentTabIndex == 0
                            ? AppTheme.primary
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // 已发送
          Expanded(
            child: GestureDetector(
              onTap: () => setState(() => _currentTabIndex = 1),
              child: Container(
                height: 48,
                alignment: Alignment.center,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      '已发送 (${_sentRequests.length})',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: _currentTabIndex == 1
                            ? FontWeight.w600
                            : FontWeight.w500,
                        color: _currentTabIndex == 1
                            ? AppTheme.primary
                            : (isDark ? Colors.grey[500] : Colors.grey[400]),
                      ),
                    ),
                    const SizedBox(height: 8),
                    // 指示器
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      width: 20,
                      height: 3,
                      decoration: BoxDecoration(
                        color: _currentTabIndex == 1
                            ? AppTheme.primary
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPendingContent(bool isDark) {
    if (_pendingRequests.isEmpty) {
      return _buildEmptyState(isDark);
    }

    return ListView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      itemCount: _pendingRequests.length,
      itemBuilder: (context, index) {
        final request = _pendingRequests[index];
        return _buildPendingItem(request, isDark);
      },
    );
  }

  Widget _buildSentContent(bool isDark) {
    if (_sentRequests.isEmpty) {
      return _buildEmptyState(isDark, message: '暂无已发送的申请');
    }

    return ListView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      itemCount: _sentRequests.length,
      itemBuilder: (context, index) {
        final request = _sentRequests[index];
        return _buildSentItem(request, isDark);
      },
    );
  }

  Widget _buildEmptyState(bool isDark, {String? message}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 60, horizontal: 32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          // 图标区域
          Container(
            width: 128,
            height: 128,
            decoration: BoxDecoration(
              color: AppTheme.primary.withValues(alpha: isDark ? 0.1 : 0.05),
              shape: BoxShape.circle,
            ),
            child: Center(
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  Icon(
                    Icons.forum_rounded,
                    size: 56,
                    color: AppTheme.primary.withValues(alpha: 0.4),
                  ),
                  Positioned(
                    top: -8,
                    right: -8,
                    child: Container(
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        color: isDark ? const Color(0xFF111111) : Colors.white,
                        shape: BoxShape.circle,
                      ),
                      child: Center(
                        child: Icon(
                          Icons.person_add,
                          size: 14,
                          color: AppTheme.primary,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 24),

          // 文字
          Text(
            message ?? '暂无好友申请',
            style: TextStyle(
              fontSize: 14,
              color: isDark ? Colors.grey[500] : Colors.grey[400],
              letterSpacing: 0.5,
            ),
            textAlign: TextAlign.center,
          ),

          const SizedBox(height: 4),

          Text(
            '你的圈子正在等待新的连接',
            style: TextStyle(
              fontSize: 13,
              color: isDark ? Colors.grey[600] : Colors.grey[300],
            ),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget _buildPendingItem(ContactRequestModel request, bool isDark) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 头像
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              color: AppTheme.primary,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: request.fromAvatarUrl != null
                  ? Image.network(
                      request.fromAvatarUrl!,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => _buildDefaultAvatar(request.fromDisplayName),
                    )
                  : _buildDefaultAvatar(request.fromDisplayName),
            ),
          ),

          const SizedBox(width: 16),

          // 内容
          Expanded(
            child: Container(
              padding: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark ? Colors.grey[800]! : Colors.grey[100]!,
                  ),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              request.fromDisplayName,
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: isDark ? Colors.white : Colors.grey[800],
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              request.message?.isNotEmpty == true
                                  ? request.message!
                                  : '请求添加你为好友',
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.grey[400],
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ],
                        ),
                      ),

                      const SizedBox(width: 12),

                      // 操作按钮
                      Row(
                        children: [
                          GestureDetector(
                            onTap: () => _rejectRequest(request),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                              decoration: BoxDecoration(
                                color: isDark ? Colors.grey[800] : Colors.grey[100],
                                borderRadius: BorderRadius.circular(20),
                              ),
                              child: Text(
                                '拒绝',
                                style: TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  color: isDark ? Colors.grey[400] : Colors.grey[600],
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          GestureDetector(
                            onTap: () => _acceptRequest(request),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                              decoration: BoxDecoration(
                                color: AppTheme.primary,
                                borderRadius: BorderRadius.circular(20),
                                boxShadow: [
                                  BoxShadow(
                                    color: AppTheme.primary.withValues(alpha: 0.2),
                                    blurRadius: 4,
                                  ),
                                ],
                              ),
                              child: const Text(
                                '接受',
                                style: TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSentItem(ContactRequestModel request, bool isDark) {
    final statusText = _getStatusText(request.status);
    final statusColor = _getStatusColor(request.status);

    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 头像
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              color: AppTheme.primary,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: request.toAvatarUrl != null
                  ? Image.network(
                      request.toAvatarUrl!,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => _buildDefaultAvatar(request.toDisplayName),
                    )
                  : _buildDefaultAvatar(request.toDisplayName),
            ),
          ),

          const SizedBox(width: 16),

          // 内容
          Expanded(
            child: Container(
              padding: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark ? Colors.grey[800]! : Colors.grey[100]!,
                  ),
                ),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          request.toDisplayName,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: isDark ? Colors.white : Colors.grey[800],
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '@${request.toUsername ?? ''}',
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[400],
                          ),
                        ),
                      ],
                    ),
                  ),

                  // 状态标签
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: statusColor.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Text(
                      statusText,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: statusColor,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRecommendedSection(bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 24),

        // 标题
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '可能认识的人',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: isDark ? Colors.white : Colors.grey[800],
                ),
              ),
              Text(
                '查看更多',
                style: TextStyle(
                  fontSize: 12,
                  color: Colors.grey[400],
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),

        const SizedBox(height: 16),

        // 推荐列表
        ListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 20),
          itemCount: _recommendedUsers.length,
          itemBuilder: (context, index) {
            final user = _recommendedUsers[index];
            final isLast = index == _recommendedUsers.length - 1;
            return _buildRecommendedItem(user, isDark, showDivider: !isLast);
          },
        ),
      ],
    );
  }

  Widget _buildRecommendedItem(Map<String, dynamic> user, bool isDark, {bool showDivider = true}) {
    final username = user['username'] ?? '';
    final nickname = user['nickname'] ?? username;
    final avatarUrl = user['avatarUrl'];

    // 随机生成关系描述
    final relations = [
      '可能认识',
      '来自共同群聊',
      '3 位共同好友',
    ];
    final relation = relations[user['id'].hashCode % relations.length];

    return Padding(
      padding: const EdgeInsets.only(bottom: 0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 头像
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              color: _getAvatarColor(nickname),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: avatarUrl != null
                  ? Image.network(
                      avatarUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => _buildDefaultAvatar(nickname),
                    )
                  : _buildDefaultAvatar(nickname),
            ),
          ),

          const SizedBox(width: 16),

          // 内容
          Expanded(
            child: Container(
              padding: const EdgeInsets.only(bottom: 16),
              decoration: showDivider
                  ? BoxDecoration(
                      border: Border(
                        bottom: BorderSide(
                          color: isDark ? Colors.grey[800]! : Colors.grey[50]!,
                        ),
                      ),
                    )
                  : null,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          nickname,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: isDark ? Colors.white : Colors.grey[800],
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          relation,
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[400],
                          ),
                        ),
                      ],
                    ),
                  ),

                  // 添加按钮
                  GestureDetector(
                    onTap: () => _addRecommendedUser(user),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                      decoration: BoxDecoration(
                        color: AppTheme.primary,
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: [
                          BoxShadow(
                            color: AppTheme.primary.withValues(alpha: 0.2),
                            blurRadius: 4,
                          ),
                        ],
                      ),
                      child: const Text(
                        '添加',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchCard(bool isDark) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 32, 20, 0),
      child: Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F7F7),
          borderRadius: BorderRadius.circular(24),
        ),
        child: Column(
          children: [
            Icon(
              Icons.search,
              size: 32,
              color: isDark ? Colors.grey[600] : Colors.grey[300],
            ),
            const SizedBox(height: 8),
            Text(
              '找不到你想找的人？',
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.grey[400] : Colors.grey[600],
              ),
            ),
            const SizedBox(height: 12),
            GestureDetector(
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const AddContactPage()),
                );
              },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  '通过手机号搜索',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                    color: AppTheme.primary,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDefaultAvatar(String name) {
    return Center(
      child: Text(
        name.isNotEmpty ? name[0].toUpperCase() : '?',
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.bold,
          fontSize: 20,
        ),
      ),
    );
  }

  Color _getAvatarColor(String name) {
    final colors = [
      AppTheme.primary,
      Colors.indigo,
      Colors.orange,
      Colors.purple,
      Colors.teal,
      Colors.pink,
    ];
    return colors[name.hashCode.abs() % colors.length];
  }

  String _getStatusText(ContactRequestStatus status) {
    switch (status) {
      case ContactRequestStatus.pending:
        return '等待确认';
      case ContactRequestStatus.accepted:
        return '已通过';
      case ContactRequestStatus.rejected:
        return '已拒绝';
    }
  }

  Color _getStatusColor(ContactRequestStatus status) {
    switch (status) {
      case ContactRequestStatus.pending:
        return Colors.orange;
      case ContactRequestStatus.accepted:
        return AppTheme.primary;
      case ContactRequestStatus.rejected:
        return Colors.red;
    }
  }
}
