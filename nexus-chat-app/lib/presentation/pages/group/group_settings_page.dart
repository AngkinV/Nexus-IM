import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/datasources/remote/group_api_service.dart';
import '../../../data/datasources/remote/file_api_service.dart';
import '../../../data/models/group/group_models.dart';
import '../../../data/repositories/auth_repository.dart';
import 'invite_members_page.dart';
import 'group_members_page.dart';

/// 群组设置页面
class GroupSettingsPage extends StatefulWidget {
  final int groupId;
  final String groupName;

  const GroupSettingsPage({
    super.key,
    required this.groupId,
    required this.groupName,
  });

  @override
  State<GroupSettingsPage> createState() => _GroupSettingsPageState();
}

class _GroupSettingsPageState extends State<GroupSettingsPage> {
  final GroupApiService _groupApiService = GroupApiService();
  final FileApiService _fileApiService = FileApiService();
  final AuthRepository _authRepository = AuthRepository();
  final ImagePicker _imagePicker = ImagePicker();

  GroupDetailModel? _groupDetail;
  bool _isLoading = true;
  int? _currentUserId;
  bool _isAdmin = false;

  // 开关状态
  bool _isMuted = false;
  bool _isPinned = false;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    final userId = await _authRepository.getCurrentUserId();
    _currentUserId = userId;

