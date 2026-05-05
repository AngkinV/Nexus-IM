import 'dart:ui';
import 'package:flutter/material.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/datasources/remote/contact_api_service.dart';
import '../../../data/datasources/remote/group_api_service.dart';
import '../../../data/models/contact/contact_models.dart';
import '../../../data/models/group/group_models.dart';
import '../../../data/repositories/auth_repository.dart';

/// 邀请成员页面
class InviteMembersPage extends StatefulWidget {
  final int groupId;
  final List<GroupMemberModel> existingMembers;

  const InviteMembersPage({
    super.key,
    required this.groupId,
    required this.existingMembers,
  });

  @override
  State<InviteMembersPage> createState() => _InviteMembersPageState();
}

class _InviteMembersPageState extends State<InviteMembersPage> {
  final ContactApiService _contactApiService = ContactApiService();
  final GroupApiService _groupApiService = GroupApiService();
  final AuthRepository _authRepository = AuthRepository();

  List<ContactModel> _contacts = [];
  Set<int> _selectedIds = {};
  bool _isLoading = true;
  bool _isSubmitting = false;
  int? _currentUserId;

  @override
  void initState() {
    super.initState();
    _loadContacts();
  }

  Future<void> _loadContacts() async {
    final userId = await _authRepository.getCurrentUserId();
    if (userId == null) {
      setState(() => _isLoading = false);
      return;
    }

    _currentUserId = userId;

    try {
      final contacts = await _contactApiService.getContacts(userId);

      // 过滤掉已经是群成员的联系人
      final existingMemberIds = widget.existingMembers.map((m) => m.id).toSet();
      final availableContacts = contacts
          .where((c) => !existingMemberIds.contains(c.userId))
          .toList();

      setState(() {
        _contacts = availableContacts;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('加载联系人失败: $e')),
        );
      }
    }
  }

  Future<void> _inviteMembers() async {
    if (_selectedIds.isEmpty || _currentUserId == null) return;

    setState(() => _isSubmitting = true);

    try {
      await _groupApiService.addMembers(
        widget.groupId,
        _currentUserId!,
        _selectedIds.toList(),
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('成功邀请 ${_selectedIds.length} 位成员')),
        );
        Navigator.pop(context, true); // 返回 true 表示有更新
      }
    } catch (e) {
      setState(() => _isSubmitting = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('邀请失败: $e')),
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
                : _contacts.isEmpty
                    ? _buildEmptyState(isDark)
                    : _buildContactList(isDark),
          ),
          if (_selectedIds.isNotEmpty) _buildBottomBar(isDark),
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
                      Icons.close,
                      color: isDark ? Colors.white : Colors.black87,
                    ),
                  ),
                  const Expanded(
                    child: Text(
                      '邀请成员',
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

  Widget _buildEmptyState(bool isDark) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.person_add_disabled,
            size: 64,
            color: Colors.grey[400],
          ),
          const SizedBox(height: 16),
          Text(
            '没有可邀请的联系人',
            style: TextStyle(
              fontSize: 16,
              color: Colors.grey[500],
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '所有联系人都已在群组中',
            style: TextStyle(
              fontSize: 14,
              color: Colors.grey[400],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildContactList(bool isDark) {
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: _contacts.length,
      itemBuilder: (context, index) {
        final contact = _contacts[index];
        final isSelected = _selectedIds.contains(contact.userId);

        return _buildContactItem(contact, isSelected, isDark);
      },
    );
  }

  Widget _buildContactItem(ContactModel contact, bool isSelected, bool isDark) {
    return GestureDetector(
      onTap: () {
        setState(() {
          if (isSelected) {
            _selectedIds.remove(contact.userId);
          } else {
            _selectedIds.add(contact.userId);
          }
        });
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        color: isDark ? Colors.transparent : Colors.transparent,
        child: Row(
          children: [
            // 选择框
            Container(
              width: 24,
              height: 24,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isSelected ? AppTheme.primary : Colors.transparent,
                border: Border.all(
                  color: isSelected ? AppTheme.primary : Colors.grey[400]!,
                  width: 2,
                ),
              ),
              child: isSelected
                  ? const Icon(Icons.check, size: 16, color: Colors.white)
                  : null,
            ),
            const SizedBox(width: 12),
            // 头像
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _getAvatarColor(contact.displayName),
              ),
              child: ClipOval(
                child: contact.avatarUrl != null
                    ? Image.network(
                        contact.avatarUrl!,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => _buildDefaultAvatar(contact.displayName),
                      )
                    : _buildDefaultAvatar(contact.displayName),
              ),
            ),
            const SizedBox(width: 12),
            // 名称
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    contact.displayName,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: isDark ? Colors.white : Colors.black87,
                    ),
                  ),
                  if (contact.username != contact.nickname)
                    Text(
                      '@${contact.username}',
                      style: TextStyle(
                        fontSize: 13,
                        color: Colors.grey[500],
                      ),
                    ),
                ],
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

  Widget _buildBottomBar(bool isDark) {
    return Container(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 12,
      ),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1F2937) : Colors.white,
        border: Border(
          top: BorderSide(
            color: isDark ? Colors.grey[800]! : Colors.grey[200]!,
          ),
        ),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          width: double.infinity,
          height: 48,
          child: ElevatedButton(
            onPressed: _isSubmitting ? null : _inviteMembers,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24),
              ),
              elevation: 0,
            ),
            child: _isSubmitting
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(
                    '邀请 (${_selectedIds.length})',
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
          ),
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
