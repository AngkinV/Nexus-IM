import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/repositories/auth_repository.dart';
import '../auth/login_page.dart';
import 'about_page.dart';

/// 设置页面
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final AuthRepository _authRepository = AuthRepository();

  Future<void> _handleLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
        title: const Text(
          '退出登录',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
        ),
        content: const Text(
          '确定要退出当前账号吗？',
          style: TextStyle(fontSize: 15),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.grey[600],
                fontSize: 16,
              ),
            ),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text(
              '退出',
              style: TextStyle(
                color: Colors.red,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await _authRepository.logout();
      if (mounted) {
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (context) => const LoginPage()),
          (route) => false,
        );
      }
    }
  }

  Future<void> _handleSwitchAccount() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
        title: const Text(
          '切换账号',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
        ),
        content: const Text(
          '切换账号将清除当前登录信息，确定继续吗？',
          style: TextStyle(fontSize: 15),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(
              '取消',
              style: TextStyle(
                color: Colors.grey[600],
                fontSize: 16,
              ),
            ),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text(
              '确定',
              style: TextStyle(
                color: AppTheme.primary,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await _authRepository.switchAccount();
      if (mounted) {
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (context) => const LoginPage()),
          (route) => false,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final backgroundColor = isDark ? const Color(0xFF0A0A0A) : const Color(0xFFF7F7F7);

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        backgroundColor: backgroundColor,
        appBar: AppBar(
          backgroundColor: isDark ? const Color(0xFF18181B) : Colors.white,
          elevation: 0,
          scrolledUnderElevation: 0,
          leading: IconButton(
            onPressed: () => Navigator.pop(context),
            icon: Icon(
              Icons.arrow_back_ios,
              size: 20,
              color: isDark ? Colors.white : Colors.black,
            ),
          ),
          title: Text(
            '设置',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              color: isDark ? Colors.white : Colors.black,
            ),
          ),
          centerTitle: true,
        ),
        body: ListView(
          padding: EdgeInsets.zero,
          children: [
            const SizedBox(height: 8),

            // 账号与安全
            _buildMenuSection(
              isDark: isDark,
              items: const [
                _SettingsItem(
                  title: '账号与安全',
                ),
              ],
            ),

            const SizedBox(height: 8),

            // 通用设置组
            _buildMenuSection(
              isDark: isDark,
              items: const [
                _SettingsItem(
                  title: '消息通知',
                ),
                _SettingsItem(
                  title: '聊天',
                ),
                _SettingsItem(
                  title: '通用',
                ),
              ],
            ),

            const SizedBox(height: 8),

            // 隐私与权限
            _buildMenuSection(
              isDark: isDark,
              items: const [
                _SettingsItem(
                  title: '隐私',
                ),
                _SettingsItem(
                  title: '个人信息与权限',
                ),
                _SettingsItem(
                  title: '个人信息收集清单',
                ),
                _SettingsItem(
                  title: '第三方信息共享清单',
                ),
              ],
            ),

            const SizedBox(height: 8),

            // 关于
            _buildMenuSection(
              isDark: isDark,
              items: [
                _SettingsItem(
                  title: '关于 Nexus',
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(builder: (_) => const AboutPage()),
                    );
                  },
                ),
                const _SettingsItem(
                  title: '帮助与反馈',
                ),
              ],
            ),

            const SizedBox(height: 8),

            // 切换账号
            _buildActionButton(
              isDark: isDark,
              title: '切换账号',
              onTap: _handleSwitchAccount,
              isDestructive: false,
            ),

            const SizedBox(height: 8),

            // 退出登录
            _buildActionButton(
              isDark: isDark,
              title: '退出登录',
              onTap: _handleLogout,
              isDestructive: true,
            ),

            const SizedBox(height: 50),
          ],
        ),
      ),
    );
  }

  /// 构建菜单分组
  Widget _buildMenuSection({
    required bool isDark,
    required List<_SettingsItem> items,
  }) {
    return Container(
      color: isDark ? const Color(0xFF18181B) : Colors.white,
      child: Column(
        children: [
          for (int i = 0; i < items.length; i++) ...[
            _buildMenuItem(items[i], isDark),
            if (i < items.length - 1)
              Divider(
                height: 1,
                indent: 16,
                endIndent: 0,
                color: isDark ? const Color(0xFF27272A) : const Color(0xFFF4F4F5),
              ),
          ],
        ],
      ),
    );
  }

  /// 构建菜单项
  Widget _buildMenuItem(_SettingsItem item, bool isDark) {
    return InkWell(
      onTap: item.onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            // 标题
            Expanded(
              child: Text(
                item.title,
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w400,
                  color: isDark ? Colors.white : const Color(0xFF18181B),
                ),
              ),
            ),

            // 副标题
            if (item.subtitle != null) ...[
              Text(
                item.subtitle!,
                style: TextStyle(
                  fontSize: 15,
                  color: Colors.grey[500],
                ),
              ),
              const SizedBox(width: 4),
            ],

            // 箭头
            Icon(
              Icons.chevron_right,
              size: 22,
              color: isDark ? Colors.grey[600] : Colors.grey[300],
            ),
          ],
        ),
      ),
    );
  }

  /// 构建操作按钮
  Widget _buildActionButton({
    required bool isDark,
    required String title,
    required VoidCallback onTap,
    required bool isDestructive,
  }) {
    return Container(
      color: isDark ? const Color(0xFF18181B) : Colors.white,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Center(
            child: Text(
              title,
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w400,
                color: isDestructive ? Colors.red : (isDark ? Colors.white : const Color(0xFF18181B)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 设置项数据模型
class _SettingsItem {
  final String title;
  final String? subtitle;
  final VoidCallback? onTap;

  const _SettingsItem({
    required this.title,
    this.subtitle,
    this.onTap,
  });
}
