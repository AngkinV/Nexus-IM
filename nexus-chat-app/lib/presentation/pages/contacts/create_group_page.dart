import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/models/contact/contact_models.dart';
import '../../../data/repositories/auth_repository.dart';
import '../../../data/repositories/contact_repository.dart';
import 'complete_group_info_page.dart';

/// 创建群聊页面
class CreateGroupPage extends StatefulWidget {
  const CreateGroupPage({super.key});

  @override
  State<CreateGroupPage> createState() => _CreateGroupPageState();
}

class _CreateGroupPageState extends State<CreateGroupPage> {
  final AuthRepository _authRepository = AuthRepository();
  final ContactRepository _contactRepository = ContactRepository();
  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  List<ContactGroup> _contactGroups = [];
  List<ContactModel> _allContacts = [];
  Set<int> _selectedContactIds = {};
  bool _isLoading = true;
  int? _currentUserId;
  String? _currentHighlightLetter;

  // 字母索引键映射
  final Map<String, GlobalKey> _sectionKeys = {};

  @override
  void initState() {
    super.initState();
    _loadData();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final userId = await _authRepository.getCurrentUserId();
    if (userId == null) {
      setState(() => _isLoading = false);
      return;
    }

    _currentUserId = userId;
    await _loadContacts();
  }

