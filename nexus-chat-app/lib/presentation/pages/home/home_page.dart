import 'package:flutter/material.dart';
import '../main/main_navigation_page.dart';

/// 主页 - 现在重定向到 MainNavigationPage
/// 保留此类以保持向后兼容
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    // 重定向到主导航页面
    return const MainNavigationPage();
  }
}
