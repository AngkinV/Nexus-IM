import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/network/message_service.dart';
import '../../../core/storage/secure_storage.dart';
import '../../widgets/common/in_app_notification_banner.dart';
import '../home/messages_page.dart';
import '../contacts/contacts_page.dart';
import '../community/community_page.dart';
import '../profile/profile_page.dart';
import '../chat/chat_page.dart';
import '../../../data/models/chat/chat_models.dart';

/// 主导航页面 - 包含底部导航栏的容器
class MainNavigationPage extends StatefulWidget {
  const MainNavigationPage({super.key});

  @override
  State<MainNavigationPage> createState() => _MainNavigationPageState();
}

class _MainNavigationPageState extends State<MainNavigationPage> {
  int _currentIndex = 0;
  final MessageService _messageService = MessageService();
  final SecureStorageService _secureStorage = SecureStorageService();
  final ValueNotifier<int> _tabNotifier = ValueNotifier<int>(0);

  late final List<Widget> _pages = [
    const MessagesPage(),
    const ContactsPage(),
    const CommunityPage(),
    ProfilePage(onNavigateToCommunity: _navigateToCommunity, tabNotifier: _tabNotifier),
  ];

  /// 从个人中心跳转到社区Tab
  void _navigateToCommunity() {
    setState(() {
      _currentIndex = 2;
    });
  }

  @override
  void initState() {
    super.initState();
    _connectWebSocket();
    _setupInAppNotification();
  }

  @override
  void dispose() {
    _tabNotifier.dispose();
    super.dispose();
  }

  /// 连接 WebSocket
  Future<void> _connectWebSocket() async {
    try {
      final userId = await _secureStorage.getUserId();
      final token = await _secureStorage.getToken();

      if (userId != null && token != null) {
        await _messageService.connect(userId, token);
        debugPrint('🔌 MainNavigationPage: WebSocket 连接成功');
      }
    } catch (e) {
      debugPrint('🔌 MainNavigationPage: WebSocket 连接失败 $e');
    }
  }

  /// 设置应用内通知回调
  void _setupInAppNotification() {
    _messageService.onShowInAppNotification = (messageData) {
      if (!mounted) return;

      final chatId = messageData['chatId'] as int?;
      final senderName = messageData['senderNickname'] as String? ?? '未知用户';
      final senderAvatar = messageData['senderAvatar'] as String?;
      final content = messageData['content'] as String? ?? '';
      final messageType = messageData['messageType'] as String?;

      if (chatId == null) return;

      // 根据消息类型格式化预览
      String preview = content;
      if (messageType == 'IMAGE') {
        preview = '[图片]';
      } else if (messageType == 'VIDEO') {
        preview = '[视频]';
      } else if (messageType == 'AUDIO') {
        preview = '[语音]';
      } else if (messageType == 'FILE') {
        preview = '[文件]';
      }

      // 显示应用内横幅
      InAppNotificationOverlay.show(
        context,
        senderName: senderName,
        senderAvatar: senderAvatar,
        messagePreview: preview,
        chatId: chatId,
        onTap: () => _navigateToChat(chatId, messageData),
      );
    };
  }

  /// 跳转到聊天页面
  void _navigateToChat(int chatId, Map<String, dynamic> messageData) {
    // 从消息数据构建一个临时的 ChatModel
    // 实际使用时应该从缓存或 API 获取完整的 ChatModel
    final chat = ChatModel(
      id: chatId,
      type: ChatType.direct,
      name: messageData['senderNickname'] as String?,
      avatar: messageData['senderAvatar'] as String?,
      members: [],
    );

    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (context) => ChatPage(chat: chat),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        body: IndexedStack(
          index: _currentIndex,
          children: _pages,
        ),
        bottomNavigationBar: _buildBottomNav(context, isDark),
      ),
    );
  }

  /// 构建底部导航栏
  Widget _buildBottomNav(BuildContext context, bool isDark) {
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          decoration: BoxDecoration(
            color: isDark
                ? Theme.of(context).scaffoldBackgroundColor.withValues(alpha: 0.95)
                : Theme.of(context).scaffoldBackgroundColor.withValues(alpha: 0.95),
            border: Border(
              top: BorderSide(
                color: isDark ? Colors.white.withValues(alpha: 0.1) : Colors.grey.shade200,
              ),
            ),
          ),
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  _buildNavItem(0, Icons.chat_bubble, Icons.chat_bubble_outline, '消息', isDark),
                  _buildNavItem(1, Icons.people, Icons.people_outline, '联系人', isDark),
                  _buildNavItem(2, Icons.public, Icons.public_outlined, '社区', isDark),
                  _buildNavItem(3, Icons.person, Icons.person_outline, '我', isDark),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 构建导航项
  Widget _buildNavItem(int index, IconData selectedIcon, IconData unselectedIcon, String label, bool isDark) {
    final isSelected = _currentIndex == index;

    return GestureDetector(
      onTap: () {
        if (_currentIndex != index) {
          HapticFeedback.selectionClick();
          setState(() {
            _currentIndex = index;
          });
          _tabNotifier.value = index;
        }
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              isSelected ? selectedIcon : unselectedIcon,
              color: isSelected
                  ? AppTheme.primary
                  : (isDark ? Colors.grey[400] : Colors.grey[400]),
              size: 24,
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.bold,
                color: isSelected
                    ? AppTheme.primary
                    : (isDark ? Colors.grey[400] : Colors.grey[400]),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
