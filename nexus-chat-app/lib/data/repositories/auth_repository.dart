import '../../core/storage/secure_storage.dart';
import '../../core/state/user_state_manager.dart';
import '../datasources/remote/auth_api_service.dart';
import '../models/auth/auth_models.dart';

/// 认证仓库
class AuthRepository {
  final AuthApiService _authApi = AuthApiService();
  final SecureStorageService _secureStorage = SecureStorageService();
  final UserStateManager _userStateManager = UserStateManager.instance;

  /// 发送验证码
  Future<bool> sendVerificationCode(String email, {String type = 'REGISTER'}) async {
    return await _authApi.sendVerificationCode(email, type: type);
  }

  /// 验证验证码
  Future<bool> verifyCode(String email, String code, {String type = 'REGISTER'}) async {
    return await _authApi.verifyCode(email, code, type: type);
  }

  /// 用户注册
  Future<UserModel> register({
    required String email,
    required String username,
    required String password,
    required String verificationCode,
    String? nickname,
  }) async {
    final request = RegisterRequest(
      email: email,
      username: username,
      password: password,
      verificationCode: verificationCode,
      nickname: nickname,
    );

    final response = await _authApi.register(request);

    // 保存认证信息
    await _saveAuthData(response);

    // 更新全局用户状态
    await _userStateManager.updateUser(response.user);

    return response.user;
  }

  /// 用户登录
  Future<UserModel> login({
    required String usernameOrEmail,
    required String password,
  }) async {
    final request = LoginRequest(
      usernameOrEmail: usernameOrEmail,
      password: password,
    );

    final response = await _authApi.login(request);

    // 保存认证信息
    await _saveAuthData(response);

    // 更新全局用户状态
    await _userStateManager.updateUser(response.user);

    return response.user;
  }

  /// 用户登出（软登出，保留账号记忆）
  Future<void> logout() async {
    final userId = await _secureStorage.getUserId();
    if (userId != null) {
      try {
        await _authApi.logout(userId);
      } catch (_) {
        // 忽略登出API错误
      }
    }
    // 清除全局用户状态
    await _userStateManager.clear();
    // 软登出：保留账号记忆
    await _secureStorage.softLogout();
  }

  /// 完全登出（清除所有数据包括账号记忆）
  Future<void> fullLogout() async {
    final userId = await _secureStorage.getUserId();
    if (userId != null) {
      try {
        await _authApi.logout(userId);
      } catch (_) {
        // 忽略登出API错误
      }
    }
    // 清除全局用户状态
    await _userStateManager.clear();
    await _secureStorage.clearAll();
  }

  /// 切换账号
  Future<void> switchAccount() async {
    await _secureStorage.switchAccount();
  }

  /// 检查是否已登录（有token且会话有效）
  Future<bool> isLoggedIn() async {
    final hasToken = await _secureStorage.hasToken();
    if (!hasToken) {
      return false;
    }

    // 检查会话是否在30天内有效
    final isValid = await _secureStorage.isSessionValid();
    if (!isValid) {
      // 会话过期，执行软登出
      await _secureStorage.softLogout();
      return false;
    }

    return true;
  }

  /// 检查是否有记忆的账号（用于快速登录）
  Future<bool> hasRememberedAccount() async {
    return await _secureStorage.hasRememberedAccount();
  }

  /// 获取记忆的账号信息
  Future<RememberedAccount?> getRememberedAccount() async {
    final account = await _secureStorage.getLastAccount();
    if (account == null || account.isEmpty) {
      return null;
    }

    return RememberedAccount(
      account: account,
      nickname: await _secureStorage.getLastNickname(),
      avatarUrl: await _secureStorage.getLastAvatar(),
      userId: await _secureStorage.getLastUserId(),
    );
  }

  /// 获取当前用户
  Future<UserModel?> getCurrentUser() async {
    final userData = await _secureStorage.getUserData();
    if (userData != null) {
      return UserModel.fromJson(userData);
    }
    return null;
  }

  /// 获取当前用户ID
  Future<int?> getCurrentUserId() async {
    return await _secureStorage.getUserId();
  }

  /// 更新最后活跃时间
  Future<void> updateLastActiveTime() async {
    await _secureStorage.updateLastActiveTime();
  }

  /// 保存认证数据
  Future<void> _saveAuthData(AuthResponse response) async {
    await _secureStorage.saveToken(response.token);
    await _secureStorage.saveUserId(response.user.id);
    await _secureStorage.saveUserData(response.user.toJson());
  }
}

/// 记忆的账号信息
class RememberedAccount {
  final String account;
  final String? nickname;
  final String? avatarUrl;
  final int? userId;

  RememberedAccount({
    required this.account,
    this.nickname,
    this.avatarUrl,
    this.userId,
  });

  /// 获取显示名称
  String get displayName => nickname ?? account;
}
