import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/repositories/auth_repository.dart';
import '../main/main_navigation_page.dart';
import 'login_page.dart';

/// 快速登录页面 - 显示记忆的账号，仅需输入密码
class QuickLoginPage extends StatefulWidget {
  const QuickLoginPage({super.key});

  @override
  State<QuickLoginPage> createState() => _QuickLoginPageState();
}

class _QuickLoginPageState extends State<QuickLoginPage> {
  final _authRepository = AuthRepository();
  final _passwordController = TextEditingController();
  final _passwordFocusNode = FocusNode();

  bool _isPasswordVisible = false;
  bool _isLoading = false;
  RememberedAccount? _rememberedAccount;

  @override
  void initState() {
    super.initState();
    _loadRememberedAccount();
  }

  Future<void> _loadRememberedAccount() async {
    final account = await _authRepository.getRememberedAccount();
    if (mounted) {
      setState(() {
        _rememberedAccount = account;
      });
    }
  }

  @override
  void dispose() {
    _passwordController.dispose();
    _passwordFocusNode.dispose();
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
                  // 顶部导航栏
                  _buildTopBar(context),

                  // 可滚动内容区域
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(horizontal: 32),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          const SizedBox(height: 48),

                          // 用户头像和信息
                          _buildUserInfo(context, isDark),

                          const SizedBox(height: 48),

                          // 密码输入
                          _buildPasswordSection(context, isDark),

                          const SizedBox(height: 32),

                          // 切换账号链接
                          _buildSwitchAccountLink(context),

                          const SizedBox(height: 48),
                        ],
                      ),
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

  /// 构建背景装饰
  Widget _buildBackgroundDecorations(bool isDark) {
    return Stack(
      children: [
        // 右上角模糊圆形
        Positioned(
          top: -50,
          right: -100,
          child: Container(
            width: 400,
            height: 400,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppTheme.primary.withValues(alpha: isDark ? 0.1 : 0.08),
            ),
          ),
        ),
        // 左下角模糊圆形
        Positioned(
          bottom: -50,
          left: -100,
          child: Container(
            width: 300,
            height: 300,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppTheme.primary.withValues(alpha: isDark ? 0.05 : 0.06),
            ),
          ),
        ),
      ],
    );
  }

  /// 构建顶部导航栏
  Widget _buildTopBar(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          // 返回按钮（隐藏，保持布局一致）
          const SizedBox(width: 48),

          // 标题
          Expanded(
            child: Text(
              '快速登录',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w500,
                    color: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.color
                        ?.withValues(alpha: 0.6),
                  ),
            ),
          ),

          // 右侧占位
          const SizedBox(width: 48),
        ],
      ),
    );
  }

  /// 构建用户信息区域
  Widget _buildUserInfo(BuildContext context, bool isDark) {
    final displayName = _rememberedAccount?.displayName ?? '用户';
    final avatarUrl = _rememberedAccount?.avatarUrl;

    return Column(
      children: [
        // 头像
        Container(
          width: 100,
          height: 100,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(
              color: AppTheme.primary.withValues(alpha: 0.3),
              width: 3,
            ),
            boxShadow: [
              BoxShadow(
                color: AppTheme.primary.withValues(alpha: 0.2),
                blurRadius: 20,
                spreadRadius: 2,
              ),
            ],
          ),
          child: ClipOval(
            child: avatarUrl != null && avatarUrl.isNotEmpty
                ? Image.network(
                    avatarUrl,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) {
                      return _buildDefaultAvatar(displayName, isDark);
                    },
                  )
                : _buildDefaultAvatar(displayName, isDark),
          ),
        ),

        const SizedBox(height: 24),

        // 欢迎文字
        Text(
          '欢迎回来',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
        ),

        const SizedBox(height: 8),

        // 用户名
        Text(
          displayName,
          style: Theme.of(context).textTheme.displayLarge?.copyWith(
                fontSize: 28,
                letterSpacing: -0.5,
              ),
        ),

        const SizedBox(height: 8),

        // 账号信息
        if (_rememberedAccount?.account != null)
          Text(
            _rememberedAccount!.account,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: isDark ? Colors.grey[500] : Colors.grey[400],
                ),
          ),
      ],
    );
  }

  /// 构建默认头像
  Widget _buildDefaultAvatar(String displayName, bool isDark) {
    return Container(
      color: isDark ? Colors.grey[800] : Colors.grey[200],
      child: Center(
        child: Text(
          displayName.isNotEmpty ? displayName[0].toUpperCase() : '?',
          style: TextStyle(
            fontSize: 40,
            fontWeight: FontWeight.bold,
            color: isDark ? Colors.white : Colors.grey[600],
          ),
        ),
      ),
    );
  }

  /// 构建密码输入区域
  Widget _buildPasswordSection(BuildContext context, bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 密码标签
        _buildPasswordLabel(context),
        const SizedBox(height: 8),

        // 密码输入框
        _buildPasswordInput(isDark),

        const SizedBox(height: 32),

        // 登录按钮
        _buildLoginButton(context),
      ],
    );
  }

  /// 构建密码标签行
  Widget _buildPasswordLabel(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            '密码',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.color
                      ?.withValues(alpha: 0.8),
                ),
          ),
          GestureDetector(
            onTap: () {
              // TODO: 跳转忘记密码页面
            },
            child: Text(
              '忘记密码？',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    fontWeight: FontWeight.w500,
                    color: AppTheme.primary,
                  ),
            ),
          ),
        ],
      ),
    );
  }

  /// 构建密码输入框
  Widget _buildPasswordInput(bool isDark) {
    final bgColor = isDark ? const Color(0xFF1C271F) : Colors.white;
    final borderColor =
        isDark ? const Color(0xFF3B5443) : Colors.grey.shade200;
    final hintColor = isDark ? const Color(0xFF9DB9A6) : Colors.grey.shade400;

    return AnimatedBuilder(
      animation: _passwordFocusNode,
      builder: (context, child) {
        final isFocused = _passwordFocusNode.hasFocus;
        return Container(
          decoration: BoxDecoration(
            color: bgColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isFocused ? AppTheme.primary : borderColor,
              width: isFocused ? 2 : 1,
            ),
          ),
          child: TextField(
            controller: _passwordController,
            focusNode: _passwordFocusNode,
            obscureText: !_isPasswordVisible,
            style: Theme.of(context).textTheme.bodyLarge,
            decoration: InputDecoration(
              hintText: '请输入密码',
              hintStyle: TextStyle(color: hintColor),
              prefixIcon: Padding(
                padding: const EdgeInsets.only(left: 16, right: 12),
                child: Icon(
                  Icons.lock_outline,
                  size: 20,
                  color: isFocused ? AppTheme.primary : Colors.grey,
                ),
              ),
              prefixIconConstraints: const BoxConstraints(
                minWidth: 48,
                minHeight: 48,
              ),
              suffixIcon: IconButton(
                onPressed: () {
                  setState(() {
                    _isPasswordVisible = !_isPasswordVisible;
                  });
                },
                icon: Icon(
                  _isPasswordVisible ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                  color: Colors.grey,
                ),
              ),
              border: InputBorder.none,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 16,
              ),
            ),
          ),
        );
      },
    );
  }

  /// 构建登录按钮
  Widget _buildLoginButton(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: ElevatedButton(
        onPressed: _isLoading ? null : _handleLogin,
        style: ElevatedButton.styleFrom(
          backgroundColor: AppTheme.primary,
          foregroundColor: Colors.black,
          elevation: 8,
          shadowColor: AppTheme.primary.withValues(alpha: 0.4),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        child: _isLoading
            ? const SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(Colors.black),
                ),
              )
            : const Text(
                '登录',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                ),
              ),
      ),
    );
  }

  /// 构建切换账号链接
  Widget _buildSwitchAccountLink(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Center(
      child: GestureDetector(
        onTap: _handleSwitchAccount,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.swap_horiz,
              size: 18,
              color: isDark ? Colors.grey[400] : Colors.grey[500],
            ),
            const SizedBox(width: 8),
            Text(
              '使用其他账号登录',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: isDark ? Colors.grey[400] : Colors.grey[500],
                  ),
            ),
          ],
        ),
      ),
    );
  }

  /// 处理登录
  Future<void> _handleLogin() async {
    final password = _passwordController.text;
    final account = _rememberedAccount?.account;

    if (account == null || account.isEmpty) {
      _showSnackBar('账号信息异常，请重新登录');
      _handleSwitchAccount();
      return;
    }

    if (password.isEmpty) {
      _showSnackBar('请输入密码');
      return;
    }

    setState(() {
      _isLoading = true;
    });

    try {
      await _authRepository.login(
        usernameOrEmail: account,
        password: password,
      );

      if (mounted) {
        // 登录成功，跳转到主页
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (context) => const MainNavigationPage()),
          (route) => false,
        );
      }
    } catch (e) {
      if (mounted) {
        _showSnackBar(e.toString());
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  /// 处理切换账号
  Future<void> _handleSwitchAccount() async {
    await _authRepository.switchAccount();
    if (mounted) {
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (context) => const LoginPage()),
      );
    }
  }

  /// 显示提示消息
  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    );
  }
}
