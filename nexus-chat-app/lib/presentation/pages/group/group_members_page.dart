import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/datasources/remote/group_api_service.dart';
import '../../../data/models/group/group_models.dart';
import '../../../data/repositories/auth_repository.dart';

/// 群成员列表页面
class GroupMembersPage extends StatefulWidget {
  final int groupId;
  final int creatorId;

  const GroupMembersPage({
    super.key,
    required this.groupId,
    required this.creatorId,
  });

  @override
  State<GroupMembersPage> createState() => _GroupMembersPageState();
}

class _GroupMembersPageState extends State<GroupMembersPage> {
  final GroupApiService _groupApiService = GroupApiService();
  final AuthRepository _authRepository = AuthRepository();

  List<GroupMemberModel> _members = [];
  bool _isLoading = true;
  int? _currentUserId;
  bool _isAdmin = false;

  @override
  void initState() {
    super.initState();
    _loadMembers();
  }

  Future<void> _loadMembers() async {
    final userId = await _authRepository.getCurrentUserId();
    _currentUserId = userId;

    try {
      final members = await _groupApiService.getGroupMembers(widget.groupId);

      // 排序: 群主 > 管理员 > 普通成员
      members.sort((a, b) {
        if (a.isOwner) return -1;
        if (b.isOwner) return 1;
        if (a.isAdmin && !b.isAdmin) return -1;
        if (!a.isAdmin && b.isAdmin) return 1;
        return a.displayName.compareTo(b.displayName);
      });

      // 检查当前用户是否是管理员
      final currentMember = members.where((m) => m.id == userId).firstOrNull;
      _isAdmin = currentMember?.isAdmin ?? false;

      setState(() {
        _members = members;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载失败: $e')),
        );
      }
    }
  }

