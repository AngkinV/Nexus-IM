import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/datasources/remote/file_api_service.dart';
import '../../../data/models/contact/contact_models.dart';
import '../../../data/repositories/chat_repository.dart';
import '../chat/chat_page.dart';

/// 完善群信息页面
class CompleteGroupInfoPage extends StatefulWidget {
  final int currentUserId;
  final List<ContactModel> selectedContacts;

  const CompleteGroupInfoPage({
    super.key,
    required this.currentUserId,
    required this.selectedContacts,
  });

  @override
  State<CompleteGroupInfoPage> createState() => _CompleteGroupInfoPageState();
}

class _CompleteGroupInfoPageState extends State<CompleteGroupInfoPage> {
  final ChatRepository _chatRepository = ChatRepository();
  final FileApiService _fileApiService = FileApiService();
  final ImagePicker _imagePicker = ImagePicker();
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _descriptionController = TextEditingController();

  File? _selectedAvatar;
  bool _isPrivate = false;
  bool _isCreating = false;
  bool _isUploading = false;

  static const int _maxNameLength = 20;

  @override
  void initState() {
    super.initState();
    // 默认群名为前3个成员名称
    final defaultName = widget.selectedContacts
        .take(3)
        .map((c) => c.displayName)
        .join('、');
    _nameController.text = defaultName.length > _maxNameLength
        ? defaultName.substring(0, _maxNameLength)
        : defaultName;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  bool get _canCreate {
    return _nameController.text.trim().isNotEmpty &&
        !_isCreating &&
        !_isUploading;
  }

  Future<void> _showAvatarOptions() async {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        decoration: BoxDecoration(
          color: isDark ? const Color(0xFF1C1C1E) : Colors.white,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(top: 12),
                decoration: BoxDecoration(
                  color: Colors.grey[300],
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(height: 20),
              ListTile(
                leading: Icon(Icons.photo_library, color: AppTheme.primary),
                title: const Text('从相册选择'),
                onTap: () {
                  Navigator.pop(context);
                  _pickAvatarFromGallery();
                },
              ),
              ListTile(
                leading: Icon(Icons.camera_alt, color: AppTheme.primary),
                title: const Text('拍照'),
                onTap: () {
                  Navigator.pop(context);
                  _takePhoto();
                },
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickAvatarFromGallery() async {
    final permission = await _requestPhotoPermission();
    if (!permission) {
      _showPermissionDeniedDialog();
      return;
    }

    try {
      final pickedFile = await _imagePicker.pickImage(
        source: ImageSource.gallery,
        maxWidth: 512,
        maxHeight: 512,
        imageQuality: 85,
      );

      if (pickedFile != null) {
        setState(() {
          _selectedAvatar = File(pickedFile.path);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('选择图片失败: $e')),
        );
      }
    }
  }

  Future<void> _takePhoto() async {
    final permission = await _requestCameraPermission();
    if (!permission) {
      _showPermissionDeniedDialog(isCamera: true);
      return;
    }

    try {
      final pickedFile = await _imagePicker.pickImage(
        source: ImageSource.camera,
        maxWidth: 512,
        maxHeight: 512,
        imageQuality: 85,
      );

      if (pickedFile != null) {
        setState(() {
          _selectedAvatar = File(pickedFile.path);
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('拍照失败: $e')),
        );
      }
    }
  }

  Future<bool> _requestPhotoPermission() async {
    if (Platform.isIOS) {
      final status = await Permission.photos.request();
      return status.isGranted || status.isLimited;
    } else {
      if (await Permission.photos.isGranted) {
        return true;
      }
      final status = await Permission.photos.request();
      if (status.isGranted) return true;
      final storageStatus = await Permission.storage.request();
      return storageStatus.isGranted;
    }
  }

  Future<bool> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    return status.isGranted;
  }

  void _showPermissionDeniedDialog({bool isCamera = false}) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(isCamera ? '需要相机权限' : '需要相册权限'),
        content: Text(isCamera
            ? '请在设置中允许访问相机以拍照'
            : '请在设置中允许访问相册以选择图片'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('去设置'),
          ),
        ],
      ),
    );
  }

