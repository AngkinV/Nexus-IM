import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/repositories/auth_repository.dart';
import '../home/home_page.dart';

/// 用户注册页面
class RegisterPage extends StatefulWidget {
  const RegisterPage({super.key});

  @override
  State<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends State<RegisterPage> {
  final _authRepository = AuthRepository();
  final _usernameController = TextEditingController();
  final _emailController = TextEditingController();
  final _codeController = TextEditingController();
  final _passwordController = TextEditingController();

  final _usernameFocusNode = FocusNode();
  final _emailFocusNode = FocusNode();
  final _codeFocusNode = FocusNode();
  final _passwordFocusNode = FocusNode();

  bool _isPasswordVisible = false;
  bool _isLoading = false;
  bool _isSendingCode = false;
  int _countDown = 0;
  Timer? _timer;

  @override
  void dispose() {
    _usernameController.dispose();
    _emailController.dispose();
    _codeController.dispose();
    _passwordController.dispose();
    _usernameFocusNode.dispose();
    _emailFocusNode.dispose();
    _codeFocusNode.dispose();
    _passwordFocusNode.dispose();
    _timer?.cancel();
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
            Column(
              children: [
                // 顶部导航栏
                _buildTopBar(context, isDark),

                // 可滚动内容区域
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.symmetric(horizontal: 32),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 32),

                        // 欢迎标题
                        _buildWelcomeHeader(context, isDark),

                        const SizedBox(height: 40),

                        // 注册表单
                        _buildRegisterForm(context, isDark),

                        const SizedBox(height: 32),

                        // 提交按钮
                        _buildSubmitButton(context),

                        const SizedBox(height: 32),

                        // 登录链接
                        _buildLoginLink(context, isDark),

                        const SizedBox(height: 40),

                        // 服务条款
                        _buildTerms(context),

                        const SizedBox(height: 48),
                      ],
                    ),
                  ),
                ),
              ],
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
  Widget _buildTopBar(BuildContext context, bool isDark) {
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            // 返回按钮
            IconButton(
              onPressed: () {
                Navigator.pop(context);
              },
              icon: const Icon(Icons.arrow_back_ios_new, size: 20),
              style: IconButton.styleFrom(
                backgroundColor: Colors.transparent,
              ),
            ),

            // 标题
            Expanded(
              child: Text(
                '用户注册',
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
      ),
    );
  }

  /// 构建欢迎标题
  Widget _buildWelcomeHeader(BuildContext context, bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '创建账号',
          style: Theme.of(context).textTheme.displayLarge?.copyWith(
                fontSize: 32,
                letterSpacing: -0.5,
              ),
        ),
        const SizedBox(height: 8),
        Text(
          '开始您的即时通讯之旅',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
        ),
      ],
    );
  }

  /// 构建注册表单
  Widget _buildRegisterForm(BuildContext context, bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 用户名输入框
        _buildInputLabel(context, '用户名称'),
        const SizedBox(height: 8),
        _buildInputField(
          controller: _usernameController,
          focusNode: _usernameFocusNode,
          hintText: '请输入用户名',
          prefixIcon: Icons.person_outline,
          isDark: isDark,
        ),

        const SizedBox(height: 24),

        // 邮箱输入框
        _buildInputLabel(context, '邮箱地址'),
        const SizedBox(height: 8),
        _buildInputField(
          controller: _emailController,
          focusNode: _emailFocusNode,
          hintText: 'example@mail.com',
          prefixIcon: Icons.mail_outline,
          keyboardType: TextInputType.emailAddress,
          isDark: isDark,
        ),

        const SizedBox(height: 24),

        // 验证码输入框
        _buildInputLabel(context, '验证码'),
        const SizedBox(height: 8),
        _buildVerificationCodeInput(isDark),

        const SizedBox(height: 24),

        // 密码输入框
        _buildInputLabel(context, '设置密码'),
        const SizedBox(height: 8),
        _buildPasswordInput(isDark),
      ],
    );
  }

  /// 构建输入框标签
  Widget _buildInputLabel(BuildContext context, String label) {
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

  /// 通用输入框构建 - 与登录页保持一致
  Widget _buildInputField({
    required TextEditingController controller,
    required FocusNode focusNode,
    required String hintText,
    required IconData prefixIcon,
    required bool isDark,
    TextInputType? keyboardType,
    bool obscureText = false,
    Widget? suffixIcon,
    int? maxLength,
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
            maxLength: maxLength,
            style: Theme.of(context).textTheme.bodyLarge,
            decoration: InputDecoration(
              hintText: hintText,
              hintStyle: TextStyle(color: hintColor),
              counterText: '',
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

  /// 构建验证码输入框
  Widget _buildVerificationCodeInput(bool isDark) {
    final bgColor = isDark ? const Color(0xFF1C271F) : Colors.white;
    final borderColor =
        isDark ? const Color(0xFF3B5443) : Colors.grey.shade200;
    final hintColor = isDark ? const Color(0xFF9DB9A6) : Colors.grey.shade400;

    return Row(
      children: [
        // 验证码输入框
        Expanded(
          child: AnimatedBuilder(
            animation: _codeFocusNode,
            builder: (context, child) {
              final isFocused = _codeFocusNode.hasFocus;
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
                  controller: _codeController,
                  focusNode: _codeFocusNode,
                  keyboardType: TextInputType.number,
                  maxLength: 6,
                  style: Theme.of(context).textTheme.bodyLarge,
                  decoration: InputDecoration(
                    hintText: '6位验证码',
                    hintStyle: TextStyle(color: hintColor),
                    counterText: '',
                    prefixIcon: Padding(
                      padding: const EdgeInsets.only(left: 16, right: 12),
                      child: Icon(
                        Icons.verified_user_outlined,
                        size: 20,
                        color: isFocused ? AppTheme.primary : Colors.grey,
                      ),
                    ),
                    prefixIconConstraints: const BoxConstraints(
                      minWidth: 48,
                      minHeight: 48,
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
          ),
        ),

        const SizedBox(width: 12),

        // 获取验证码按钮
        SizedBox(
          height: 56,
          child: TextButton(
            onPressed: _countDown > 0 || _isSendingCode ? null : _sendCode,
            style: TextButton.styleFrom(
              backgroundColor: AppTheme.primary.withValues(alpha: 0.05),
              foregroundColor: AppTheme.primary,
              disabledForegroundColor: Colors.grey,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(
                  color: _countDown > 0
                      ? Colors.grey.withValues(alpha: 0.2)
                      : AppTheme.primary.withValues(alpha: 0.2),
                ),
              ),
            ),
            child: Text(
              _countDown > 0 ? '${_countDown}s' : '获取验证码',
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// 构建密码输入框
  Widget _buildPasswordInput(bool isDark) {
    return _buildInputField(
      controller: _passwordController,
      focusNode: _passwordFocusNode,
      hintText: '至少8位字符',
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

  /// 构建提交按钮 - 与登录页保持一致
  Widget _buildSubmitButton(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: ElevatedButton(
        onPressed: _isLoading ? null : _handleRegister,
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
            : const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    '注册并登录',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  SizedBox(width: 8),
                  Icon(Icons.login, size: 20),
                ],
              ),
      ),
    );
  }

  /// 构建登录链接
  Widget _buildLoginLink(BuildContext context, bool isDark) {
    return Center(
      child: RichText(
        text: TextSpan(
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: isDark ? Colors.grey[400] : Colors.grey[500],
              ),
          children: [
            const TextSpan(text: '已有账号？'),
            WidgetSpan(
              child: GestureDetector(
                onTap: () {
                  Navigator.pop(context);
                },
                child: const Text(
                  ' 立即登录',
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

  /// 构建服务条款
  Widget _buildTerms(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24),
        child: RichText(
          textAlign: TextAlign.center,
          text: TextSpan(
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Colors.grey[400],
                  fontSize: 12,
                  height: 1.5,
                ),
            children: const [
              TextSpan(text: '注册即表示您同意我们的 '),
              TextSpan(
                text: '服务条款',
                style: TextStyle(
                  decoration: TextDecoration.underline,
                ),
              ),
              TextSpan(text: ' 和 '),
              TextSpan(
                text: '隐私政策',
                style: TextStyle(
                  decoration: TextDecoration.underline,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 发送验证码
  Future<void> _sendCode() async {
    final email = _emailController.text.trim();

    if (email.isEmpty) {
      _showSnackBar('请先输入邮箱地址');
      return;
    }

    if (!_isValidEmail(email)) {
      _showSnackBar('请输入有效的邮箱地址');
      return;
    }

    setState(() {
      _isSendingCode = true;
    });

    try {
      final success = await _authRepository.sendVerificationCode(email);

      if (mounted) {
        if (success) {
          setState(() {
            _countDown = 60;
            _isSendingCode = false;
          });

          _startCountDown();
          _showSnackBar('验证码已发送到您的邮箱');
        } else {
          setState(() {
            _isSendingCode = false;
          });
          _showSnackBar('发送失败，请重试');
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isSendingCode = false;
        });
        _showSnackBar(e.toString());
      }
    }
  }

  /// 开始倒计时
  void _startCountDown() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (mounted) {
        setState(() {
          _countDown--;
          if (_countDown <= 0) {
            timer.cancel();
          }
        });
      } else {
        timer.cancel();
      }
    });
  }

  /// 验证邮箱格式
  bool _isValidEmail(String email) {
    return RegExp(r'^[\w-\.]+@([\w-]+\.)+[\w-]{2,4}$').hasMatch(email);
  }

  /// 处理注册
  Future<void> _handleRegister() async {
    final username = _usernameController.text.trim();
    final email = _emailController.text.trim();
    final code = _codeController.text.trim();
    final password = _passwordController.text;

    if (username.isEmpty) {
      _showSnackBar('请输入用户名');
      return;
    }

    if (email.isEmpty) {
      _showSnackBar('请输入邮箱地址');
      return;
    }

    if (!_isValidEmail(email)) {
      _showSnackBar('请输入有效的邮箱地址');
      return;
    }

    if (code.isEmpty || code.length != 6) {
      _showSnackBar('请输入6位验证码');
      return;
    }

    if (password.isEmpty || password.length < 8) {
      _showSnackBar('密码至少8位字符');
      return;
    }

    setState(() {
      _isLoading = true;
    });

    try {
      await _authRepository.register(
        email: email,
        username: username,
        password: password,
        verificationCode: code,
        nickname: username, // 使用用户名作为默认昵称
      );

      if (mounted) {
        _showSnackBar('注册成功');
        // 注册成功，跳转到主页
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (context) => const HomePage()),
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