  Future<void> _removeMember(GroupMemberModel member) async {
    if (_currentUserId == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('移除成员'),
        content: Text('确定要将 ${member.displayName} 移出群组吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('移除'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await _groupApiService.removeMember(widget.groupId, _currentUserId!, member.id);
      setState(() {
        _members.removeWhere((m) => m.id == member.id);
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已移除 ${member.displayName}')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('移除失败: $e')),
        );
      }
    }
  }

  Future<void> _toggleAdmin(GroupMemberModel member) async {
    if (_currentUserId == null) return;

    final newAdminStatus = !member.isAdmin;
    final action = newAdminStatus ? '设为管理员' : '取消管理员';

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(action),
        content: Text('确定要将 ${member.displayName} $action吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确定'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await _groupApiService.setAdmin(widget.groupId, _currentUserId!, member.id, newAdminStatus);
      await _loadMembers(); // 重新加载成员列表
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已${action} ${member.displayName}')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败: $e')),
        );
      }
    }
  }

  void _showMemberOptions(GroupMemberModel member) {
    final isOwner = widget.creatorId == _currentUserId;
    final isSelf = member.id == _currentUserId;

    // 群主不能对自己操作，普通成员不能操作他人
    if (isSelf || member.isOwner) return;
    if (!_isAdmin && !isOwner) return;

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        final isDark = Theme.of(context).brightness == Brightness.dark;
        return Container(
          margin: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1F2937) : Colors.white,
            borderRadius: BorderRadius.circular(16),
          ),
          child: SafeArea(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // 只有群主可以设置管理员
                if (isOwner)
                  ListTile(
                    leading: Icon(
                      member.isAdmin ? Icons.person_remove : Icons.admin_panel_settings,
                      color: AppTheme.primary,
                    ),
                    title: Text(member.isAdmin ? '取消管理员' : '设为管理员'),
                    onTap: () {
                      Navigator.pop(context);
                      _toggleAdmin(member);
                    },
                  ),
                // 只有群主可以转让群主
                if (isOwner)
                  ListTile(
                    leading: const Icon(Icons.swap_horiz, color: Color(0xFFF59E0B)),
                    title: const Text('转让群主', style: TextStyle(color: Color(0xFFF59E0B))),
                    onTap: () {
                      Navigator.pop(context);
                      _transferOwnership(member);
                    },
                  ),
                ListTile(
                  leading: const Icon(Icons.remove_circle_outline, color: Colors.red),
                  title: const Text('移出群组', style: TextStyle(color: Colors.red)),
                  onTap: () {
                    Navigator.pop(context);
                    _removeMember(member);
                  },
                ),
                const SizedBox(height: 8),
                ListTile(
                  title: const Text('取消', textAlign: TextAlign.center),
                  onTap: () => Navigator.pop(context),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Future<void> _transferOwnership(GroupMemberModel member) async {
    if (_currentUserId == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final isDark = Theme.of(context).brightness == Brightness.dark;
        return AlertDialog(
          backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
          title: const Text('转让群主'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('确定要将群主转让给 ${member.displayName} 吗？'),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFFFEF3C7),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.warning_amber, color: Color(0xFFD97706), size: 20),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '转让后，你将成为普通成员',
                        style: TextStyle(
                          fontSize: 13,
                          color: Color(0xFFD97706),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              style: TextButton.styleFrom(foregroundColor: const Color(0xFFF59E0B)),
              child: const Text('确认转让'),
            ),
          ],
        );
      },
    );

    if (confirmed != true) return;

    try {
      await _groupApiService.transferOwnership(widget.groupId, _currentUserId!, member.id);
      await _loadMembers(); // 重新加载成员列表
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已将群主转让给 ${member.displayName}')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('转让失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0A0A0A) : const Color(0xFFF6F8F6),
      body: Column(
        children: [
          _buildHeader(isDark),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _buildMemberList(isDark),
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
          color: isDark
              ? const Color(0xFF0A0A0A).withValues(alpha: 0.8)
              : Colors.white.withValues(alpha: 0.8),
          child: SafeArea(
            bottom: false,
            child: Container(
              height: 56,
              padding: const EdgeInsets.symmetric(horizontal: 4),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark ? Colors.grey[800]! : Colors.grey[200]!,
                  ),
                ),
              ),
              child: Row(
                children: [
                  IconButton(
                    onPressed: () => Navigator.pop(context),
                    icon: Icon(
                      Icons.arrow_back_ios,
                      color: isDark ? Colors.white : Colors.black87,
                    ),
                  ),
                  Expanded(
                    child: Text(
                      '群成员 (${_members.length})',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
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

  Widget _buildMemberList(bool isDark) {
    // 分组成员 - 使用 creatorId 判断群主（更可靠）
    final owner = _members.where((m) => m.id == widget.creatorId || m.isOwner).toList();
    final ownerIds = owner.map((m) => m.id).toSet();
    final admins = _members.where((m) => m.isAdmin && !ownerIds.contains(m.id)).toList();
    final regularMembers = _members.where((m) => !m.isAdmin && !ownerIds.contains(m.id)).toList();

    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 群主
          if (owner.isNotEmpty) ...[
            _buildSectionHeader('群主', null, isDark),
            ...owner.map((m) => _buildMemberItem(m, isDark)),
          ],

          // 管理员
          if (admins.isNotEmpty) ...[
            _buildSectionHeader('管理员', admins.length, isDark),
            ...admins.map((m) => _buildMemberItem(m, isDark)),
          ],

          // 普通成员
          if (regularMembers.isNotEmpty) ...[
            _buildSectionHeader('成员', regularMembers.length, isDark),
            ...regularMembers.map((m) => _buildMemberItem(m, isDark)),
          ],
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title, int? count, bool isDark) {
    return Padding(
      padding: const EdgeInsets.only(left: 16, right: 16, top: 16, bottom: 8),
      child: Text(
        count != null ? '$title ($count)' : title,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w600,
          color: isDark ? Colors.grey[400] : Colors.grey[600],
        ),
      ),
    );
  }

  Widget _buildMemberItem(GroupMemberModel member, bool isDark) {
    // 使用 creatorId 判断群主（更可靠）
    final isOwner = member.id == widget.creatorId || member.isOwner;
    final isAdmin = member.isAdmin && !isOwner;

    return GestureDetector(
      onLongPress: () => _showMemberOptions(member),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            // 头像
            Stack(
              children: [
                Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    shape: isOwner ? BoxShape.rectangle : BoxShape.circle,
                    borderRadius: isOwner ? BorderRadius.circular(10) : null,
                    color: _getAvatarColor(member.displayName),
                  ),
                  child: ClipRRect(
                    borderRadius: isOwner
                        ? BorderRadius.circular(10)
                        : BorderRadius.circular(24),
                    child: member.avatarUrl != null
                        ? CachedNetworkImage(
                            imageUrl: member.avatarUrl!,
                            fit: BoxFit.cover,
                            placeholder: (context, url) => _buildDefaultAvatar(member.displayName),
                            errorWidget: (context, url, error) => _buildDefaultAvatar(member.displayName),
                          )
                        : _buildDefaultAvatar(member.displayName),
                  ),
                ),
                // 管理员徽章 (仅管理员显示)
                if (isAdmin)
                  Positioned(
                    bottom: 0,
                    right: 0,
                    child: Container(
                      width: 16,
                      height: 16,
                      decoration: BoxDecoration(
                        color: AppTheme.primary,
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: isDark ? const Color(0xFF0A0A0A) : Colors.white,
                          width: 2,
                        ),
                      ),
                      child: const Icon(
                        Icons.check,
                        size: 10,
                        color: Colors.white,
                      ),
                    ),
                  ),
                // 在线状态 (不与管理员徽章冲突)
                if (member.isOnline && !isAdmin)
                  Positioned(
                    bottom: 0,
                    right: 0,
                    child: Container(
                      width: 14,
                      height: 14,
                      decoration: BoxDecoration(
                        color: AppTheme.primary,
                        shape: BoxShape.circle,
                        border: Border.all(color: isDark ? const Color(0xFF0A0A0A) : Colors.white, width: 2),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 12),
            // 名称和角色
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        member.displayName,
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w500,
                          color: isDark ? Colors.white : Colors.black87,
                        ),
                      ),
                      if (member.id == _currentUserId)
                        Container(
                          margin: const EdgeInsets.only(left: 8),
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: isDark ? Colors.grey[700] : Colors.grey[200],
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            '我',
                            style: TextStyle(
                              fontSize: 10,
                              color: isDark ? Colors.grey[300] : Colors.grey[600],
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '@${member.username}',
                    style: TextStyle(
                      fontSize: 13,
                      color: Colors.grey[500],
                    ),
                  ),
                ],
              ),
            ),
            // 角色标签
            _buildRoleTag(member, isDark),
          ],
        ),
      ),
    );
  }

  Widget _buildRoleTag(GroupMemberModel member, bool isDark) {
    // 使用 creatorId 判断群主
    final isOwner = member.id == widget.creatorId || member.isOwner;
    final isAdmin = member.isAdmin && !isOwner;

    if (isOwner) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: const Color(0xFFFEF3C7),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.star, size: 12, color: Color(0xFFD97706)),
            SizedBox(width: 4),
            Text(
              '群主',
              style: TextStyle(
                fontSize: 12,
                color: Color(0xFFD97706),
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      );
    } else if (isAdmin) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: const Color(0xFFD1FAE5),
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.shield, size: 12, color: Color(0xFF059669)),
            SizedBox(width: 4),
            Text(
              '管理员',
              style: TextStyle(
                fontSize: 12,
                color: Color(0xFF059669),
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      );
    }
    return const SizedBox.shrink();
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
    if (name.isEmpty) return AppTheme.primary;
    final colors = [
      const Color(0xFF10B981),
      const Color(0xFF3B82F6),
      const Color(0xFFF59E0B),
      const Color(0xFF8B5CF6),
      const Color(0xFF14B8A6),
      const Color(0xFFEC4899),
    ];
    return colors[name.codeUnitAt(0) % colors.length];
  }
}
