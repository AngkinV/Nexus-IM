import 'package:flutter/material.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/models/auth/auth_models.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/contact_repository.dart';

/// 添加联系人页面
class AddContactPage extends StatefulWidget {
  const AddContactPage({super.key});

  @override
  State<AddContactPage> createState() => _AddContactPageState();
}

class _AddContactPageState extends State<AddContactPage> {
  final AuthRepository _authRepository = AuthRepository();
  final ContactRepository _contactRepository = ContactRepository();
  final TextEditingController _searchController = TextEditingController();

  UserModel? _currentUser;
  List<Map<String, dynamic>> _searchResults = [];
  bool _isSearching = false;
  bool _showResults = false;
  int? _currentUserId;

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadCurrentUser() async {
    final user = await _authRepository.getCurrentUser();
    final userId = await _authRepository.getCurrentUserId();
    setState(() {
      _currentUser = user;
      _currentUserId = userId;
    });
  }

  Future<void> _searchUsers(String query) async {
    if (query.trim().isEmpty) {
      setState(() {
        _searchResults = [];
        _showResults = false;
      });
      return;
    }

    setState(() {
      _isSearching = true;
      _showResults = true;
    });

    try {
      final results = await _contactRepository.searchUsers(query);
      final filtered = results
          .where((user) => user['id'] != _currentUserId)
          .toList();

      setState(() {
        _searchResults = filtered;
        _isSearching = false;
      });
    } catch (e) {
      setState(() {
        _isSearching = false;
      });
    }
  }

