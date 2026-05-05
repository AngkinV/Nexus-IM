import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/models/chat/chat_models.dart';

/// 用户资料详情页面
class UserProfilePage extends StatefulWidget {
  final ChatMemberModel user;
  final int chatId;

  const UserProfilePage({
    super.key,
    required this.user,
    required this.chatId,
  });

  @override
  State<UserProfilePage> createState() => _UserProfilePageState();
}

/// 获取用户显示名称
extension ChatMemberModelExtension on ChatMemberModel {
  String get displayName => nickname ?? username;
}

class _UserProfilePageState extends State<UserProfilePage> {
  // 开关状态
  bool _isMuted = false;
  bool _isPinned = false;
  bool _isBlocked = false;

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0A0A0A) : const Color(0xFFF6F8F6),
      body: Column(
        children: [
          _buildHeader(isDark),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.only(bottom: 40),
              child: Column(
                children: [
                  _buildProfileCard(isDark),
                  const SizedBox(height: 24),
                  _buildMessageSettings(isDark),
                  const SizedBox(height: 24),
                  _buildPrivacySettings(isDark),
                  const SizedBox(height: 24),
                  _buildPersonalInfo(isDark),
                  const SizedBox(height: 40),
                  _buildActionButtons(isDark),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: isDark
                  ? [
                      const Color(0xFF102215).withValues(alpha: 0.9),
                      const Color(0xFF0A0A0A).withValues(alpha: 0.9),
                    ]
                  : [
                      AppTheme.primary.withValues(alpha: 0.1),
                      Colors.white.withValues(alpha: 0.9),
                    ],
            ),
          ),
          child: SafeArea(
            bottom: false,
            child: Container(
              height: 56,
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Row(
                children: [
                  IconButton(
                    onPressed: () => Navigator.pop(context),
                    icon: Icon(
                      Icons.arrow_back_ios,
                      color: isDark ? Colors.white : Colors.black87,
                    ),
                  ),
                  const Expanded(
                    child: Text(
                      '用户资料',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 48),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildProfileCard(bool isDark) {
    final fullAvatarUrl = ApiConfig.getFullUrl(widget.user.avatarUrl);

    return Container(
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 20,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Column(
        children: [
          // 头像 - 带旋转效果的圆角方形
          Transform.rotate(
            angle: 0.08, // 约5度的旋转
            child: Container(
              width: 100,
              height: 100,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(24),
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    AppTheme.primary,
                    AppTheme.primary.withValues(alpha: 0.7),
                  ],
                ),
                boxShadow: [
                  BoxShadow(
                    color: AppTheme.primary.withValues(alpha: 0.3),
                    blurRadius: 20,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(24),
                child: fullAvatarUrl.isNotEmpty
                    ? CachedNetworkImage(
                        imageUrl: fullAvatarUrl,
                        fit: BoxFit.cover,
                        placeholder: (_, __) => _buildDefaultAvatar(widget.user.displayName),
                        errorWidget: (_, __, ___) =>
                            _buildDefaultAvatar(widget.user.displayName),
                      )
                    : _buildDefaultAvatar(widget.user.displayName),
              ),
            ),
          ),
          const SizedBox(height: 20),

          // 用户名
          Text(
            widget.user.displayName,
            style: TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.bold,
              color: isDark ? Colors.white : const Color(0xFF111813),
            ),
          ),
          const SizedBox(height: 8),

          // Nexus ID
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: isDark
                  ? AppTheme.primary.withValues(alpha: 0.15)
                  : const Color(0xFFD1FAE5),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.alternate_email,
                  size: 14,
                  color: AppTheme.primary,
                ),
                const SizedBox(width: 4),
                Text(
                  'Nexus ID: ${widget.user.username}',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: AppTheme.primary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // 个人签名
          Text(
            '这个人很懒，什么都没写~',
            style: TextStyle(
              fontSize: 14,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
              height: 1.5,
            ),
            textAlign: TextAlign.center,
          ),

          // 在线状态
          const SizedBox(height: 16),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: widget.user.isOnline ? AppTheme.primary : Colors.grey,
                ),
              ),
              const SizedBox(width: 6),
              Text(
                widget.user.isOnline ? '在线' : '离线',
                style: TextStyle(
                  fontSize: 13,
                  color: widget.user.isOnline ? AppTheme.primary : Colors.grey,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ],
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
          fontSize: 40,
        ),
      ),
    );
  }

  Widget _buildMessageSettings(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16, top: 16, bottom: 8),
            child: Text(
              '消息设置',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: isDark ? Colors.grey[400] : Colors.grey[600],
              ),
            ),
          ),
          _buildSwitchItem(
            isDark,
            icon: Icons.notifications_off_outlined,
            title: '消息免打扰',
            subtitle: '开启后将不会收到消息通知',
            value: _isMuted,
            onChanged: (value) => setState(() => _isMuted = value),
          ),
          _buildDivider(isDark),
          _buildSwitchItem(
            isDark,
            icon: Icons.push_pin_outlined,
            title: '置顶聊天',
            subtitle: '将此会话置顶显示',
            value: _isPinned,
            onChanged: (value) => setState(() => _isPinned = value),
          ),
        ],
      ),
    );
  }

  Widget _buildPrivacySettings(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16, top: 16, bottom: 8),
            child: Text(
              '隐私与权限',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: isDark ? Colors.grey[400] : Colors.grey[600],
              ),
            ),
          ),
          _buildSwitchItem(
            isDark,
            icon: Icons.block_outlined,
            title: '拉黑此用户',
            subtitle: '拉黑后将无法接收对方消息',
            value: _isBlocked,
            onChanged: (value) => setState(() => _isBlocked = value),
            isDestructive: true,
          ),
          _buildDivider(isDark),
          _buildActionItem(
            isDark,
            icon: Icons.report_outlined,
            title: '举报用户',
            subtitle: '举报违规或不当行为',
            onTap: () {
              // TODO: 举报功能
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('举报功能开发中...')),
              );
            },
            isDestructive: true,
          ),
        ],
      ),
    );
  }

  Widget _buildPersonalInfo(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16, top: 16, bottom: 8),
            child: Text(
              '个人信息',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: isDark ? Colors.grey[400] : Colors.grey[600],
              ),
            ),
          ),
          _buildInfoItem(
            isDark,
            icon: Icons.person_outline,
            title: '昵称',
            value: widget.user.nickname ?? widget.user.username,
          ),
          _buildDivider(isDark),
          _buildInfoItem(
            isDark,
            icon: Icons.alternate_email,
            title: '用户名',
            value: '@${widget.user.username}',
          ),
          _buildDivider(isDark),
          _buildInfoItem(
            isDark,
            icon: Icons.badge_outlined,
            title: 'Nexus ID',
            value: widget.user.id.toString(),
          ),
        ],
      ),
    );
  }

  Widget _buildSwitchItem(
    bool isDark, {
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
    bool isDestructive = false,
  }) {
    final color = isDestructive ? Colors.red : (isDark ? Colors.white : const Color(0xFF111813));

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: isDestructive
                  ? Colors.red.withValues(alpha: 0.1)
                  : (isDark
                      ? AppTheme.primary.withValues(alpha: 0.15)
                      : const Color(0xFFD1FAE5)),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(
              icon,
              size: 20,
              color: isDestructive ? Colors.red : AppTheme.primary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                    color: color,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark ? Colors.grey[500] : Colors.grey[500],
                  ),
                ),
              ],
            ),
          ),
          _buildIOSSwitch(value, onChanged, isDestructive: isDestructive),
        ],
      ),
    );
  }

  Widget _buildActionItem(
    bool isDark, {
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    bool isDestructive = false,
  }) {
    final color = isDestructive ? Colors.red : (isDark ? Colors.white : const Color(0xFF111813));

    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: isDestructive
                    ? Colors.red.withValues(alpha: 0.1)
                    : (isDark
                        ? AppTheme.primary.withValues(alpha: 0.15)
                        : const Color(0xFFD1FAE5)),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(
                icon,
                size: 20,
                color: isDestructive ? Colors.red : AppTheme.primary,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                      color: color,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: isDark ? Colors.grey[500] : Colors.grey[500],
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right,
              size: 20,
              color: isDark ? Colors.grey[600] : Colors.grey[400],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoItem(
    bool isDark, {
    required IconData icon,
    required String title,
    required String value,
  }) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: isDark
                  ? AppTheme.primary.withValues(alpha: 0.15)
                  : const Color(0xFFD1FAE5),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(
              icon,
              size: 20,
              color: AppTheme.primary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              title,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.white : const Color(0xFF111813),
              ),
            ),
          ),
          Text(
            value,
            style: TextStyle(
              fontSize: 14,
              color: isDark ? Colors.grey[400] : Colors.grey[600],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildIOSSwitch(bool value, ValueChanged<bool> onChanged, {bool isDestructive = false}) {
    final activeColor = isDestructive ? Colors.red : AppTheme.primary;

    return GestureDetector(
      onTap: () => onChanged(!value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        width: 51,
        height: 31,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(31),
          color: value ? activeColor : const Color(0xFFE9E9EA),
        ),
        child: AnimatedAlign(
          duration: const Duration(milliseconds: 200),
          alignment: value ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            margin: const EdgeInsets.all(2),
            width: 27,
            height: 27,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.white,
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.15),
                  blurRadius: 8,
                  offset: const Offset(0, 3),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildActionButtons(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        children: [
          // 发送消息按钮
          Expanded(
            child: GestureDetector(
              onTap: () {
                // 返回聊天页面
                Navigator.pop(context);
              },
              child: Container(
                height: 52,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      AppTheme.primary,
                      AppTheme.primary.withValues(alpha: 0.8),
                    ],
                  ),
                  borderRadius: BorderRadius.circular(26),
                  boxShadow: [
                    BoxShadow(
                      color: AppTheme.primary.withValues(alpha: 0.3),
                      blurRadius: 12,
                      offset: const Offset(0, 4),
                    ),
                  ],
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.chat_bubble_outline, color: Colors.white, size: 20),
                    SizedBox(width: 8),
                    Text(
                      '发送消息',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          // 音频通话按钮
          GestureDetector(
            onTap: () {
              // TODO: 音频通话功能
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('通话功能开发中...')),
              );
            },
            child: Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF374151) : const Color(0xFFF3F4F6),
                borderRadius: BorderRadius.circular(26),
              ),
              child: Icon(
                Icons.call_outlined,
                color: isDark ? Colors.white : Colors.grey[700],
                size: 24,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDivider(bool isDark) {
    return Divider(
      height: 1,
      thickness: 1,
      color: isDark ? const Color(0xFF374151) : const Color(0xFFF3F4F6),
      indent: 68,
      endIndent: 16,
    );
  }
}