  Future<void> _loadContacts() async {
    if (_currentUserId == null) return;

    try {
      final groups = await _contactRepository.getGroupedContacts(_currentUserId!);
      final contacts = await _contactRepository.getContacts(_currentUserId!);

      // 为每个分组创建 GlobalKey
      for (final group in groups) {
        _sectionKeys[group.letter] = GlobalKey();
      }

      setState(() {
        _contactGroups = groups;
        _allContacts = contacts;
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

  void _onScroll() {
    // 根据滚动位置更新当前高亮的字母
    for (final group in _contactGroups) {
      final key = _sectionKeys[group.letter];
      if (key?.currentContext != null) {
        final box = key!.currentContext!.findRenderObject() as RenderBox?;
        if (box != null) {
          final position = box.localToGlobal(Offset.zero);
          if (position.dy >= 100 && position.dy < 200) {
            if (_currentHighlightLetter != group.letter) {
              setState(() {
                _currentHighlightLetter = group.letter;
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

  void _toggleSelection(ContactModel contact) {
    setState(() {
      if (_selectedContactIds.contains(contact.userId)) {
        _selectedContactIds.remove(contact.userId);
      } else {
        _selectedContactIds.add(contact.userId);
      }
    });
  }

  List<ContactModel> get _selectedContacts {
    return _allContacts
        .where((c) => _selectedContactIds.contains(c.userId))
        .toList();
  }

  Future<void> _createGroup() async {
    if (_currentUserId == null) return;
    if (_selectedContactIds.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请至少选择一位成员')),
      );
      return;
    }

    // 跳转到完善群信息页面
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => CompleteGroupInfoPage(
          currentUserId: _currentUserId!,
          selectedContacts: _selectedContacts,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF102217) : Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            // 头部
            _buildHeader(isDark),

            // 已选择成员和搜索栏
            _buildSelectedMembersAndSearch(isDark),

            // 联系人列表
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : Stack(
                      children: [
                        _buildContactList(isDark),
                        // 字母索引栏
                        _buildAlphabetSidebar(isDark),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF102217) : Colors.white,
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // 取消按钮
          GestureDetector(
            onTap: () => Navigator.pop(context),
            child: Text(
              '取消',
              style: TextStyle(
                fontSize: 16,
                color: isDark ? Colors.grey[300] : Colors.grey[600],
              ),
            ),
          ),

          // 标题
          const Text(
            '发起群聊',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
          ),

          // 完成按钮
          GestureDetector(
            onTap: _selectedContactIds.isNotEmpty ? _createGroup : null,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              decoration: BoxDecoration(
                color: _selectedContactIds.isNotEmpty
                    ? AppTheme.primary
                    : Colors.grey[300],
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(
                '完成(${_selectedContactIds.length})',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: _selectedContactIds.isNotEmpty
                      ? Colors.black
                      : Colors.grey[500],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSelectedMembersAndSearch(bool isDark) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(
            color: isDark ? Colors.grey[800]! : Colors.grey[100]!,
          ),
        ),
      ),
      child: Row(
        children: [
          // 已选择的成员头像
          if (_selectedContacts.isNotEmpty)
            Expanded(
              child: SizedBox(
                height: 32,
                child: ListView.builder(
                  scrollDirection: Axis.horizontal,
                  itemCount: _selectedContacts.length,
                  itemBuilder: (context, index) {
                    final contact = _selectedContacts[index];
                    return Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: _buildSmallAvatar(contact),
                    );
                  },
                ),
              ),
            ),

          // 搜索输入框
          Expanded(
            flex: _selectedContacts.isEmpty ? 1 : 0,
            child: Container(
              constraints: BoxConstraints(
                minWidth: _selectedContacts.isEmpty ? double.infinity : 100,
              ),
              child: TextField(
                controller: _searchController,
                decoration: InputDecoration(
                  hintText: '搜索',
                  hintStyle: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 14,
                  ),
                  border: InputBorder.none,
                  contentPadding: EdgeInsets.zero,
                  isDense: true,
                ),
                style: const TextStyle(fontSize: 14),
                onChanged: (value) {
                  // TODO: 实现搜索过滤
                  setState(() {});
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSmallAvatar(ContactModel contact) {
    return Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        color: AppTheme.primary,
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: contact.avatarUrl != null && contact.avatarUrl!.isNotEmpty
            ? Image.network(
                contact.avatarUrl!,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => _buildDefaultSmallAvatar(contact),
              )
            : _buildDefaultSmallAvatar(contact),
      ),
    );
  }

  Widget _buildDefaultSmallAvatar(ContactModel contact) {
    return Center(
      child: Text(
        contact.displayName.isNotEmpty
            ? contact.displayName[0].toUpperCase()
            : '?',
        style: const TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.bold,
          fontSize: 14,
        ),
      ),
    );
  }

  Widget _buildContactList(bool isDark) {
    if (_contactGroups.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.people_outline, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '暂无联系人',
              style: TextStyle(color: Colors.grey[500], fontSize: 16),
            ),
            const SizedBox(height: 8),
            Text(
              '请先添加好友',
              style: TextStyle(color: Colors.grey[400], fontSize: 14),
            ),
          ],
        ),
      );
    }

    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.only(bottom: 20, right: 24),
      itemCount: _contactGroups.fold<int>(
        0,
        (sum, group) => sum + 1 + group.contacts.length,
      ),
      itemBuilder: (context, index) {
        int currentIndex = 0;
        for (final group in _contactGroups) {
          // 分组标题
          if (index == currentIndex) {
            return _buildSectionHeader(group.letter, isDark);
          }
          currentIndex++;

          // 联系人
          for (int i = 0; i < group.contacts.length; i++) {
            if (index == currentIndex) {
              final contact = group.contacts[i];
              final isSelected = _selectedContactIds.contains(contact.userId);
              final isLast = i == group.contacts.length - 1;
              return _buildContactItem(contact, isSelected, isDark, showDivider: !isLast);
            }
            currentIndex++;
          }
        }
        return const SizedBox.shrink();
      },
    );
  }

  Widget _buildSectionHeader(String letter, bool isDark) {
    return Container(
      key: _sectionKeys[letter],
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      color: isDark
          ? const Color(0xFF102217).withValues(alpha: 0.95)
          : const Color(0xFFF5F8F7).withValues(alpha: 0.95),
      child: Text(
        letter,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: isDark ? Colors.grey[400] : Colors.grey[500],
        ),
      ),
    );
  }

  Widget _buildContactItem(ContactModel contact, bool isSelected, bool isDark, {bool showDivider = true}) {
    return GestureDetector(
      onTap: () => _toggleSelection(contact),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        color: isDark ? const Color(0xFF102217) : Colors.white,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: showDivider
              ? BoxDecoration(
                  border: Border(
                    bottom: BorderSide(
                      color: isDark ? Colors.grey[800]! : Colors.grey[100]!,
                    ),
                  ),
                )
              : null,
          child: Row(
            children: [
              // 圆形复选框
              Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: isSelected ? AppTheme.primary : Colors.transparent,
                  border: Border.all(
                    color: isSelected ? AppTheme.primary : Colors.grey[300]!,
                    width: 2,
                  ),
                ),
                child: isSelected
                    ? const Icon(
                        Icons.check,
                        color: Colors.black,
                        size: 14,
                      )
                    : null,
              ),

              const SizedBox(width: 16),

              // 头像
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(8),
                  color: isDark ? Colors.grey[800] : Colors.grey[200],
                ),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: contact.avatarUrl != null && contact.avatarUrl!.isNotEmpty
                      ? Image.network(
                          contact.avatarUrl!,
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) => _buildDefaultAvatar(contact, isDark),
                        )
                      : _buildDefaultAvatar(contact, isDark),
                ),
              ),

              const SizedBox(width: 16),

              // 名称
              Expanded(
                child: Text(
                  contact.displayName,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
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

  Widget _buildDefaultAvatar(ContactModel contact, bool isDark) {
    return Container(
      color: AppTheme.primary.withValues(alpha: 0.2),
      child: Center(
        child: Text(
          contact.displayName.isNotEmpty
              ? contact.displayName[0].toUpperCase()
              : '?',
          style: TextStyle(
            color: AppTheme.primary,
            fontWeight: FontWeight.bold,
            fontSize: 16,
          ),
        ),
      ),
    );
  }

  Widget _buildAlphabetSidebar(bool isDark) {
    const allLetters = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '#'];
    final availableLetters = _contactGroups.map((g) => g.letter).toSet();

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
              final isAvailable = availableLetters.contains(letter);
              final isHighlighted = letter == _currentHighlightLetter;

              return GestureDetector(
                onTap: () {
                  if (isAvailable) {
                    _scrollToLetter(letter);
                  }
                },
                child: Container(
                  width: 16,
                  height: 16,
                  margin: const EdgeInsets.symmetric(vertical: 0.5),
                  child: Center(
                    child: Text(
                      letter,
                      style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                        color: isHighlighted
                            ? (isDark ? Colors.white : Colors.black)
                            : isAvailable
                                ? AppTheme.primary
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
