import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/repositories/auth_repository.dart';
import '../auth/login_page.dart';
import '../auth/quick_login_page.dart';
import '../main/main_navigation_page.dart';

/// 应用启动页面
class SplashPage extends StatefulWidget {
  const SplashPage({super.key});

  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> with TickerProviderStateMixin {
  final AuthRepository _authRepository = AuthRepository();

  // 进度值
  double _progress = 0.0;
  String _statusText = '正在初始化...';

  // 动画控制器
  late AnimationController _logoAnimationController;
  late AnimationController _pulseAnimationController;
  late Animation<double> _logoScaleAnimation;
  late Animation<double> _logoOpacityAnimation;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _initAnimations();
    _startLoading();
  }

  void _initAnimations() {
    // Logo 入场动画
    _logoAnimationController = AnimationController(
      duration: const Duration(milliseconds: 800),
      vsync: this,
    );

    _logoScaleAnimation = Tween<double>(begin: 0.5, end: 1.0).animate(
      CurvedAnimation(
        parent: _logoAnimationController,
        curve: Curves.elasticOut,
      ),
    );

    _logoOpacityAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(
        parent: _logoAnimationController,
        curve: const Interval(0.0, 0.5, curve: Curves.easeOut),
      ),
    );

    // 脉冲动画
    _pulseAnimationController = AnimationController(
      duration: const Duration(milliseconds: 1500),
      vsync: this,
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 0.4, end: 1.0).animate(
      CurvedAnimation(
        parent: _pulseAnimationController,
        curve: Curves.easeInOut,
      ),
    );

    // 启动logo动画
    _logoAnimationController.forward();
  }

  Future<void> _startLoading() async {
    // 模拟加载过程
    final steps = [
      (0.15, '正在初始化...'),
      (0.35, '正在加载资源...'),
      (0.55, '正在检查更新...'),
      (0.75, '正在同步数据...'),
      (0.90, '正在验证登录...'),
      (1.0, '准备就绪'),
    ];

    for (final step in steps) {
      await Future.delayed(const Duration(milliseconds: 400));
      if (mounted) {
        setState(() {
          _progress = step.$1;
          _statusText = step.$2;
        });
      }
    }

    // 检查登录状态并跳转
    await Future.delayed(const Duration(milliseconds: 300));
    if (mounted) {
      await _navigateToNextPage();
    }
  }

  /// 检查登录状态并跳转到对应页面
  Future<void> _navigateToNextPage() async {
    Widget nextPage;

    // 检查是否已登录且会话有效
    final isLoggedIn = await _authRepository.isLoggedIn();

    if (isLoggedIn) {
      // 已登录且会话有效，更新活跃时间并跳转到首页
      await _authRepository.updateLastActiveTime();
      nextPage = const MainNavigationPage();
    } else {
      // 未登录或会话过期，检查是否有记忆的账号
      final hasRememberedAccount = await _authRepository.hasRememberedAccount();

      if (hasRememberedAccount) {
        // 有记忆的账号，跳转到快速登录页
        nextPage = const QuickLoginPage();
      } else {
        // 无记忆的账号，跳转到正常登录页
        nextPage = const LoginPage();
      }
    }

    if (mounted) {
      Navigator.pushReplacement(
        context,
        PageRouteBuilder(
          pageBuilder: (context, animation, secondaryAnimation) => nextPage,
          transitionsBuilder: (context, animation, secondaryAnimation, child) {
            return FadeTransition(
              opacity: animation,
              child: child,
            );
          },
          transitionDuration: const Duration(milliseconds: 300),
        ),
      );
    }
  }

  @override
  void dispose() {
    _logoAnimationController.dispose();
    _pulseAnimationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        body: Stack(
          children: [
            // 背景装饰
            _buildBackgroundDecorations(isDark),

            // 主内容
            SafeArea(
              child: Column(
                children: [
                  // Logo 和标题区域
                  Expanded(
                    child: Center(
                      child: _buildLogoSection(),
                    ),
                  ),

                  // 进度条区域
                  _buildProgressSection(isDark),

                  // 底部文字
                  _buildFooter(isDark),

                  const SizedBox(height: 48),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 构建背景装饰
  Widget _buildBackgroundDecorations(bool isDark) {
    return Stack(
      children: [
        // 左上角模糊圆形
        Positioned(
          left: -100,
          top: -100,
          child: Container(
            width: 500,
            height: 500,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isDark
                  ? AppTheme.primary.withValues(alpha: 0.05)
                  : AppTheme.primary.withValues(alpha: 0.08),
            ),
          ),
        ),
        // 右下角模糊圆形
        Positioned(
          right: -80,
          bottom: -80,
          child: Container(
            width: 400,
            height: 400,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isDark
                  ? AppTheme.primary.withValues(alpha: 0.05)
                  : AppTheme.primary.withValues(alpha: 0.08),
            ),
          ),
        ),
      ],
    );
  }

  /// 构建 Logo 区域
  Widget _buildLogoSection() {
    return AnimatedBuilder(
      animation: _logoAnimationController,
      builder: (context, child) {
        return Opacity(
          opacity: _logoOpacityAnimation.value,
          child: Transform.scale(
            scale: _logoScaleAnimation.value,
            child: child,
          ),
        );
      },
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Logo 图片
          Container(
            width: 112,
            height: 112,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(28),
              boxShadow: AppTheme.primaryShadow,
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(28),
              child: Image.asset(
                'assets/icon/app_icon.jpg',
                fit: BoxFit.cover,
              ),
            ),
          ),

          const SizedBox(height: 32),

          // 应用名称
          Text(
            'Nexus',
            style: Theme.of(context).textTheme.displayLarge,
          ),

          const SizedBox(height: 12),

          // 副标题
          Text(
            '连接未来，触手可及',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                ),
          ),
        ],
      ),
    );
  }

  /// 构建进度条区域
  Widget _buildProgressSection(bool isDark) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 40),
      child: Column(
        children: [
          // 状态文字和百分比
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _statusText,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: isDark ? Colors.grey[300] : Colors.grey[600],
                      fontWeight: FontWeight.w500,
                    ),
              ),
              Text(
                '${(_progress * 100).toInt()}%',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: AppTheme.primary,
                      fontWeight: FontWeight.w700,
                    ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // 进度条
          Container(
            height: 8,
            decoration: BoxDecoration(
              color: isDark ? Colors.grey[800] : Colors.grey[100],
              borderRadius: BorderRadius.circular(4),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: AnimatedFractionallySizedBox(
                duration: const Duration(milliseconds: 300),
                curve: Curves.easeOut,
                alignment: Alignment.centerLeft,
                widthFactor: _progress,
                child: Container(
                  decoration: BoxDecoration(
                    color: AppTheme.primary,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
            ),
          ),

          const SizedBox(height: 24),

          // 加密状态指示
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              AnimatedBuilder(
                animation: _pulseAnimation,
                builder: (context, child) {
                  return Container(
                    width: 6,
                    height: 6,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppTheme.primary.withValues(
                        alpha: _pulseAnimation.value,
                      ),
                    ),
                  );
                },
              ),
              const SizedBox(width: 8),
              Text(
                '端到端加密已就绪',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      fontWeight: FontWeight.w500,
                    ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// 构建底部文字
  Widget _buildFooter(bool isDark) {
    return Padding(
      padding: const EdgeInsets.only(top: 48),
      child: Text(
        'POWERED BY NEXUS NETWORK',
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: isDark ? Colors.grey[600] : Colors.grey[400],
            ),
      ),
    );
  }
}
