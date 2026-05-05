import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/state/user_state_manager.dart';
import '../../../data/models/auth/auth_models.dart';

/// 个人信息编辑页面
class ProfileEditPage extends StatefulWidget {
  final UserModel user;

  const ProfileEditPage({super.key, required this.user});

  @override
  State<ProfileEditPage> createState() => _ProfileEditPageState();
}

class _ProfileEditPageState extends State<ProfileEditPage> {
  final DioClient _dioClient = DioClient();
  final UserStateManager _userStateManager = UserStateManager.instance;
  final ImagePicker _imagePicker = ImagePicker();

  late UserModel _user;
  bool _isLoading = false;
  bool _hasChanges = false;

  @override
  void initState() {
    super.initState();
    _user = widget.user;
  }

  /// 选择并上传头像
  Future<void> _pickAndUploadAvatar() async {
    final source = await _showImageSourceDialog();
    if (source == null) return;

    try {
      final XFile? image = await _imagePicker.pickImage(
        source: source,
        maxWidth: 800,
        maxHeight: 800,
        imageQuality: 85,
      );

      if (image == null) return;

      setState(() => _isLoading = true);

      // 上传头像到服务器
      final avatarUrl = await _uploadAvatar(File(image.path));

      if (avatarUrl != null) {
        setState(() {
          _user = _user.copyWith(avatarUrl: avatarUrl);
          _hasChanges = true;
        });

        // 更新全局用户状态（同时会保存到本地存储并清除旧缓存）
        await _userStateManager.updateAvatar(avatarUrl);

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('头像更新成功')),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('头像上传失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  /// 上传头像到服务器
  Future<String?> _uploadAvatar(File imageFile) async {
    try {
      final fileName = imageFile.path.split('/').last;
      final formData = FormData.fromMap({
        'file': await MultipartFile.fromFile(
          imageFile.path,
          filename: fileName,
        ),
      });

      final response = await _dioClient.post(
        '/api/users/${_user.id}/avatar',
        data: formData,
        options: Options(
          contentType: 'multipart/form-data',
        ),
      );

      if (response.statusCode == 200 && response.data != null) {
        final data = response.data as Map<String, dynamic>;
        return data['avatarUrl'] as String?;
      }
      return null;
    } catch (e) {
      debugPrint('上传头像失败: $e');
      rethrow;
    }
  }

  /// 显示图片来源选择对话框
  Future<ImageSource?> _showImageSourceDialog() async {
    return showModalBottomSheet<ImageSource>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) {
        final isDark = Theme.of(context).brightness == Brightness.dark;
        return Container(
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1C1C1E) : Colors.white,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
          ),
          child: SafeArea(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(height: 8),
                Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: isDark ? Colors.grey[700] : Colors.grey[300],
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(height: 16),
                ListTile(
                  leading: Icon(Icons.camera_alt, color: AppTheme.primary),
                  title: const Text('拍照'),
                  onTap: () => Navigator.pop(context, ImageSource.camera),
                ),
                ListTile(
                  leading: Icon(Icons.photo_library, color: AppTheme.primary),
                  title: const Text('从相册选择'),
                  onTap: () => Navigator.pop(context, ImageSource.gallery),
                ),
                const SizedBox(height: 8),
                ListTile(
                  title: Text(
                    '取消',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey[500]),
                  ),
                  onTap: () => Navigator.pop(context),
                ),
                const SizedBox(height: 8),
              ],
            ),
          ),
        );
      },
    );
  }

  /// 编辑昵称
  Future<void> _editNickname() async {
    final result = await _showEditDialog(
      title: '修改昵称',
      initialValue: _user.nickname ?? '',
      hintText: '请输入昵称',
      maxLength: 20,
    );

    if (result != null && result.isNotEmpty && result != _user.nickname) {
      await _updateProfile(nickname: result);
    }
  }

  /// 编辑个性签名
  Future<void> _editBio() async {
    final result = await _showEditDialog(
      title: '修改个性签名',
      initialValue: _user.bio ?? '',
      hintText: '请输入个性签名',
      maxLength: 100,
      maxLines: 3,
    );

    if (result != null && result != _user.bio) {
      await _updateProfile(bio: result);
    }
  }

  /// 更新个人资料
  Future<void> _updateProfile({String? nickname, String? bio}) async {
    setState(() => _isLoading = true);

    try {
      final response = await _dioClient.put(
        '/api/users/${_user.id}/profile',
        data: {
          if (nickname != null) 'nickname': nickname,
          if (bio != null) 'bio': bio,
        },
      );

      if (response.statusCode == 200) {
        setState(() {
          _user = _user.copyWith(
            nickname: nickname ?? _user.nickname,
            bio: bio ?? _user.bio,
          );
          _hasChanges = true;
        });

        // 更新全局用户状态
        if (nickname != null) {
          await _userStateManager.updateNickname(nickname);
        }
        if (bio != null) {
          await _userStateManager.updateBio(bio);
        }

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('资料更新成功')),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('更新失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  /// 显示编辑对话框
  Future<String?> _showEditDialog({
    required String title,
    required String initialValue,
    required String hintText,
    int maxLength = 50,
    int maxLines = 1,
  }) async {
    final controller = TextEditingController(text: initialValue);
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF1C1C1E) : Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(title),
        content: TextField(
          controller: controller,
          maxLength: maxLength,
          maxLines: maxLines,
          autofocus: true,
          decoration: InputDecoration(
            hintText: hintText,
            filled: true,
            fillColor: isDark ? const Color(0xFF2C2C2E) : Colors.grey[100],
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide.none,
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: AppTheme.primary, width: 2),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('取消', style: TextStyle(color: Colors.grey[500])),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: Text('确定', style: TextStyle(color: AppTheme.primary)),
          ),
        ],
      ),
    );
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
          leading: IconButton(
            icon: Icon(
              Icons.arrow_back_ios,
              color: isDark ? Colors.white : Colors.black87,
            ),
            onPressed: () => Navigator.pop(context, _hasChanges),
          ),
          title: Text(
            '个人信息',
            style: TextStyle(
              color: isDark ? Colors.white : Colors.black87,
              fontWeight: FontWeight.w600,
            ),
          ),
          centerTitle: true,
        ),
        body: Stack(
          children: [
            ListView(
              children: [
                const SizedBox(height: 8),

                // 头像
                _buildMenuItem(
                  isDark: isDark,
                  title: '头像',
                  trailing: _buildAvatarPreview(isDark),
                  onTap: _pickAndUploadAvatar,
                ),

                const SizedBox(height: 8),

                // 昵称
                _buildMenuItem(
                  isDark: isDark,
                  title: '昵称',
                  value: _user.nickname ?? '未设置',
                  onTap: _editNickname,
                ),

                // Nexus号（不可编辑）
                _buildMenuItem(
                  isDark: isDark,
                  title: 'Nexus号',
                  value: _user.username,
                  showArrow: false,
                ),

                const SizedBox(height: 8),

                // 个性签名
                _buildMenuItem(
                  isDark: isDark,
                  title: '个性签名',
                  value: _user.bio?.isNotEmpty == true ? _user.bio! : '未设置',
                  onTap: _editBio,
                ),
              ],
            ),

            // 加载指示器
            if (_isLoading)
              Container(
                color: Colors.black26,
                child: const Center(
                  child: CircularProgressIndicator(color: AppTheme.primary),
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// 构建头像预览
  Widget _buildAvatarPreview(bool isDark) {
    final fullAvatarUrl = ApiConfig.getFullUrl(_user.avatarUrl);
    final displayName = _user.displayName;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 60,
          height: 60,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.1),
                blurRadius: 4,
                offset: const Offset(0, 2),
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: fullAvatarUrl.isNotEmpty
                ? CachedNetworkImage(
                    imageUrl: fullAvatarUrl,
                    fit: BoxFit.cover,
                    placeholder: (context, url) => _buildDefaultAvatar(displayName, isDark),
                    errorWidget: (context, url, error) => _buildDefaultAvatar(displayName, isDark),
                  )
                : _buildDefaultAvatar(displayName, isDark),
          ),
        ),
        const SizedBox(width: 8),
        Icon(
          Icons.chevron_right,
          size: 22,
          color: isDark ? Colors.grey[600] : Colors.grey[300],
        ),
      ],
    );
  }

  /// 构建默认头像
  Widget _buildDefaultAvatar(String displayName, bool isDark) {
    final initials = displayName.isNotEmpty ? displayName[0].toUpperCase() : '?';

    return Container(
      color: AppTheme.primary,
      child: Center(
        child: Text(
          initials,
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
        ),
      ),
    );
  }

  /// 构建菜单项
  Widget _buildMenuItem({
    required bool isDark,
    required String title,
    String? value,
    Widget? trailing,
    bool showArrow = true,
    VoidCallback? onTap,
  }) {
    return Container(
      color: isDark ? const Color(0xFF18181B) : Colors.white,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
          child: Row(
            children: [
              // 标题
              Text(
                title,
                style: TextStyle(
                  fontSize: 17,
                  color: isDark ? Colors.white : const Color(0xFF18181B),
                ),
              ),

              const Spacer(),

              // 值或自定义trailing
              if (trailing != null)
                trailing
              else if (value != null) ...[
                Flexible(
                  child: Text(
                    value,
                    style: TextStyle(
                      fontSize: 16,
                      color: isDark ? Colors.grey[400] : Colors.grey[500],
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (showArrow) ...[
                  const SizedBox(width: 8),
                  Icon(
                    Icons.chevron_right,
                    size: 22,
                    color: isDark ? Colors.grey[600] : Colors.grey[300],
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}
