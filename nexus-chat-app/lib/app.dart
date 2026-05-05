import 'package:flutter/material.dart';
import 'core/config/theme_config.dart';
import 'presentation/pages/splash/splash_page.dart';

/// 全局 Navigator Key，用于通知点击跳转
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// Nexus Chat 应用程序
class NexusChatApp extends StatelessWidget {
  const NexusChatApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Nexus Chat',
      debugShowCheckedModeBanner: false,

      // 全局 Navigator Key
      navigatorKey: navigatorKey,

      // 主题配置
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: ThemeMode.system,

      // 启动页
      home: const SplashPage(),
    );
  }
}