  Future<void> _addContact(Map<String, dynamic> user) async {
    if (_currentUserId == null) return;

    final userId = user['id'] as int;
    final message = await _showAddMessageDialog(user);
    if (message == null) return;

    try {
      final response = await _contactRepository.addContact(
        userId: _currentUserId!,
        contactUserId: userId,
        message: message.isEmpty ? null : message,
      );

      if (mounted) {
        if (response.isDirect) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('添加成功')),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('好友申请已发送')),
          );
        }
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('添加失败: $e')),
        );
      }
    }
  }

  Future<String?> _showAddMessageDialog(Map<String, dynamic> user) async {
    final controller = TextEditingController();
    final nickname = user['nickname'] ?? user['username'] ?? '用户';

    return showDialog<String>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: Text('添加 $nickname'),
          content: TextField(
            controller: controller,
            decoration: const InputDecoration(
              hintText: '请输入验证消息（可选）',
              border: OutlineInputBorder(),
            ),
            maxLines: 3,
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, null),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, controller.text),
              child: const Text('发送'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF102217) : const Color(0xFFF5F8F7),
      body: SafeArea(
        child: Column(
          children: [
            // 头部
            _buildHeader(isDark),

            // 搜索栏
            _buildSearchBar(isDark),

            // 内容区域
            Expanded(
              child: _showResults
                  ? _buildSearchResults(isDark)
                  : _buildMainContent(isDark),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 12, 8, 8),
      child: Row(
        children: [
          IconButton(
            onPressed: () => Navigator.pop(context),
            icon: const Icon(Icons.chevron_left, size: 28),
            padding: EdgeInsets.zero,
          ),
          const Expanded(
            child: Text(
              '添加联系人',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(width: 48), // 平衡左边的按钮
        ],
      ),
    );
  }

  Widget _buildSearchBar(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      child: Column(
        children: [
          Container(
            decoration: BoxDecoration(
              color: isDark ? Colors.grey[800] : Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: isDark ? Colors.grey[700]! : Colors.grey[100]!,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.03),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Row(
              children: [
                const Padding(
                  padding: EdgeInsets.only(left: 16),
                  child: Icon(Icons.search, color: Colors.grey, size: 24),
                ),
                Expanded(
                  child: TextField(
                    controller: _searchController,
                    decoration: const InputDecoration(
                      hintText: '邮箱/Nexus ID',
                      hintStyle: TextStyle(color: Colors.grey),
                      border: InputBorder.none,
                      contentPadding: EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 14,
                      ),
                    ),
                    onChanged: (value) {
                      if (value.length >= 2) {
                        _searchUsers(value);
                      } else if (value.isEmpty) {
                        setState(() {
                          _searchResults = [];
                          _showResults = false;
                        });
                      }
                    },
                    onSubmitted: _searchUsers,
                  ),
                ),
                if (_searchController.text.isNotEmpty)
                  IconButton(
                    onPressed: () {
                      _searchController.clear();
                      setState(() {
                        _searchResults = [];
                        _showResults = false;
                      });
                    },
                    icon: const Icon(Icons.clear, color: Colors.grey),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Text(
            '我的 Nexus ID: ${_currentUser?.username ?? '加载中...'}',
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey[500],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMainContent(bool isDark) {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(
        children: [
          const SizedBox(height: 8),

          // 快捷操作
          _buildActionItem(
            icon: Icons.qr_code_scanner,
            title: '扫一扫',
            subtitle: '扫描二维码名片',
            isDark: isDark,
            onTap: () {
              // TODO: 扫码功能
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('扫码功能开发中')),
              );
            },
          ),
          const SizedBox(height: 12),
          _buildActionItem(
            icon: Icons.contact_phone,
            title: '手机联系人',
            subtitle: '添加通讯录中的好友',
            isDark: isDark,
            onTap: () {
              // TODO: 通讯录功能
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('通讯录功能开发中')),
              );
            },
          ),
          const SizedBox(height: 12),
          _buildActionItem(
            icon: Icons.diversity_3,
            title: '面对面建群',
            subtitle: '与身边的朋友进入同一个群聊',
            isDark: isDark,
            onTap: () {
              // TODO: 面对面建群
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('面对面建群功能开发中')),
              );
            },
          ),

          const SizedBox(height: 24),

          // 二维码卡片
          _buildQRCodeCard(isDark),

          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _buildActionItem({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: isDark ? Colors.grey[800] : Colors.white,
          borderRadius: BorderRadius.circular(16),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.03),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: AppTheme.primary, size: 24),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.grey[500],
                    ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: Colors.grey[300]),
          ],
        ),
      ),
    );
  }

  Widget _buildQRCodeCard(bool isDark) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: isDark ? Colors.grey[800] : Colors.white,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(
          color: isDark ? Colors.grey[700]! : Colors.grey[100]!,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        children: [
          // 用户信息
          Row(
            children: [
              CircleAvatar(
                radius: 20,
                backgroundColor: AppTheme.primary,
                backgroundImage: _currentUser?.avatarUrl != null
                    ? NetworkImage(_currentUser!.avatarUrl!)
                    : null,
                child: _currentUser?.avatarUrl == null
                    ? Text(
                        (_currentUser?.nickname ?? _currentUser?.username ?? '?')[0].toUpperCase(),
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                        ),
                      )
                    : null,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _currentUser?.nickname ?? _currentUser?.username ?? '加载中...',
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Nexus ID: ${_currentUser?.username ?? ''}',
                      style: TextStyle(
                        fontSize: 10,
                        color: Colors.grey[500],
                        letterSpacing: 0.5,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 20),

          // 二维码区域
          Stack(
            clipBehavior: Clip.none,
            children: [
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: isDark ? Colors.grey[900] : Colors.grey[50],
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: AppTheme.primary.withValues(alpha: 0.2),
                    width: 2,
                  ),
                ),
                child: Container(
                  width: 128,
                  height: 128,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.05),
                        blurRadius: 4,
                        spreadRadius: -2,
                      ),
                    ],
                  ),
                  child: Stack(
                    children: [
                      // 四角装饰
                      Positioned(
                        top: 8,
                        left: 8,
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            border: Border(
                              top: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                              left: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        top: 8,
                        right: 8,
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            border: Border(
                              top: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                              right: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        bottom: 8,
                        left: 8,
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            border: Border(
                              bottom: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                              left: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        bottom: 8,
                        right: 8,
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            border: Border(
                              bottom: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                              right: BorderSide(width: 4, color: isDark ? Colors.white : Colors.black),
                            ),
                          ),
                        ),
                      ),
                      // 中心图标
                      Center(
                        child: Container(
                          width: 16,
                          height: 16,
                          decoration: BoxDecoration(
                            color: AppTheme.primary,
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              // 标签
              Positioned(
                bottom: -10,
                left: 0,
                right: 0,
                child: Center(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    decoration: BoxDecoration(
                      color: AppTheme.primary,
                      borderRadius: BorderRadius.circular(12),
                      boxShadow: [
                        BoxShadow(
                          color: AppTheme.primary.withValues(alpha: 0.3),
                          blurRadius: 8,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: const Text(
                      '我的二维码',
                      style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),

          const SizedBox(height: 24),

          Text(
            '展示二维码，让对方扫一扫添加你',
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey[500],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchResults(bool isDark) {
    if (_isSearching) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_searchResults.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.person_off_outlined, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '未找到用户',
              style: TextStyle(color: Colors.grey[500], fontSize: 16),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: () {
                setState(() {
                  _showResults = false;
                  _searchController.clear();
                });
              },
              child: const Text('返回'),
            ),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      itemCount: _searchResults.length,
      itemBuilder: (context, index) {
        final user = _searchResults[index];
        return _buildUserItem(user, isDark);
      },
    );
  }

  Widget _buildUserItem(Map<String, dynamic> user, bool isDark) {
    final username = user['username'] ?? '';
    final nickname = user['nickname'];
    final avatarUrl = user['avatarUrl'];
    final displayName = nickname ?? username;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark ? Colors.grey[800] : Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 24,
            backgroundColor: AppTheme.primary,
            backgroundImage: avatarUrl != null ? NetworkImage(avatarUrl) : null,
            child: avatarUrl == null
                ? Text(
                    displayName.isNotEmpty ? displayName[0].toUpperCase() : '?',
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  )
                : null,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  displayName,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 16,
                  ),
                ),
                if (nickname != null)
                  Text(
                    '@$username',
                    style: TextStyle(color: Colors.grey[500], fontSize: 13),
                  ),
              ],
            ),
          ),
          TextButton(
            onPressed: () => _addContact(user),
            style: TextButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            child: const Text('添加'),
          ),
        ],
      ),
    );
  }
}
