import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../data/models/auth/auth_models.dart';
import '../storage/secure_storage.dart';

/// 全局用户状态管理器
/// 用于在用户信息（头像、昵称等）更新时通知所有监听者
class UserStateManager extends ChangeNotifier {
  static UserStateManager? _instance;
  static UserStateManager get instance {
    _instance ??= UserStateManager._();
    return _instance!;
  }

  UserStateManager._();

  final SecureStorageService _secureStorage = SecureStorageService();

  UserModel? _currentUser;
  UserModel? get currentUser => _currentUser;

  // 用户数据更新流
  final _userUpdateController = StreamController<UserModel?>.broadcast();
  Stream<UserModel?> get userStream => _userUpdateController.stream;

  // 头像更新流（用于需要刷新头像缓存的场景）
  final _avatarUpdateController = StreamController<String?>.broadcast();
  Stream<String?> get avatarUpdateStream => _avatarUpdateController.stream;

  /// 初始化 - 从本地存储加载用户数据
  Future<void> initialize() async {
    final userData = await _secureStorage.getUserData();
    if (userData != null) {
      _currentUser = UserModel.fromJson(userData);
      _userUpdateController.add(_currentUser);
      notifyListeners();
    }
  }

  /// 更新用户数据
  Future<void> updateUser(UserModel user) async {
    final oldAvatarUrl = _currentUser?.avatarUrl;
    _currentUser = user;

    // 保存到本地存储
    await _secureStorage.saveUserData(user.toJson());

    // 如果头像URL变了，清除旧头像缓存并通知
    if (oldAvatarUrl != null && oldAvatarUrl != user.avatarUrl) {
      await _clearAvatarCache(oldAvatarUrl);
      _avatarUpdateController.add(user.avatarUrl);
    }

    // 通知所有监听者
    _userUpdateController.add(_currentUser);
    notifyListeners();
  }

  /// 仅更新头像
  Future<void> updateAvatar(String avatarUrl) async {
    if (_currentUser == null) return;

    final oldAvatarUrl = _currentUser!.avatarUrl;
    _currentUser = _currentUser!.copyWith(avatarUrl: avatarUrl);

    // 保存到本地存储
    await _secureStorage.saveUserData(_currentUser!.toJson());

    // 清除旧头像缓存
    if (oldAvatarUrl != null && oldAvatarUrl != avatarUrl) {
      await _clearAvatarCache(oldAvatarUrl);
    }

    // 通知头像更新
    _avatarUpdateController.add(avatarUrl);
    _userUpdateController.add(_currentUser);
    notifyListeners();

    debugPrint('UserStateManager: 头像已更新 $avatarUrl');
  }

  /// 仅更新昵称
  Future<void> updateNickname(String nickname) async {
    if (_currentUser == null) return;

    _currentUser = _currentUser!.copyWith(nickname: nickname);

    // 保存到本地存储
    await _secureStorage.saveUserData(_currentUser!.toJson());

    // 通知更新
    _userUpdateController.add(_currentUser);
    notifyListeners();

    debugPrint('UserStateManager: 昵称已更新 $nickname');
  }

  /// 仅更新个性签名
  Future<void> updateBio(String bio) async {
    if (_currentUser == null) return;

    _currentUser = _currentUser!.copyWith(bio: bio);

    // 保存到本地存储
    await _secureStorage.saveUserData(_currentUser!.toJson());

    // 通知更新
    _userUpdateController.add(_currentUser);
    notifyListeners();

    debugPrint('UserStateManager: 个性签名已更新');
  }

  /// 清除头像缓存
  Future<void> _clearAvatarCache(String avatarUrl) async {
    try {
      // 清除 CachedNetworkImage 的缓存
      await CachedNetworkImage.evictFromCache(avatarUrl);
      debugPrint('UserStateManager: 已清除旧头像缓存 $avatarUrl');
    } catch (e) {
      debugPrint('UserStateManager: 清除头像缓存失败 $e');
    }
  }

  /// 清除所有用户数据（登出时调用）
  Future<void> clear() async {
    _currentUser = null;
    _userUpdateController.add(null);
    notifyListeners();
  }

  /// 从服务器重新加载用户数据
  /// 当需要确保数据是最新的时候调用
  Future<void> reload() async {
    final userData = await _secureStorage.getUserData();
    if (userData != null) {
      _currentUser = UserModel.fromJson(userData);
      _userUpdateController.add(_currentUser);
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _userUpdateController.close();
    _avatarUpdateController.close();
    super.dispose();
  }
}