  Future<void> _createGroup() async {
    if (!_canCreate) return;

    setState(() => _isCreating = true);

    try {
      String? avatarUrl;

      // 上传头像
      if (_selectedAvatar != null) {
        setState(() => _isUploading = true);
        try {
          final response = await _fileApiService.uploadFile(
            _selectedAvatar!,
            uploaderId: widget.currentUserId,
          );
          final baseUrl = ApiConfig.getBaseUrl(isAndroid: Platform.isAndroid);
          avatarUrl = response.getFullFileUrl(baseUrl);
        } finally {
          setState(() => _isUploading = false);
        }
      }

      // 创建群聊
      final chat = await _chatRepository.createGroupChat(
        userId: widget.currentUserId,
        name: _nameController.text.trim(),
        description: _descriptionController.text.trim().isEmpty
            ? null
            : _descriptionController.text.trim(),
        avatar: avatarUrl,
        isPrivate: _isPrivate,
        memberIds: widget.selectedContacts.map((c) => c.userId).toList(),
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('群聊创建成功')),
        );
        // 清除导航栈并跳转到聊天页面
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (_) => ChatPage(chat: chat)),
          (route) => route.isFirst,
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('创建失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isCreating = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final backgroundColor = isDark ? const Color(0xFF111111) : const Color(0xFFF9F9F8);

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
        statusBarBrightness: isDark ? Brightness.dark : Brightness.light,
      ),
      child: Scaffold(
        backgroundColor: backgroundColor,
        body: Stack(
          children: [
            // 主内容
            Column(
              children: [
                // 头部
                _buildHeader(isDark),

                // 可滚动内容区域
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 140),
                    child: Column(
                      children: [
                        // 群头像
                        _buildAvatarSection(isDark),

                        const SizedBox(height: 32),

                        // 群名称和简介卡片
                        _buildInfoCard(isDark),

                        const SizedBox(height: 24),

                        // 群类型选择
                        _buildGroupTypeSection(isDark),

                        const SizedBox(height: 24),

                        // 提示文字
                        _buildTips(isDark),
                      ],
                    ),
                  ),
                ),
              ],
            ),

