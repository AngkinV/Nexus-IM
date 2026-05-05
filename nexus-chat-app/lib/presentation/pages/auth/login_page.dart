import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/repositories/auth_repository.dart';
import '../main/main_navigation_page.dart';
import 'register_page.dart';

/// 邮箱登录页面
class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _authRepository = AuthRepository();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _emailFocusNode = FocusNode();
  final _passwordFocusNode = FocusNode();

  bool _isPasswordVisible = false;
  bool _isLoading = false;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _emailFocusNode.dispose();
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
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const SizedBox(height: 48),

                          // 欢迎标题
                          _buildWelcomeHeader(context, isDark),

                          const SizedBox(height: 48),

                          // 登录表单
                          _buildLoginForm(context, isDark),

                          const SizedBox(height: 32),

                          // 注册链接
                          _buildRegisterLink(context),

                          const SizedBox(height: 48),

                          // 社交登录
                          _buildSocialLogin(context, isDark),

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
          // 返回按钮
          IconButton(
            onPressed: () {
              // TODO: 导航返回
            },
            icon: const Icon(Icons.arrow_back_ios_new, size: 20),
            style: IconButton.styleFrom(
              backgroundColor: Colors.transparent,
            ),
          ),

          // 标题
          Expanded(
            child: Text(
              '邮箱登录',
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

  /// 构建欢迎标题
  Widget _buildWelcomeHeader(BuildContext context, bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '欢迎回来',
          style: Theme.of(context).textTheme.displayLarge?.copyWith(
                fontSize: 32,
                letterSpacing: -0.5,
              ),
        ),
        const SizedBox(height: 8),
        Text(
          '请使用您的邮箱账号继续使用即时通讯',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
        ),
      ],
    );
  }

  /// 构建登录表单
  Widget _buildLoginForm(BuildContext context, bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 邮箱输入框
        _buildInputLabel('邮箱地址'),
        const SizedBox(height: 8),
        _buildEmailInput(isDark),

        const SizedBox(height: 24),

        // 密码输入框
        _buildPasswordLabel(context),
        const SizedBox(height: 8),
        _buildPasswordInput(isDark),

        const SizedBox(height: 32),

        // 登录按钮
        _buildLoginButton(context),
      ],
    );
  }

  /// 构建输入框标签
  Widget _buildInputLabel(String label) {
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Text(
        label,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
              fontWeight: FontWeight.w500,
              color: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.color
                  ?.withValues(alpha: 0.8),
            ),
      ),
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

  /// 构建邮箱输入框
  Widget _buildEmailInput(bool isDark) {
    return _buildInputField(
      controller: _emailController,
      focusNode: _emailFocusNode,
      hintText: '请输入邮箱',
      prefixIcon: Icons.mail_outline,
      keyboardType: TextInputType.emailAddress,
      isDark: isDark,
    );
  }

  /// 构建密码输入框
  Widget _buildPasswordInput(bool isDark) {
    return _buildInputField(
      controller: _passwordController,
      focusNode: _passwordFocusNode,
      hintText: '请输入密码',
      prefixIcon: Icons.lock_outline,
      obscureText: !_isPasswordVisible,
      isDark: isDark,
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
    );
  }

  /// 通用输入框构建
  Widget _buildInputField({
    required TextEditingController controller,
    required FocusNode focusNode,
    required String hintText,
    required IconData prefixIcon,
    required bool isDark,
    TextInputType? keyboardType,
    bool obscureText = false,
    Widget? suffixIcon,
  }) {
    final bgColor = isDark ? const Color(0xFF1C271F) : Colors.white;
    final borderColor =
        isDark ? const Color(0xFF3B5443) : Colors.grey.shade200;
    final hintColor = isDark ? const Color(0xFF9DB9A6) : Colors.grey.shade400;

    return AnimatedBuilder(
      animation: focusNode,
      builder: (context, child) {
        final isFocused = focusNode.hasFocus;
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
            controller: controller,
            focusNode: focusNode,
            keyboardType: keyboardType,
            obscureText: obscureText,
            style: Theme.of(context).textTheme.bodyLarge,
            decoration: InputDecoration(
              hintText: hintText,
              hintStyle: TextStyle(color: hintColor),
              prefixIcon: Padding(
                padding: const EdgeInsets.only(left: 16, right: 12),
                child: Icon(
                  prefixIcon,
                  size: 20,
                  color: isFocused ? AppTheme.primary : Colors.grey,
                ),
              ),
              prefixIconConstraints: const BoxConstraints(
                minWidth: 48,
                minHeight: 48,
              ),
              suffixIcon: suffixIcon,
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

  /// 构建注册链接
  Widget _buildRegisterLink(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Center(
      child: RichText(
        text: TextSpan(
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
          children: [
            const TextSpan(text: '还没有账号？'),
            WidgetSpan(
              child: GestureDetector(
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => const RegisterPage(),
                    ),
                  );
                },
                child: const Text(
                  ' 立即注册',
                  style: TextStyle(
                    color: AppTheme.primary,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 构建社交登录区域
  Widget _buildSocialLogin(BuildContext context, bool isDark) {
    return Column(
      children: [
        // 分隔线
        Row(
          children: [
            Expanded(
              child: Divider(
                color: isDark ? Colors.white10 : Colors.grey.shade200,
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                '社交账号快速登录',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.grey,
                      letterSpacing: 1.5,
                      fontSize: 10,
                    ),
              ),
            ),
            Expanded(
              child: Divider(
                color: isDark ? Colors.white10 : Colors.grey.shade200,
              ),
            ),
          ],
        ),

        const SizedBox(height: 32),

        // 社交登录按钮
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // 微信
            _buildSocialButton(
              context: context,
              isDark: isDark,
              color: const Color(0xFF07C160),
              icon: _buildWeChatIcon(),
              onTap: () {
                // TODO: 微信登录
              },
            ),

            const SizedBox(width: 24),

            // QQ
            _buildSocialButton(
              context: context,
              isDark: isDark,
              color: const Color(0xFF12B7F5),
              icon: _buildQQIcon(),
              onTap: () {
                // TODO: QQ登录
              },
            ),

            const SizedBox(width: 24),

            // 更多
            _buildSocialButton(
              context: context,
              isDark: isDark,
              color: null,
              icon: Icon(
                Icons.more_horiz,
                color: isDark ? Colors.grey[400] : Colors.grey,
              ),
              onTap: () {
                // TODO: 更多登录方式
              },
            ),
          ],
        ),
      ],
    );
  }

  /// 构建社交登录按钮
  Widget _buildSocialButton({
    required BuildContext context,
    required bool isDark,
    required Color? color,
    required Widget icon,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 56,
        height: 56,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isDark ? Colors.white.withValues(alpha: 0.03) : Colors.white,
          border: Border.all(
            color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey.shade200,
          ),
        ),
        child: Center(
          child: color != null
              ? Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: color,
                  ),
                  child: Center(child: icon),
                )
              : icon,
        ),
      ),
    );
  }

  /// 微信图标
  Widget _buildWeChatIcon() {
    return const Icon(
      Icons.chat_bubble,
      color: Colors.white,
      size: 20,
    );
  }

  /// QQ图标
  Widget _buildQQIcon() {
    return const Icon(
      Icons.person,
      color: Colors.white,
      size: 20,
    );
  }

  /// 处理登录
  Future<void> _handleLogin() async {
    final email = _emailController.text.trim();
    final password = _passwordController.text;

    if (email.isEmpty) {
      _showSnackBar('请输入邮箱地址');
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
        usernameOrEmail: email,
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
