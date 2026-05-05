import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/state/user_state_manager.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/models/auth/auth_models.dart';
import 'settings_page.dart';
import 'profile_edit_page.dart';
import '../../../data/datasources/remote/user_api_service.dart';
import '../community/bookmarks_page.dart';

/// 个人中心页面
class ProfilePage extends StatefulWidget {
  final VoidCallback? onNavigateToCommunity;
  final ValueNotifier<int>? tabNotifier;

  const ProfilePage({super.key, this.onNavigateToCommunity, this.tabNotifier});

  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  final AuthRepository _authRepository = AuthRepository();
  final UserStateManager _userStateManager = UserStateManager.instance;
  final UserApiService _userApiService = UserApiService();

  UserModel? _currentUser;
  bool _isLoading = true;
  StreamSubscription<UserModel?>? _userSubscription;

  int _followingCount = 0;
  int _followerCount = 0;
  int _postCount = 0;

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
    _setupUserListener();
    widget.tabNotifier?.addListener(_onTabChanged);
  }

  @override
  void dispose() {
    widget.tabNotifier?.removeListener(_onTabChanged);
    _userSubscription?.cancel();
    super.dispose();
  }

  void _onTabChanged() {
    // 当"我"标签页被选中时（index == 3），刷新统计数据
    if (widget.tabNotifier?.value == 3) {
      _loadUserStats();
    }
  }

  /// 公开方法：供外部调用刷新统计数据
  void refreshStats() {
    _loadUserStats();
  }

  void _setupUserListener() {
    _userSubscription = _userStateManager.userStream.listen((user) {
      if (mounted && user != null) {
        setState(() {
          _currentUser = user;
        });
      }
    });
  }

  Future<void> _loadCurrentUser() async {
    final user = await _authRepository.getCurrentUser();
    if (mounted) {
      setState(() {
        _currentUser = user;
        _isLoading = false;
      });
      _loadUserStats();
    }
  }

  Future<void> _loadUserStats() async {
    if (_currentUser == null) return;
    try {
      final stats = await _userApiService.getUserStats(_currentUser!.id);
      if (mounted) {
        setState(() {
          _followingCount = stats.followingCount;
          _followerCount = stats.followerCount;
          _postCount = stats.postCount;
        });
      }
    } catch (e) {
      debugPrint('Failed to load user stats: $e');
    }
  }

  String _formatCount(int count) {
    if (count >= 10000) {
      return '${(count / 10000).toStringAsFixed(1)}w';
    } else if (count >= 1000) {
      return '${(count / 1000).toStringAsFixed(1)}k';
    }
    return '$count';
  }

  void _navigateToEditProfile() async {
    if (_currentUser == null) return;
    final result = await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => ProfileEditPage(user: _currentUser!),
      ),
    );
    if (result == true) {
      _loadCurrentUser();
    }
  }

  void _navigateToSettings() {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => const SettingsPage(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bgColor = isDark ? const Color(0xFF0F172A) : const Color(0xFFF8F9FA);

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        backgroundColor: bgColor,
        body: _isLoading
            ? const Center(child: CircularProgressIndicator(color: AppTheme.primary))
            : Stack(
                children: [
                  // 背景光晕
                  Positioned(
                    top: 80,
                    left: -40,
                    child: Container(
                      width: 200,
                      height: 200,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: AppTheme.primary.withValues(alpha: 0.08),
                      ),
                    ),
                  ),
                  Positioned(
                    bottom: 120,
                    right: -40,
                    child: Container(
                      width: 200,
                      height: 200,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: const Color(0xFF10B981).withValues(alpha: 0.06),
                      ),
                    ),
                  ),
                  // 主内容
                  ListView(
                    padding: EdgeInsets.zero,
                    children: [
                      SafeArea(
                        bottom: false,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 24),
                          child: Column(
                            children: [
                              const SizedBox(height: 24),
                              _buildUserHeader(isDark),
                              const SizedBox(height: 32),
                              _buildStatsSection(isDark),
                              const SizedBox(height: 28),
                              _buildFeatureCards(isDark),
                              const SizedBox(height: 16),
                              _buildCommunityBanner(isDark),
                              const SizedBox(height: 16),
                              _buildSettingsRow(isDark),
                              const SizedBox(height: 120),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
      ),
    );
  }

  /// 用户头部信息区 - 居中布局
  Widget _buildUserHeader(bool isDark) {
    final user = _currentUser;
    final displayName = user?.displayName ?? '未登录';
    final fullAvatarUrl = ApiConfig.getFullUrl(user?.avatarUrl);
    final nexusId = user?.username ?? 'Nexus_${user?.id ?? '000000'}';

    return Column(
      children: [
        // 头像
        GestureDetector(
          onTap: _navigateToEditProfile,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // 光晕效果
              Container(
                width: 140,
                height: 140,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppTheme.primary.withValues(alpha: 0.08),
                ),
              ),
              // 头像外圈
              Container(
                width: 128,
                height: 128,
                padding: const EdgeInsets.all(4),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(
                    begin: Alignment.topRight,
                    end: Alignment.bottomLeft,
                    colors: isDark
                        ? [const Color(0xFF374151), const Color(0xFF1F2937)]
                        : [const Color(0xFFE5E7EB), Colors.white],
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.08),
                      blurRadius: 24,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: ClipOval(
                  child: fullAvatarUrl.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: fullAvatarUrl,
                          fit: BoxFit.cover,
                          placeholder: (context, url) => _buildDefaultAvatar(displayName, isDark),
                          errorWidget: (context, url, error) => _buildDefaultAvatar(displayName, isDark),
                        )
                      : _buildDefaultAvatar(displayName, isDark),
                ),
              ),
            ],
          ),
        ),

        const SizedBox(height: 20),

        // 用户名
        Text(
          displayName,
          style: TextStyle(
            fontSize: 32,
            fontWeight: FontWeight.bold,
            color: isDark ? Colors.white : const Color(0xFF0F172A),
            letterSpacing: -0.5,
          ),
        ),

        const SizedBox(height: 6),

        // Nexus ID
        Text(
          'NEXUS ID: ${nexusId.toUpperCase()}',
          style: TextStyle(
            fontSize: 13,
            color: isDark ? const Color(0xFF64748B) : const Color(0xFF94A3B8),
            letterSpacing: 2.0,
            fontWeight: FontWeight.w500,
          ),
        ),

        const SizedBox(height: 20),

        // 操作按钮
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // 编辑资料按钮
            GestureDetector(
              onTap: _navigateToEditProfile,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 12),
                decoration: BoxDecoration(
                  color: isDark ? Colors.white : const Color(0xFF0F172A),
                  borderRadius: BorderRadius.circular(24),
                  boxShadow: [
                    BoxShadow(
                      color: (isDark ? Colors.white : const Color(0xFF0F172A)).withValues(alpha: 0.2),
                      blurRadius: 12,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Text(
                  '编辑资料',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: isDark ? const Color(0xFF0F172A) : Colors.white,
                  ),
                ),
              ),
            ),

            const SizedBox(width: 12),

            // 二维码按钮
            GestureDetector(
              onTap: () {},
              child: Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: isDark ? const Color(0xFF334155) : const Color(0xFFE2E8F0),
                    width: 1.5,
                  ),
                ),
                child: Icon(
                  Icons.qr_code_2,
                  size: 20,
                  color: isDark ? const Color(0xFF94A3B8) : const Color(0xFF64748B),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  /// 统计数据区 - 三栏卡片
  Widget _buildStatsSection(bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF1E293B) : Colors.white;

    return Row(
      children: [
        // 关注
        Expanded(
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 16),
            decoration: BoxDecoration(
              color: surfaceColor,
              borderRadius: BorderRadius.circular(16),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Column(
              children: [
                Text(
                  _formatCount(_followingCount),
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                    color: isDark ? Colors.white : const Color(0xFF0F172A),
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '关注',
                  style: TextStyle(
                    fontSize: 11,
                    color: isDark ? const Color(0xFF64748B) : const Color(0xFF94A3B8),
                    letterSpacing: 1.0,
                  ),
                ),
              ],
            ),
          ),
        ),

        const SizedBox(width: 12),

        // 粉丝 - 高亮
        Expanded(
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 16),
            decoration: BoxDecoration(
              color: surfaceColor,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: AppTheme.primary.withValues(alpha: 0.2),
                width: 1,
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Column(
              children: [
                Text(
                  _formatCount(_followerCount),
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                    color: AppTheme.primary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '粉丝',
                  style: TextStyle(
                    fontSize: 11,
                    color: AppTheme.primary,
                    letterSpacing: 1.0,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ),

        const SizedBox(width: 12),

        // 动态
        Expanded(
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 16),
            decoration: BoxDecoration(
              color: surfaceColor,
              borderRadius: BorderRadius.circular(16),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: Column(
              children: [
                Text(
                  _formatCount(_postCount),
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                    color: isDark ? Colors.white : const Color(0xFF0F172A),
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '动态',
                  style: TextStyle(
                    fontSize: 11,
                    color: isDark ? const Color(0xFF64748B) : const Color(0xFF94A3B8),
                    letterSpacing: 1.0,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  /// 功能卡片区 - 服务 + 已收藏 双卡片
  Widget _buildFeatureCards(bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF1E293B) : Colors.white;

    return SizedBox(
      height: 170,
      child: Row(
        children: [
          // 服务卡片
          Expanded(
            child: GestureDetector(
              onTap: _navigateToSettings,
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: surfaceColor,
                  borderRadius: BorderRadius.circular(24),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.06),
                      blurRadius: 20,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Stack(
                  children: [
                    // 背景图标
                    Positioned(
                      top: -8,
                      right: -8,
                      child: Icon(
                        Icons.medical_services_outlined,
                        size: 80,
                        color: AppTheme.primary.withValues(alpha: 0.08),
                      ),
                    ),
                    // 内容
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Container(
                          width: 40,
                          height: 40,
                          decoration: BoxDecoration(
                            color: AppTheme.primary.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(20),
                          ),
                          child: Icon(
                            Icons.medical_services_outlined,
                            size: 20,
                            color: AppTheme.primary,
                          ),
                        ),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '服务',
                              style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                                color: isDark ? Colors.white : const Color(0xFF0F172A),
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '管理实用工具',
                              style: TextStyle(
                                fontSize: 12,
                                color: isDark ? const Color(0xFF64748B) : const Color(0xFF94A3B8),
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
          ),

          const SizedBox(width: 12),

          // 已收藏卡片 - 深色风格
          Expanded(
            child: GestureDetector(
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const BookmarksPage()),
                );
              },
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: isDark ? const Color(0xFF1E293B) : const Color(0xFF0F172A),
                  borderRadius: BorderRadius.circular(24),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.15),
                      blurRadius: 20,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: Stack(
                  children: [
                    // 背景图标
                    Positioned(
                      bottom: -12,
                      right: -12,
                      child: Icon(
                        Icons.bookmark,
                        size: 80,
                        color: Colors.white.withValues(alpha: 0.08),
                      ),
                    ),
                    // 内容
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Container(
                          width: 40,
                          height: 40,
                          decoration: BoxDecoration(
                            color: Colors.white.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(20),
                          ),
                          child: const Icon(
                            Icons.bookmark,
                            size: 20,
                            color: Colors.white,
                          ),
                        ),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              '已收藏',
                              style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '我的收藏内容',
                              style: TextStyle(
                                fontSize: 12,
                                color: const Color(0xFF94A3B8),
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
          ),
        ],
      ),
    );
  }

  /// 社区横幅 - 翡翠绿渐变
  Widget _buildCommunityBanner(bool isDark) {
    return GestureDetector(
      onTap: () {
        widget.onNavigateToCommunity?.call();
      },
      child: Container(
        height: 110,
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: isDark
                ? [const Color(0xFF065F46), const Color(0xFF064E3B)]
                : [const Color(0xFF10B981), const Color(0xFF059669)],
          ),
          boxShadow: [
            BoxShadow(
              color: AppTheme.primary.withValues(alpha: 0.3),
              blurRadius: 20,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text(
                  '社区',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '加入讨论',
                  style: TextStyle(
                    fontSize: 14,
                    color: Colors.white.withValues(alpha: 0.8),
                  ),
                ),
              ],
            ),
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(24),
              ),
              child: const Icon(
                Icons.public,
                color: Colors.white,
                size: 24,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 设置入口行
  Widget _buildSettingsRow(bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF1E293B) : Colors.white;

    return GestureDetector(
      onTap: _navigateToSettings,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
        decoration: BoxDecoration(
          color: surfaceColor,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: isDark ? const Color(0xFF334155) : const Color(0xFFF1F5F9),
            width: 1,
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
            // 图标
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF334155) : const Color(0xFFF1F5F9),
                borderRadius: BorderRadius.circular(20),
              ),
              child: Icon(
                Icons.settings_outlined,
                size: 20,
                color: isDark ? const Color(0xFF94A3B8) : const Color(0xFF64748B),
              ),
            ),
            const SizedBox(width: 14),
            // 文字
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '设置',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: isDark ? Colors.white : const Color(0xFF0F172A),
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '偏好设置与账户',
                    style: TextStyle(
                      fontSize: 12,
                      color: isDark ? const Color(0xFF64748B) : const Color(0xFF94A3B8),
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right,
              size: 22,
              color: isDark ? const Color(0xFF475569) : const Color(0xFFCBD5E1),
            ),
          ],
        ),
      ),
    );
  }

  /// 默认头像
  Widget _buildDefaultAvatar(String displayName, bool isDark) {
    final initials = displayName.isNotEmpty ? displayName[0].toUpperCase() : '?';

    return Container(
      color: AppTheme.primary,
      child: Center(
        child: Text(
          initials,
          style: const TextStyle(
            fontSize: 40,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}