    try {
      final groupDetail = await _groupApiService.getGroupById(widget.groupId);

      // 检查当前用户是否是管理员
      final currentMember = groupDetail.members.where((m) => m.id == userId).firstOrNull;
      _isAdmin = currentMember?.isAdmin ?? false;

      setState(() {
        _groupDetail = groupDetail;
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

  // ==================== 邀请成员 ====================
  Future<void> _inviteMembers() async {
    final result = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (context) => InviteMembersPage(
          groupId: widget.groupId,
          existingMembers: _groupDetail?.members ?? [],
        ),
      ),
    );

    if (result == true) {
      _loadData(); // 重新加载数据
    }
  }

  // ==================== 查看全部成员 ====================
  Future<void> _viewAllMembers() async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => GroupMembersPage(
          groupId: widget.groupId,
          creatorId: _groupDetail?.creatorId ?? 0,
        ),
      ),
    );
    _loadData(); // 返回后重新加载
  }

  // ==================== 修改群名称 ====================
  Future<void> _editGroupName() async {
    if (!_isAdmin && _groupDetail?.creatorId != _currentUserId) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('只有管理员可以修改群名称')),
      );
      return;
    }

    final controller = TextEditingController(text: _groupDetail?.name ?? '');
    final isDark = Theme.of(context).brightness == Brightness.dark;

    final newName = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
        title: const Text('修改群名称'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 30,
          decoration: const InputDecoration(
            hintText: '请输入群名称',
            counterText: '',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('确定'),
          ),
        ],
      ),
    );

    if (newName != null && newName.isNotEmpty && newName != _groupDetail?.name) {
      try {
        await _groupApiService.updateGroup(
          groupId: widget.groupId,
          userId: _currentUserId!,
          name: newName,
        );
        await _loadData();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('群名称已更新')),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('修改失败: $e')),
          );
        }
      }
    }
  }

  // ==================== 修改群头像 ====================
  Future<void> _editGroupAvatar() async {
    if (!_isAdmin && _groupDetail?.creatorId != _currentUserId) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('只有管理员可以修改群头像')),
      );
      return;
    }

    final source = await showModalBottomSheet<ImageSource>(
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
                ListTile(
                  leading: const Icon(Icons.camera_alt),
                  title: const Text('拍照'),
                  onTap: () => Navigator.pop(context, ImageSource.camera),
                ),
                ListTile(
                  leading: const Icon(Icons.photo_library),
                  title: const Text('从相册选择'),
                  onTap: () => Navigator.pop(context, ImageSource.gallery),
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

    if (source == null) return;

    try {
      final XFile? image = await _imagePicker.pickImage(
        source: source,
        maxWidth: 512,
        maxHeight: 512,
        imageQuality: 80,
      );

      if (image == null) return;

      // 显示加载指示器
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('正在上传头像...')),
        );
      }

      // 上传图片
      final avatarUrl = await _fileApiService.uploadImage(image.path);

      // 更新群头像
      await _groupApiService.updateGroup(
        groupId: widget.groupId,
        userId: _currentUserId!,
        avatar: avatarUrl,
      );

      await _loadData();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('群头像已更新')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('上传失败: $e')),
        );
      }
    }
  }

  // ==================== 显示群二维码 ====================
  void _showGroupQRCode() {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final qrData = 'nexus://group/${widget.groupId}';

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
        title: const Text('群二维码', textAlign: TextAlign.center),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
              ),
              child: QrImageView(
                data: qrData,
                version: QrVersions.auto,
                size: 200,
                backgroundColor: Colors.white,
              ),
            ),
            const SizedBox(height: 16),
            Text(
              _groupDetail?.name ?? widget.groupName,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '扫描二维码加入群组',
              style: TextStyle(
                fontSize: 13,
                color: Colors.grey[500],
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  // ==================== 编辑群公告 ====================
  Future<void> _editAnnouncement() async {
    if (!_isAdmin && _groupDetail?.creatorId != _currentUserId) {
      // 非管理员只能查看
      _showAnnouncementDetail();
      return;
    }

    final controller = TextEditingController(text: _groupDetail?.description ?? '');
    final isDark = Theme.of(context).brightness == Brightness.dark;

    final newDescription = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
        title: const Text('编辑群公告'),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLines: 5,
          maxLength: 500,
          decoration: const InputDecoration(
            hintText: '请输入群公告内容',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('发布'),
          ),
        ],
      ),
    );

    if (newDescription != null && newDescription != _groupDetail?.description) {
      try {
        await _groupApiService.updateGroup(
          groupId: widget.groupId,
          userId: _currentUserId!,
          description: newDescription,
        );
        await _loadData();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('群公告已更新')),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('更新失败: $e')),
          );
        }
      }
    }
  }

  void _showAnnouncementDetail() {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
        title: const Text('群公告'),
        content: Text(
          _groupDetail?.description?.isNotEmpty == true
              ? _groupDetail!.description!
              : '暂无公告',
          style: TextStyle(
            fontSize: 15,
            height: 1.5,
            color: isDark ? Colors.grey[300] : Colors.grey[700],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  // ==================== 退出群组 ====================
  Future<void> _leaveGroup() async {
    if (_currentUserId == null) return;

    final isOwner = _groupDetail?.creatorId == _currentUserId;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(isOwner ? '解散群组' : '退出群组'),
        content: Text(isOwner
            ? '确定要解散群组吗？此操作不可恢复。'
            : '确定要退出群组吗？退出后将不再接收此群组的消息。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('确定'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      if (isOwner) {
        await _groupApiService.deleteGroup(widget.groupId, _currentUserId!);
      } else {
        await _groupApiService.leaveGroup(widget.groupId, _currentUserId!);
      }

      if (mounted) {
        Navigator.of(context).popUntil((route) => route.isFirst);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('操作失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF102215) : const Color(0xFFF6F8F6),
      body: Column(
        children: [
          _buildHeader(isDark),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _buildContent(isDark),
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
              ? const Color(0xFF102215).withValues(alpha: 0.8)
              : const Color(0xFFF6F8F6).withValues(alpha: 0.8),
          child: SafeArea(
            bottom: false,
            child: Container(
              height: 56,
              padding: const EdgeInsets.symmetric(horizontal: 4),
              decoration: BoxDecoration(
                border: Border(
                  bottom: BorderSide(
                    color: isDark
                        ? Colors.grey[800]!.withValues(alpha: 0.5)
                        : Colors.grey[200]!.withValues(alpha: 0.5),
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
                  const Spacer(),
                  Text(
                    '群组设置',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: isDark ? Colors.white : const Color(0xFF111813),
                    ),
                  ),
                  const Spacer(),
                  const SizedBox(width: 48),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildContent(bool isDark) {
    final isOwner = _groupDetail?.creatorId == _currentUserId;

    return SingleChildScrollView(
      padding: const EdgeInsets.only(bottom: 40),
      child: Column(
        children: [
          _buildMemberSection(isDark),
          const SizedBox(height: 24),
          _buildInfoSection(isDark),
          const SizedBox(height: 24),
          _buildAnnouncementSection(isDark),
          // 群主专属管理模块
          if (isOwner) ...[
            const SizedBox(height: 24),
            _buildAdminSection(isDark),
          ],
          const SizedBox(height: 24),
          _buildControlSection(isDark),
          const SizedBox(height: 40),
          _buildLeaveButton(isDark),
        ],
      ),
    );
  }

  Widget _buildMemberSection(bool isDark) {
    final members = _groupDetail?.members ?? [];
    final memberCount = _groupDetail?.memberCount ?? members.length;

    return Padding(
      padding: const EdgeInsets.only(top: 16, left: 16, right: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 12),
            child: Text(
              '成员 ($memberCount)',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: isDark ? Colors.white : const Color(0xFF111813),
              ),
            ),
          ),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: isDark ? const Color(0xFF1F2937) : Colors.white,
              borderRadius: BorderRadius.circular(12),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.04),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  _buildInviteButton(isDark),
                  const SizedBox(width: 20),
                  ...members.take(4).map((member) => Padding(
                        padding: const EdgeInsets.only(right: 20),
                        child: _buildMemberAvatar(member, isDark),
                      )),
                  if (members.length > 4) _buildViewAllButton(isDark),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInviteButton(bool isDark) {
    return GestureDetector(
      onTap: _inviteMembers,
      child: Column(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: AppTheme.primary,
                width: 2,
              ),
              color: AppTheme.primary.withValues(alpha: 0.05),
            ),
            child: Icon(
              Icons.add,
              size: 28,
              color: AppTheme.primary,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '邀请',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: isDark ? Colors.white70 : Colors.black54,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMemberAvatar(GroupMemberModel member, bool isDark) {
    // 使用 creatorId 判断群主（更可靠）
    final isOwner = member.id == _groupDetail?.creatorId || member.isOwner;
    final isAdmin = member.isAdmin && !isOwner;

    return Column(
      children: [
        Stack(
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                shape: isOwner ? BoxShape.rectangle : BoxShape.circle,
                borderRadius: isOwner ? BorderRadius.circular(12) : null,
                color: _getAvatarColor(member.displayName),
              ),
              child: ClipRRect(
                borderRadius: isOwner
                    ? BorderRadius.circular(12)
                    : BorderRadius.circular(28),
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
            // 管理员徽章 (仅管理员显示，群主通过方形头像区分)
            if (isAdmin)
              Positioned(
                bottom: 0,
                right: 0,
                child: Container(
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(
                    color: AppTheme.primary,
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: isDark ? const Color(0xFF1F2937) : Colors.white,
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
          ],
        ),
        const SizedBox(height: 8),
        SizedBox(
          width: 56,
          child: Text(
            member.displayName,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: isDark ? Colors.white70 : Colors.black54,
            ),
            textAlign: TextAlign.center,
            overflow: TextOverflow.ellipsis,
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
          fontSize: 22,
        ),
      ),
    );
  }

  Widget _buildViewAllButton(bool isDark) {
    return GestureDetector(
      onTap: _viewAllMembers,
      child: Column(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isDark ? const Color(0xFF374151) : const Color(0xFFF3F4F6),
            ),
            child: Icon(
              Icons.more_horiz,
              color: isDark ? Colors.grey[400] : Colors.grey[500],
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '查看全部',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w500,
              color: AppTheme.primary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoSection(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        borderRadius: BorderRadius.circular(12),
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
          _buildInfoItem(
            isDark,
            title: '群聊名称',
            value: _groupDetail?.name ?? widget.groupName,
            onTap: _editGroupName,
          ),
          _buildDivider(isDark),
          _buildInfoItemWithAvatar(
            isDark,
            title: '群头像',
            avatarUrl: _groupDetail?.avatar,
            onTap: _editGroupAvatar,
          ),
          _buildDivider(isDark),
          _buildInfoItemWithIcon(
            isDark,
            title: '群二维码',
            icon: Icons.qr_code_2,
            onTap: _showGroupQRCode,
          ),
        ],
      ),
    );
  }

  Widget _buildInfoItem(bool isDark, {
    required String title,
    required String value,
    VoidCallback? onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.white : const Color(0xFF111813),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Flexible(
                    child: Text(
                      value,
                      style: TextStyle(
                        fontSize: 14,
                        color: isDark ? Colors.grey[400] : Colors.grey[500],
                      ),
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.right,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    Icons.chevron_right,
                    size: 20,
                    color: isDark ? Colors.grey[600] : Colors.grey[300],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoItemWithAvatar(bool isDark, {
    required String title,
    String? avatarUrl,
    VoidCallback? onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.white : const Color(0xFF111813),
              ),
            ),
            const Spacer(),
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(8),
                color: AppTheme.primary,
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: avatarUrl != null
                    ? CachedNetworkImage(
                        imageUrl: avatarUrl,
                        fit: BoxFit.cover,
                        placeholder: (context, url) => const Icon(
                          Icons.group,
                          color: Colors.white,
                        ),
                        errorWidget: (context, url, error) => const Icon(
                          Icons.group,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(
                        Icons.group,
                        color: Colors.white,
                      ),
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              Icons.chevron_right,
              size: 20,
              color: isDark ? Colors.grey[600] : Colors.grey[300],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoItemWithIcon(bool isDark, {
    required String title,
    required IconData icon,
    VoidCallback? onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.white : const Color(0xFF111813),
              ),
            ),
            const Spacer(),
            Icon(
              icon,
              size: 20,
              color: isDark ? Colors.grey[400] : Colors.grey[500],
            ),
            const SizedBox(width: 8),
            Icon(
              Icons.chevron_right,
              size: 20,
              color: isDark ? Colors.grey[600] : Colors.grey[300],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAnnouncementSection(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: GestureDetector(
        onTap: _editAnnouncement,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '群公告',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: isDark ? Colors.white : const Color(0xFF111813),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _groupDetail?.description?.isNotEmpty == true
                          ? _groupDetail!.description!
                          : '暂无公告',
                      style: TextStyle(
                        fontSize: 14,
                        color: isDark ? Colors.grey[400] : Colors.grey[500],
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Icon(
                Icons.chevron_right,
                size: 20,
                color: isDark ? Colors.grey[600] : Colors.grey[300],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAdminSection(bool isDark) {
    final adminCount = _groupDetail?.members.where((m) => m.isAdmin && m.id != _groupDetail?.creatorId).length ?? 0;

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        borderRadius: BorderRadius.circular(12),
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
            padding: const EdgeInsets.only(left: 16, top: 12, bottom: 4),
            child: Text(
              '群管理',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
            ),
          ),
          // 管理员设置
          GestureDetector(
            onTap: _viewAllMembers,
            behavior: HitTestBehavior.opaque,
            child: Container(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: AppTheme.primary.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Icon(
                      Icons.admin_panel_settings,
                      size: 20,
                      color: AppTheme.primary,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '管理员设置',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w500,
                            color: isDark ? Colors.white : const Color(0xFF111813),
                          ),
                        ),
                        Text(
                          '当前 $adminCount 位管理员',
                          style: TextStyle(
                            fontSize: 13,
                            color: isDark ? Colors.grey[500] : Colors.grey[400],
                          ),
                        ),
                      ],
                    ),
                  ),
                  Icon(
                    Icons.chevron_right,
                    size: 20,
                    color: isDark ? Colors.grey[600] : Colors.grey[300],
                  ),
                ],
              ),
            ),
          ),
          _buildDivider(isDark),
          // 转让群主
          GestureDetector(
            onTap: _showTransferOwnershipDialog,
            behavior: HitTestBehavior.opaque,
            child: Container(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: const Color(0xFFFEF3C7),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Icon(
                      Icons.swap_horiz,
                      size: 20,
                      color: Color(0xFFD97706),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      '转让群主',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: isDark ? Colors.white : const Color(0xFF111813),
                      ),
                    ),
                  ),
                  Icon(
                    Icons.chevron_right,
                    size: 20,
                    color: isDark ? Colors.grey[600] : Colors.grey[300],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  // 显示转让群主对话框
  Future<void> _showTransferOwnershipDialog() async {
    final members = _groupDetail?.members
        .where((m) => m.id != _currentUserId)
        .toList() ?? [];

    if (members.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('群内没有其他成员可以转让')),
      );
      return;
    }

    final isDark = Theme.of(context).brightness == Brightness.dark;

    final selectedMember = await showModalBottomSheet<GroupMemberModel>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        height: MediaQuery.of(context).size.height * 0.6,
        decoration: BoxDecoration(
          color: isDark ? const Color(0xFF1F2937) : Colors.white,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: Column(
          children: [
            Container(
              width: 36,
              height: 4,
              margin: const EdgeInsets.only(top: 12),
              decoration: BoxDecoration(
                color: Colors.grey[300],
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '选择新群主',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: isDark ? Colors.white : Colors.black87,
                ),
              ),
            ),
            Expanded(
              child: ListView.builder(
                itemCount: members.length,
                itemBuilder: (context, index) {
                  final member = members[index];
                  return ListTile(
                    leading: Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: _getAvatarColor(member.displayName),
                      ),
                      child: ClipOval(
                        child: member.avatarUrl != null
                            ? CachedNetworkImage(
                                imageUrl: member.avatarUrl!,
                                fit: BoxFit.cover,
                                placeholder: (_, __) => Center(
                                  child: Text(
                                    member.displayName.isNotEmpty
                                        ? member.displayName[0].toUpperCase()
                                        : '?',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                                errorWidget: (_, __, ___) => Center(
                                  child: Text(
                                    member.displayName.isNotEmpty
                                        ? member.displayName[0].toUpperCase()
                                        : '?',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                              )
                            : Center(
                                child: Text(
                                  member.displayName.isNotEmpty
                                      ? member.displayName[0].toUpperCase()
                                      : '?',
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ),
                      ),
                    ),
                    title: Text(member.displayName),
                    subtitle: Text('@${member.username}'),
                    trailing: member.isAdmin
                        ? Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: const Color(0xFFD1FAE5),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: const Text(
                              '管理员',
                              style: TextStyle(
                                fontSize: 12,
                                color: Color(0xFF059669),
                              ),
                            ),
                          )
                        : null,
                    onTap: () => Navigator.pop(context, member),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );

    if (selectedMember == null) return;

    // 确认转让
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1F2937) : Colors.white,
        title: const Text('确认转让'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('确定要将群主转让给 ${selectedMember.displayName} 吗？'),
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
                      '转让后，你将成为管理员',
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
      ),
    );

    if (confirmed != true) return;

    try {
      await _groupApiService.transferOwnership(
        widget.groupId,
        _currentUserId!,
        selectedMember.id,
      );
      await _loadData();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已将群主转让给 ${selectedMember.displayName}')),
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

  Widget _buildControlSection(bool isDark) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        borderRadius: BorderRadius.circular(12),
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
          _buildSwitchItem(
            isDark,
            title: '消息免打扰',
            value: _isMuted,
            onChanged: (value) {
              setState(() => _isMuted = value);
            },
          ),
          _buildDivider(isDark),
          _buildSwitchItem(
            isDark,
            title: '置顶聊天',
            value: _isPinned,
            onChanged: (value) {
              setState(() => _isPinned = value);
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSwitchItem(bool isDark, {
    required String title,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              color: isDark ? Colors.white : const Color(0xFF111813),
            ),
          ),
          const Spacer(),
          _buildIOSSwitch(value, onChanged),
        ],
      ),
    );
  }

  Widget _buildIOSSwitch(bool value, ValueChanged<bool> onChanged) {
    return GestureDetector(
      onTap: () => onChanged(!value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        width: 51,
        height: 31,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(31),
          color: value ? AppTheme.primary : const Color(0xFFE9E9EA),
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

  Widget _buildLeaveButton(bool isDark) {
    final isOwner = _groupDetail?.creatorId == _currentUserId;

    return Column(
      children: [
        Container(
          margin: const EdgeInsets.symmetric(horizontal: 16),
          width: double.infinity,
          child: GestureDetector(
            onTap: _leaveGroup,
            child: Container(
              padding: const EdgeInsets.symmetric(vertical: 16),
              decoration: BoxDecoration(
                color: isDark ? const Color(0xFF1F2937) : Colors.white,
                borderRadius: BorderRadius.circular(12),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.04),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ],
              ),
              child: Text(
                isOwner ? '解散群组' : '退出并删除',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.red,
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(
            isOwner
                ? '解散后群组将被永久删除，\n所有成员将被移出群组。'
                : '退出后将不再接收此群组的消息，\n并清除该群组的所有本地聊天记录。',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              height: 1.5,
              color: isDark ? Colors.grey[600] : Colors.grey[400],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildDivider(bool isDark) {
    return Divider(
      height: 1,
      thickness: 1,
      color: isDark ? const Color(0xFF374151) : const Color(0xFFF3F4F6),
      indent: 16,
      endIndent: 16,
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
      const Color(0xFF6366F1),
      const Color(0xFFF97316),
    ];
    return colors[name.codeUnitAt(0) % colors.length];
  }
}