            // 底部固定按钮
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _buildBottomButton(context, isDark, backgroundColor),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return Container(
      color: isDark ? const Color(0xFF111111) : const Color(0xFFF9F9F8),
      child: SafeArea(
        bottom: false,
        child: Container(
          height: 56,
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Row(
            children: [
              // 返回按钮
              IconButton(
                onPressed: () => Navigator.pop(context),
                icon: Icon(
                  Icons.arrow_back_ios,
                  color: isDark ? Colors.white : Colors.grey[800],
                  size: 22,
                ),
              ),

              const Expanded(
                child: Center(
                  child: Text(
                    '完善群信息',
                    style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),

              // 占位，保持标题居中
              const SizedBox(width: 48),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAvatarSection(bool isDark) {
    return GestureDetector(
      onTap: _showAvatarOptions,
      child: Container(
        width: 100,
        height: 100,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isDark ? Colors.grey[800] : Colors.grey[100],
          border: Border.all(
            color: AppTheme.primary.withValues(alpha: 0.3),
            width: 0.5,
          ),
        ),
        child: ClipOval(
          child: Stack(
            fit: StackFit.expand,
            children: [
              // 头像或默认图标
              if (_selectedAvatar != null)
                Image.file(
                  _selectedAvatar!,
                  fit: BoxFit.cover,
                )
              else
                Center(
                  child: Icon(
                    Icons.groups,
                    size: 48,
                    color: isDark ? Colors.grey[600] : Colors.grey[300],
                  ),
                ),

              // 相机覆盖层
              Container(
                color: AppTheme.primary.withValues(alpha: 0.1),
                child: Center(
                  child: Icon(
                    Icons.photo_camera,
                    size: 32,
                    color: AppTheme.primary.withValues(alpha: 0.8),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInfoCard(bool isDark) {
    return Container(
      decoration: BoxDecoration(
        color: isDark ? Colors.grey[900] : Colors.white,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(
          color: isDark ? Colors.grey[800]! : const Color(0xFFEFEFEF),
        ),
      ),
      child: Column(
        children: [
          // 群名称
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              border: Border(
                bottom: BorderSide(
                  color: isDark ? Colors.grey[800]! : const Color(0xFFEFEFEF),
                ),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      '群名称',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: isDark ? Colors.grey[500] : Colors.grey[400],
                      ),
                    ),
                    Text(
                      '${_nameController.text.length}/$_maxNameLength',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w500,
                        color: Colors.grey[300],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _nameController,
                  maxLength: _maxNameLength,
                  style: TextStyle(
                    fontSize: 16,
                    color: isDark ? Colors.white : Colors.grey[900],
                  ),
                  decoration: InputDecoration(
                    hintText: '给群组起个响亮的名字',
                    hintStyle: TextStyle(
                      color: isDark ? Colors.grey[600] : Colors.grey[300],
                      fontSize: 16,
                    ),
                    border: InputBorder.none,
                    contentPadding: EdgeInsets.zero,
                    counterText: '', // 隐藏默认计数器
                    isDense: true,
                  ),
                  onChanged: (_) => setState(() {}),
                ),
              ],
            ),
          ),

          // 群简介
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '群简介',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: isDark ? Colors.grey[500] : Colors.grey[400],
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _descriptionController,
                  maxLines: 3,
                  style: TextStyle(
                    fontSize: 15,
                    height: 1.5,
                    color: isDark ? Colors.white : Colors.grey[900],
                  ),
                  decoration: InputDecoration(
                    hintText: '向成员介绍本群...',
                    hintStyle: TextStyle(
                      color: isDark ? Colors.grey[600] : Colors.grey[300],
                      fontSize: 15,
                    ),
                    border: InputBorder.none,
                    contentPadding: EdgeInsets.zero,
                    isDense: true,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGroupTypeSection(bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 12),
          child: Text(
            '群类型',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: isDark ? Colors.grey[500] : Colors.grey[400],
            ),
          ),
        ),
        Container(
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            color: isDark ? Colors.grey[900] : Colors.white,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(
              color: isDark ? Colors.grey[800]! : const Color(0xFFEFEFEF),
            ),
          ),
          child: Row(
            children: [
              // 公有群组
              Expanded(
                child: _buildTypeOption(
                  icon: Icons.public,
                  title: '公有群组',
                  subtitle: '所有人可通过搜索加入',
                  isSelected: !_isPrivate,
                  isDark: isDark,
                  onTap: () => setState(() => _isPrivate = false),
                ),
              ),
              const SizedBox(width: 4),
              // 私有群组
              Expanded(
                child: _buildTypeOption(
                  icon: Icons.lock,
                  title: '私有群组',
                  subtitle: '仅限邀请或扫码加入',
                  isSelected: _isPrivate,
                  isDark: isDark,
                  onTap: () => setState(() => _isPrivate = true),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildTypeOption({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool isSelected,
    required bool isDark,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: isSelected
              ? (isDark ? const Color(0xFF1A1A1A) : const Color(0xFFF9F9F8))
              : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          border: isSelected
              ? Border.all(
                  color: isDark ? Colors.grey[700]! : const Color(0xFFEFEFEF),
                )
              : null,
          boxShadow: isSelected
              ? [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.05),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ]
              : null,
        ),
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 200),
          opacity: isSelected ? 1.0 : 0.6,
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: isSelected
                      ? AppTheme.primary.withValues(alpha: 0.1)
                      : (isDark ? Colors.grey[800] : Colors.grey[100]),
                ),
                child: Icon(
                  icon,
                  size: 20,
                  color: isSelected
                      ? AppTheme.primary
                      : (isDark ? Colors.grey[500] : Colors.grey[400]),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: isDark ? Colors.white : Colors.grey[900],
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 11,
                        color: isDark ? Colors.grey[500] : Colors.grey[400],
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTips(bool isDark) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 4, right: 8),
          child: Icon(
            Icons.verified_user,
            size: 16,
            color: isDark ? Colors.grey[600] : Colors.grey[300],
          ),
        ),
        Expanded(
          child: Text(
            'Nexus 提倡文明交流，群组信息需符合社区准则。',
            style: TextStyle(
              fontSize: 12,
              height: 1.4,
              color: isDark ? Colors.grey[500] : Colors.grey[400],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBottomButton(BuildContext context, bool isDark, Color backgroundColor) {
    final bottomPadding = MediaQuery.of(context).padding.bottom;

    return Container(
      padding: EdgeInsets.fromLTRB(24, 40, 24, bottomPadding + 16),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          stops: const [0.0, 0.3, 1.0],
          colors: [
            backgroundColor.withValues(alpha: 0),
            backgroundColor,
            backgroundColor,
          ],
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 创建按钮
          GestureDetector(
            onTap: _canCreate ? _createGroup : null,
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 16),
              decoration: BoxDecoration(
                color: _canCreate ? AppTheme.primary : Colors.grey[300],
                borderRadius: BorderRadius.circular(28),
                boxShadow: _canCreate
                    ? [
                        BoxShadow(
                          color: AppTheme.primary.withValues(alpha: 0.4),
                          blurRadius: 20,
                          offset: const Offset(0, 8),
                        ),
                      ]
                    : null,
              ),
              child: Center(
                child: _isCreating || _isUploading
                    ? Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Text(
                            _isUploading ? '上传中...' : '创建中...',
                            style: const TextStyle(
                              fontSize: 17,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                        ],
                      )
                    : Text(
                        '立即创建',
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.bold,
                          color: _canCreate ? Colors.white : Colors.grey[500],
                          letterSpacing: 0.5,
                        ),
                      ),
              ),
            ),
          ),

          // 底部指示条
          const SizedBox(height: 24),
          Container(
            width: 128,
            height: 4,
            decoration: BoxDecoration(
              color: isDark ? Colors.grey[700] : Colors.grey[200],
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ],
      ),
    );
  }
}
