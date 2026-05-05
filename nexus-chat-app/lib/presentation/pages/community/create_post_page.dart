import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/storage/secure_storage.dart';
import '../../../data/datasources/remote/file_api_service.dart';
import '../../../data/repositories/post_repository.dart';
import '../../../data/repositories/contact_repository.dart';

/// 创建帖子页面
class CreatePostPage extends StatefulWidget {
  const CreatePostPage({super.key});

  @override
  State<CreatePostPage> createState() => _CreatePostPageState();
}

class _CreatePostPageState extends State<CreatePostPage> {
  final TextEditingController _titleController = TextEditingController();
  final TextEditingController _contentController = TextEditingController();
  final PostRepository _postRepository = PostRepository();
  final SecureStorageService _secureStorage = SecureStorageService();
  final FileApiService _fileApiService = FileApiService();
  final ContactRepository _contactRepository = ContactRepository();
  final ImagePicker _imagePicker = ImagePicker();
  final FocusNode _contentFocusNode = FocusNode();

  final List<File> _selectedImages = [];
  final List<String> _uploadedImageUrls = [];
  bool _isSubmitting = false;
  bool _isUploading = false;
  int? _currentUserId;
  String _visibility = '公开';
  int _characterCount = 0;
  static const int _maxCharacters = 1000;
  static const int _maxImages = 9;

  // UI状态
  bool _showEmojiPicker = false;
  bool _showMentionPicker = false;
  bool _showPollCreator = false;
  int _selectedEmojiCategory = 0;

  // 投票相关
  final List<TextEditingController> _pollOptionControllers = [
    TextEditingController(),
    TextEditingController(),
  ];

  // @提及相关
  List<Map<String, dynamic>> _contacts = [];

  // 2026热门表情分类
  static const List<Map<String, dynamic>> _emojiCategories = [
    {
      'name': '热门',
      'icon': '🔥',
      'emojis': [
        '😂', '🤣', '😭', '💀', '🥺', '✨', '🔥', '❤️', '😍', '🥰',
        '😊', '🙏', '👍', '👀', '💯', '🎉', '😎', '🤔', '😅', '🤪',
        '🥳', '😈', '👻', '💅', '✌️', '🤝', '💪', '🙌', '👏', '🤗',
        '😘', '🫶', '💕', '💖', '💗', '🫠', '🤭', '😏', '🙄', '😤',
      ],
    },
    {
      'name': '抖音',
      'icon': '🎵',
      'emojis': [
        '🤡', '💀', '😭', '🤣', '😂', '🥵', '🥶', '🤯', '🫣', '🫡',
        '🫥', '🫨', '🤌', '🫰', '🫳', '🫴', '🫵', '🫱', '🫲', '🤙',
        '🦋', '🌈', '⭐', '🌟', '💫', '🎀', '🎭', '🎪', '🎢', '🎡',
        '🧿', '🪬', '🔮', '🪩', '💎', '👑', '🦄', '🐉', '🦁', '🐯',
      ],
    },
    {
      'name': '海外热门',
      'icon': '🌍',
      'emojis': [
        '💀', '☠️', '🗿', '🤡', '👽', '🛸', '🚀', '🌙', '⭐', '🌟',
        '💫', '✨', '🔥', '💥', '💢', '💦', '💨', '🕳️', '💣', '💊',
        '🧠', '👁️', '🫀', '🫁', '🦴', '👅', '👄', '💋', '🩸', '🦷',
        '🪨', '🌵', '🍄', '🌸', '🌺', '🌻', '🌼', '🌷', '🌹', '🥀',
      ],
    },
    {
      'name': '表情',
      'icon': '😀',
      'emojis': [
        '😀', '😃', '😄', '😁', '😆', '😅', '🤣', '😂', '🙂', '🙃',
        '😉', '😊', '😇', '🥰', '😍', '🤩', '😘', '😗', '☺️', '😚',
        '😙', '🥲', '😋', '😛', '😜', '🤪', '😝', '🤑', '🤗', '🤭',
        '🫢', '🫣', '🤫', '🤔', '🫡', '🤐', '🤨', '😐', '😑', '😶',
      ],
    },
    {
      'name': '手势',
      'icon': '👋',
      'emojis': [
        '👋', '🤚', '🖐️', '✋', '🖖', '🫱', '🫲', '🫳', '🫴', '👌',
        '🤌', '🤏', '✌️', '🤞', '🫰', '🤟', '🤘', '🤙', '👈', '👉',
        '👆', '🖕', '👇', '☝️', '🫵', '👍', '👎', '✊', '👊', '🤛',
        '🤜', '👏', '🙌', '🫶', '👐', '🤲', '🤝', '🙏', '✍️', '💅',
      ],
    },
    {
      'name': '动物',
      'icon': '🐶',
      'emojis': [
        '🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼', '🐻‍❄️', '🐨',
        '🐯', '🦁', '🐮', '🐷', '🐸', '🐵', '🙈', '🙉', '🙊', '🐔',
        '🐧', '🐦', '🐤', '🦆', '🦅', '🦉', '🦇', '🐺', '🐗', '🐴',
        '🦄', '🐝', '🪱', '🐛', '🦋', '🐌', '🐞', '🐜', '🪰', '🪲',
      ],
    },
    {
      'name': '食物',
      'icon': '🍔',
      'emojis': [
        '🍎', '🍐', '🍊', '🍋', '🍌', '🍉', '🍇', '🍓', '🫐', '🍈',
        '🍒', '🍑', '🥭', '🍍', '🥥', '🥝', '🍅', '🍆', '🥑', '🥦',
        '🍔', '🍟', '🍕', '🌭', '🥪', '🌮', '🌯', '🫔', '🥙', '🧆',
        '🍜', '🍝', '🍣', '🍱', '🥟', '🍤', '🍩', '🍪', '🎂', '🧁',
      ],
    },
    {
      'name': '符号',
      'icon': '❤️',
      'emojis': [
        '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍', '🤎', '💔',
        '❤️‍🔥', '❤️‍🩹', '💕', '💞', '💓', '💗', '💖', '💘', '💝', '💟',
        '☮️', '✝️', '☪️', '🕉️', '☸️', '✡️', '🔯', '🕎', '☯️', '☦️',
        '♈', '♉', '♊', '♋', '♌', '♍', '♎', '♏', '♐', '♑',
      ],
    },
  ];

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
    _contentController.addListener(_updateCharacterCount);
  }

  @override
  void dispose() {
    _titleController.dispose();
    _contentController.dispose();
    _contentFocusNode.dispose();
    for (var controller in _pollOptionControllers) {
      controller.dispose();
    }
    super.dispose();
  }

  void _updateCharacterCount() {
    setState(() {
      _characterCount = _contentController.text.length;
    });
  }

  Future<void> _loadCurrentUser() async {
    final userId = await _secureStorage.getUserId();
    setState(() {
      _currentUserId = userId;
    });
    if (userId != null) {
      _loadContacts();
    }
  }

  Future<void> _loadContacts() async {
    if (_currentUserId == null) return;
    try {
      final contacts = await _contactRepository.getContacts(_currentUserId!);
      setState(() {
        _contacts = contacts.map((c) => {
          'id': c.userId,
          'username': c.username,
          'nickname': c.displayName,
          'avatarUrl': c.avatarUrl,
        }).toList();
      });
    } catch (e) {
      debugPrint('加载联系人失败: $e');
    }
  }

  bool get _canSubmit {
    return _contentController.text.trim().isNotEmpty &&
        _currentUserId != null &&
        !_isSubmitting &&
        !_isUploading &&
        _characterCount <= _maxCharacters;
  }

  Future<void> _submitPost() async {
    if (!_canSubmit) return;

    setState(() => _isSubmitting = true);

    try {
      // 上传图片
      final imageUrls = <String>[];
      if (_selectedImages.isNotEmpty) {
        setState(() => _isUploading = true);
        for (final file in _selectedImages) {
          final response = await _fileApiService.uploadFile(
            file,
            uploaderId: _currentUserId,
          );
          // 构建完整URL
          final baseUrl = ApiConfig.getBaseUrl(isAndroid: Platform.isAndroid);
          imageUrls.add(response.getFullFileUrl(baseUrl));
        }
        setState(() => _isUploading = false);
      }

      // 合并已上传的URL
      imageUrls.addAll(_uploadedImageUrls);

      await _postRepository.createPost(
        authorId: _currentUserId!,
        title: _titleController.text.trim().isEmpty
            ? null
            : _titleController.text.trim(),
        content: _contentController.text.trim(),
        images: imageUrls,
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('发布成功')),
        );
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('发布失败: ${e.toString()}')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _isUploading = false;
        });
      }
    }
  }

  // ==================== 图片选择 ====================

  Future<void> _showImageSourceOptions() async {
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
                subtitle: Text('最多选择 ${_maxImages - _selectedImages.length} 张'),
                onTap: () {
                  Navigator.pop(context);
                  _pickImagesFromGallery();
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
              ListTile(
                leading: Icon(Icons.link, color: Colors.grey[600]),
                title: const Text('输入图片链接'),
                onTap: () {
                  Navigator.pop(context);
                  _addImageUrl();
                },
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickImagesFromGallery() async {
    // 检查权限
    final permission = await _requestPhotoPermission();
    if (!permission) {
      _showPermissionDeniedDialog();
      return;
    }

    try {
      final remaining = _maxImages - _selectedImages.length;
      if (remaining <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('最多只能添加 $_maxImages 张图片')),
        );
        return;
      }

      final pickedFiles = await _imagePicker.pickMultiImage(
        maxWidth: 1920,
        maxHeight: 1920,
        imageQuality: 85,
      );

      if (pickedFiles.isNotEmpty) {
        final filesToAdd = pickedFiles.take(remaining).map((xFile) => File(xFile.path)).toList();
        setState(() {
          _selectedImages.addAll(filesToAdd);
        });
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('选择图片失败: $e')),
      );
    }
  }

  Future<void> _takePhoto() async {
    // 检查相机权限
    final permission = await _requestCameraPermission();
    if (!permission) {
      _showPermissionDeniedDialog(isCamera: true);
      return;
    }

    if (_selectedImages.length >= _maxImages) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('最多只能添加 $_maxImages 张图片')),
      );
      return;
    }

    try {
      final pickedFile = await _imagePicker.pickImage(
        source: ImageSource.camera,
        maxWidth: 1920,
        maxHeight: 1920,
        imageQuality: 85,
      );

      if (pickedFile != null) {
        setState(() {
          _selectedImages.add(File(pickedFile.path));
        });
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('拍照失败: $e')),
      );
    }
  }

  Future<bool> _requestPhotoPermission() async {
    if (Platform.isIOS) {
      final status = await Permission.photos.request();
      return status.isGranted || status.isLimited;
    } else {
      // Android 13+
      if (await Permission.photos.isGranted) {
        return true;
      }
      final status = await Permission.photos.request();
      if (status.isGranted) return true;

      // Fallback for older Android versions
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

  void _addImageUrl() {
    showDialog(
      context: context,
      builder: (context) {
        final controller = TextEditingController();
        return AlertDialog(
          title: const Text('添加图片链接'),
          content: TextField(
            controller: controller,
            decoration: const InputDecoration(
              hintText: '请输入图片URL',
              border: OutlineInputBorder(),
            ),
            autofocus: true,
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () {
                if (controller.text.trim().isNotEmpty) {
                  setState(() {
                    _uploadedImageUrls.add(controller.text.trim());
                  });
                }
                Navigator.pop(context);
              },
              child: const Text('确定'),
            ),
          ],
        );
      },
    );
  }

  void _removeImage(int index, {bool isUrl = false}) {
    setState(() {
      if (isUrl) {
        _uploadedImageUrls.removeAt(index);
      } else {
        _selectedImages.removeAt(index);
      }
    });
  }

  // ==================== 表情选择 ====================

  void _toggleEmojiPicker() {
    if (_showEmojiPicker) {
      setState(() => _showEmojiPicker = false);
      _contentFocusNode.requestFocus();
    } else {
      _contentFocusNode.unfocus();
      setState(() {
        _showEmojiPicker = true;
        _showMentionPicker = false;
        _showPollCreator = false;
      });
    }
  }

  void _insertEmoji(String emoji) {
    final text = _contentController.text;
    final selection = _contentController.selection;
    final start = selection.start >= 0 ? selection.start : text.length;
    final end = selection.end >= 0 ? selection.end : text.length;
    final newText = text.replaceRange(start, end, emoji);
    _contentController.text = newText;
    _contentController.selection = TextSelection.collapsed(
      offset: start + emoji.length,
    );
  }

  // ==================== @提及 ====================

  void _toggleMentionPicker() {
    setState(() {
      _showMentionPicker = !_showMentionPicker;
      _showEmojiPicker = false;
      _showPollCreator = false;
    });
    if (!_showMentionPicker) {
      _contentFocusNode.requestFocus();
    }
  }

  void _insertMention(Map<String, dynamic> contact) {
    final mention = '@${contact['nickname'] ?? contact['username']} ';
    final text = _contentController.text;
    final selection = _contentController.selection;
    final start = selection.start >= 0 ? selection.start : text.length;
    final end = selection.end >= 0 ? selection.end : text.length;
    final newText = text.replaceRange(start, end, mention);
    _contentController.text = newText;
    _contentController.selection = TextSelection.collapsed(
      offset: start + mention.length,
    );
    setState(() => _showMentionPicker = false);
    _contentFocusNode.requestFocus();
  }

  // ==================== 投票 ====================

  void _togglePollCreator() {
    setState(() {
      _showPollCreator = !_showPollCreator;
      _showEmojiPicker = false;
      _showMentionPicker = false;
    });
  }

  void _addPollOption() {
    if (_pollOptionControllers.length < 6) {
      setState(() {
        _pollOptionControllers.add(TextEditingController());
      });
    }
  }

  void _removePollOption(int index) {
    if (_pollOptionControllers.length > 2) {
      setState(() {
        _pollOptionControllers[index].dispose();
        _pollOptionControllers.removeAt(index);
      });
    }
  }

  // ==================== 可见性选择 ====================

  void _showVisibilityOptions() {
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
              _buildVisibilityOption('公开', Icons.public, isDark),
              _buildVisibilityOption('仅好友', Icons.people, isDark),
              _buildVisibilityOption('仅自己', Icons.lock, isDark),
              const SizedBox(height: 20),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildVisibilityOption(String option, IconData icon, bool isDark) {
    final isSelected = _visibility == option;
    return ListTile(
      leading: Icon(
        icon,
        color: isSelected ? AppTheme.primary : Colors.grey[500],
      ),
      title: Text(
        option,
        style: TextStyle(
          fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          color: isSelected ? AppTheme.primary : (isDark ? Colors.white : Colors.black),
        ),
      ),
      trailing: isSelected ? Icon(Icons.check, color: AppTheme.primary) : null,
      onTap: () {
        setState(() => _visibility = option);
        Navigator.pop(context);
      },
    );
  }

  // ==================== UI构建 ====================

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final backgroundColor = isDark ? const Color(0xFF111111) : Colors.white;

    // 设置状态栏样式以匹配背景
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
        statusBarBrightness: isDark ? Brightness.dark : Brightness.light,
        systemNavigationBarColor: backgroundColor,
        systemNavigationBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
      ),
      child: Scaffold(
        backgroundColor: backgroundColor,
        resizeToAvoidBottomInset: true,
        body: Stack(
          children: [
            // 背景装饰
            Positioned(
              top: -96,
              right: -96,
              child: Container(
                width: 384,
                height: 384,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppTheme.primary.withValues(alpha: 0.03),
                ),
              ),
            ),
            Positioned(
              top: MediaQuery.of(context).size.height / 2,
              left: -96,
              child: Container(
                width: 256,
                height: 256,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppTheme.primary.withValues(alpha: 0.02),
                ),
              ),
            ),

            // 主内容
            Column(
              children: [
                // 顶部安全区域 - 添加背景色以消除色差
                Container(
                  color: backgroundColor,
                  child: SafeArea(
                    bottom: false,
                    child: _buildHeader(isDark),
                  ),
                ),

                // 内容区域
                Expanded(
                  child: GestureDetector(
                    onTap: () {
                      setState(() {
                        _showEmojiPicker = false;
                        _showMentionPicker = false;
                        _showPollCreator = false;
                      });
                      _contentFocusNode.requestFocus();
                    },
                    child: SingleChildScrollView(
                      padding: EdgeInsets.fromLTRB(
                        24,
                        8,
                        24,
                        _showEmojiPicker || _showMentionPicker || _showPollCreator ? 20 : 120,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // 标签按钮
                          _buildTagButtons(isDark),

                          const SizedBox(height: 24),

                          // 标题输入
                          TextField(
                            controller: _titleController,
                            decoration: InputDecoration(
                              hintText: '起个标题...',
                              hintStyle: TextStyle(
                                color: isDark ? Colors.grey[700] : Colors.grey[300],
                                fontSize: 28,
                                fontWeight: FontWeight.bold,
                                letterSpacing: -0.5,
                              ),
                              border: InputBorder.none,
                              contentPadding: EdgeInsets.zero,
                            ),
                            style: TextStyle(
                              fontSize: 28,
                              fontWeight: FontWeight.bold,
                              color: isDark ? Colors.white : const Color(0xFF1A1C1E),
                              letterSpacing: -0.5,
                            ),
                            maxLines: null,
                            onChanged: (_) => setState(() {}),
                          ),

                          const SizedBox(height: 16),

                          // 内容输入
                          TextField(
                            controller: _contentController,
                            focusNode: _contentFocusNode,
                            decoration: InputDecoration(
                              hintText: '分享你的新鲜事...',
                              hintStyle: TextStyle(
                                color: isDark ? Colors.grey[600] : Colors.grey[400],
                                fontSize: 18,
                                height: 1.6,
                              ),
                              border: InputBorder.none,
                              contentPadding: EdgeInsets.zero,
                            ),
                            style: TextStyle(
                              fontSize: 18,
                              color: isDark ? Colors.grey[300] : Colors.grey[700],
                              height: 1.6,
                            ),
                            maxLines: null,
                            minLines: 10,
                            onChanged: (_) => setState(() {}),
                          ),

                          // 图片预览
                          if (_selectedImages.isNotEmpty || _uploadedImageUrls.isNotEmpty) ...[
                            const SizedBox(height: 24),
                            _buildImagePreview(isDark),
                          ],

                          // 投票预览
                          if (_showPollCreator) ...[
                            const SizedBox(height: 24),
                            _buildPollCreator(isDark),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),

                // 底部面板
                if (_showEmojiPicker) _buildEmojiPicker(isDark),
                if (_showMentionPicker) _buildMentionPicker(isDark),

                // 底部工具栏
                if (!_showEmojiPicker && !_showMentionPicker)
                  Padding(
                    padding: EdgeInsets.fromLTRB(
                      24,
                      0,
                      24,
                      MediaQuery.of(context).padding.bottom + 20,
                    ),
                    child: _buildBottomToolbar(isDark),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(bool isDark) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            AppTheme.primary.withValues(alpha: 0.08),
            Colors.transparent,
          ],
        ),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // 关闭按钮
          GestureDetector(
            onTap: () => Navigator.pop(context),
            child: Container(
              padding: const EdgeInsets.all(8),
              child: Icon(
                Icons.close,
                size: 24,
                color: isDark ? Colors.white : Colors.grey[800],
              ),
            ),
          ),

          // 标题
          Text(
            '发布帖子',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.bold,
              letterSpacing: -0.3,
              color: isDark ? Colors.white : const Color(0xFF1A1C1E),
            ),
          ),

          // 发布按钮
          GestureDetector(
            onTap: _canSubmit ? _submitPost : null,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              decoration: BoxDecoration(
                color: _canSubmit ? AppTheme.primary : Colors.grey[300],
                borderRadius: BorderRadius.circular(20),
                boxShadow: _canSubmit
                    ? [
                        BoxShadow(
                          color: AppTheme.primary.withValues(alpha: 0.3),
                          blurRadius: 8,
                          offset: const Offset(0, 2),
                        ),
                      ]
                    : null,
              ),
              child: _isSubmitting || _isUploading
                  ? Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          _isUploading ? '上传中' : '发布中',
                          style: const TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                      ],
                    )
                  : Text(
                      '发布',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: _canSubmit ? Colors.white : Colors.grey[500],
                      ),
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTagButtons(bool isDark) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        // 选择话题按钮
        GestureDetector(
          onTap: () {
            // TODO: 选择话题
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: AppTheme.primary.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: AppTheme.primary.withValues(alpha: 0.1),
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.tag, size: 14, color: AppTheme.primary),
                const SizedBox(width: 4),
                Text(
                  '选择话题',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppTheme.primary,
                  ),
                ),
              ],
            ),
          ),
        ),

        // 可见性按钮
        GestureDetector(
          onTap: _showVisibilityOptions,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: isDark
                  ? Colors.grey[800]?.withValues(alpha: 0.5)
                  : Colors.grey[500]?.withValues(alpha: 0.05),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: isDark
                    ? Colors.grey[700]!
                    : Colors.grey[500]!.withValues(alpha: 0.1),
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  _visibility == '公开'
                      ? Icons.public
                      : (_visibility == '仅好友' ? Icons.people : Icons.lock),
                  size: 14,
                  color: isDark ? Colors.grey[400] : Colors.grey[600],
                ),
                const SizedBox(width: 4),
                Text(
                  _visibility,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: isDark ? Colors.grey[400] : Colors.grey[600],
                  ),
                ),
                const SizedBox(width: 2),
                Icon(
                  Icons.expand_more,
                  size: 14,
                  color: isDark ? Colors.grey[400] : Colors.grey[600],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildImagePreview(bool isDark) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            // 本地选择的图片
            ..._selectedImages.asMap().entries.map((entry) {
              return _buildImageItem(
                child: Image.file(entry.value, fit: BoxFit.cover),
                onRemove: () => _removeImage(entry.key),
                isDark: isDark,
              );
            }),
            // URL图片
            ..._uploadedImageUrls.asMap().entries.map((entry) {
              return _buildImageItem(
                child: Image.network(
                  entry.value,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    color: Colors.grey[300],
                    child: const Icon(Icons.image_not_supported),
                  ),
                ),
                onRemove: () => _removeImage(entry.key, isUrl: true),
                isDark: isDark,
              );
            }),
            // 添加更多按钮
            if (_selectedImages.length + _uploadedImageUrls.length < _maxImages)
              GestureDetector(
                onTap: _showImageSourceOptions,
                child: Container(
                  width: 100,
                  height: 100,
                  decoration: BoxDecoration(
                    color: isDark ? Colors.grey[800] : Colors.grey[100],
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: isDark ? Colors.grey[700]! : Colors.grey[300]!,
                      style: BorderStyle.solid,
                    ),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        Icons.add,
                        size: 32,
                        color: isDark ? Colors.grey[500] : Colors.grey[400],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${_selectedImages.length + _uploadedImageUrls.length}/$_maxImages',
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.grey[500],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }

  Widget _buildImageItem({
    required Widget child,
    required VoidCallback onRemove,
    required bool isDark,
  }) {
    return Stack(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: SizedBox(
            width: 100,
            height: 100,
            child: child,
          ),
        ),
        Positioned(
          top: 6,
          right: 6,
          child: GestureDetector(
            onTap: onRemove,
            child: Container(
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.6),
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.close,
                color: Colors.white,
                size: 14,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildPollCreator(bool isDark) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isDark ? Colors.grey[900] : Colors.grey[50],
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isDark ? Colors.grey[800]! : Colors.grey[200]!,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '创建投票',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: isDark ? Colors.white : Colors.grey[800],
                ),
              ),
              GestureDetector(
                onTap: () => setState(() => _showPollCreator = false),
                child: Icon(Icons.close, size: 20, color: Colors.grey[500]),
              ),
            ],
          ),
          const SizedBox(height: 16),
          ..._pollOptionControllers.asMap().entries.map((entry) {
            return Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: entry.value,
                      decoration: InputDecoration(
                        hintText: '选项 ${entry.key + 1}',
                        hintStyle: TextStyle(color: Colors.grey[400]),
                        filled: true,
                        fillColor: isDark ? Colors.grey[800] : Colors.white,
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide(
                            color: isDark ? Colors.grey[700]! : Colors.grey[300]!,
                          ),
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide(
                            color: isDark ? Colors.grey[700]! : Colors.grey[300]!,
                          ),
                        ),
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 12,
                        ),
                      ),
                    ),
                  ),
                  if (_pollOptionControllers.length > 2)
                    Padding(
                      padding: const EdgeInsets.only(left: 8),
                      child: GestureDetector(
                        onTap: () => _removePollOption(entry.key),
                        child: Icon(
                          Icons.remove_circle_outline,
                          color: Colors.red[400],
                        ),
                      ),
                    ),
                ],
              ),
            );
          }),
          if (_pollOptionControllers.length < 6)
            GestureDetector(
              onTap: _addPollOption,
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.add, size: 20, color: AppTheme.primary),
                    const SizedBox(width: 8),
                    Text(
                      '添加选项',
                      style: TextStyle(
                        color: AppTheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildEmojiPicker(bool isDark) {
    final currentEmojis = _emojiCategories[_selectedEmojiCategory]['emojis'] as List<String>;

    return Container(
      height: 320,
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1C1C1E) : Colors.white,
        border: Border(
          top: BorderSide(
            color: isDark ? Colors.grey[800]! : Colors.grey[200]!,
          ),
        ),
      ),
      child: Column(
        children: [
          // 分类标签
          Container(
            height: 50,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              itemCount: _emojiCategories.length,
              itemBuilder: (context, index) {
                final category = _emojiCategories[index];
                final isSelected = _selectedEmojiCategory == index;
                return GestureDetector(
                  onTap: () => setState(() => _selectedEmojiCategory = index),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? AppTheme.primary.withValues(alpha: 0.15)
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Row(
                      children: [
                        Text(
                          category['icon'] as String,
                          style: const TextStyle(fontSize: 18),
                        ),
                        const SizedBox(width: 4),
                        Text(
                          category['name'] as String,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                            color: isSelected
                                ? AppTheme.primary
                                : (isDark ? Colors.grey[400] : Colors.grey[600]),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),

          // 分隔线
          Divider(height: 1, color: isDark ? Colors.grey[800] : Colors.grey[200]),

          // 表情网格
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.all(12),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 8,
                childAspectRatio: 1,
                crossAxisSpacing: 4,
                mainAxisSpacing: 4,
              ),
              itemCount: currentEmojis.length,
              itemBuilder: (context, index) {
                final emoji = currentEmojis[index];
                return GestureDetector(
                  onTap: () => _insertEmoji(emoji),
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Center(
                      child: Text(
                        emoji,
                        style: const TextStyle(fontSize: 26),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),

          // 底部关闭按钮
          Container(
            padding: EdgeInsets.only(
              left: 16,
              right: 16,
              bottom: MediaQuery.of(context).padding.bottom + 8,
              top: 8,
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${_emojiCategories[_selectedEmojiCategory]['name']}',
                  style: TextStyle(
                    fontSize: 13,
                    color: Colors.grey[500],
                  ),
                ),
                GestureDetector(
                  onTap: _toggleEmojiPicker,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    decoration: BoxDecoration(
                      color: isDark ? Colors.grey[800] : Colors.grey[100],
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.keyboard, size: 18, color: Colors.grey[600]),
                        const SizedBox(width: 6),
                        Text(
                          '键盘',
                          style: TextStyle(
                            fontSize: 13,
                            color: Colors.grey[600],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMentionPicker(bool isDark) {
    return Container(
      height: 280,
      decoration: BoxDecoration(
        color: isDark ? const Color(0xFF1C1C1E) : Colors.white,
        border: Border(
          top: BorderSide(
            color: isDark ? Colors.grey[800]! : Colors.grey[200]!,
          ),
        ),
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '选择要@的人',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    color: isDark ? Colors.white : Colors.grey[800],
                  ),
                ),
                GestureDetector(
                  onTap: _toggleMentionPicker,
                  child: Icon(Icons.close, size: 20, color: Colors.grey[500]),
                ),
              ],
            ),
          ),
          Expanded(
            child: _contacts.isEmpty
                ? Center(
                    child: Text(
                      '暂无联系人',
                      style: TextStyle(color: Colors.grey[500]),
                    ),
                  )
                : ListView.builder(
                    itemCount: _contacts.length,
                    itemBuilder: (context, index) {
                      final contact = _contacts[index];
                      return ListTile(
                        leading: CircleAvatar(
                          backgroundColor: AppTheme.primary,
                          backgroundImage: contact['avatarUrl'] != null
                              ? NetworkImage(contact['avatarUrl'])
                              : null,
                          child: contact['avatarUrl'] == null
                              ? Text(
                                  (contact['nickname'] ?? contact['username'] ?? '?')[0].toUpperCase(),
                                  style: const TextStyle(color: Colors.white),
                                )
                              : null,
                        ),
                        title: Text(contact['nickname'] ?? contact['username'] ?? ''),
                        subtitle: Text('@${contact['username'] ?? ''}'),
                        onTap: () => _insertMention(contact),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomToolbar(bool isDark) {
    final progress = _characterCount / _maxCharacters;
    final isOverLimit = _characterCount > _maxCharacters;

    return ClipRRect(
      borderRadius: BorderRadius.circular(28),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
          decoration: BoxDecoration(
            color: isDark
                ? Colors.grey[900]?.withValues(alpha: 0.75)
                : Colors.white.withValues(alpha: 0.75),
            borderRadius: BorderRadius.circular(28),
            border: Border.all(
              color: isDark
                  ? Colors.grey[800]!
                  : Colors.white.withValues(alpha: 0.3),
            ),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.08),
                blurRadius: 32,
                offset: const Offset(0, 8),
              ),
            ],
          ),
          child: Row(
            children: [
              // 图片按钮
              _buildToolButton(
                Icons.image,
                isDark,
                isActive: _selectedImages.isNotEmpty || _uploadedImageUrls.isNotEmpty,
                badge: _selectedImages.length + _uploadedImageUrls.length > 0
                    ? '${_selectedImages.length + _uploadedImageUrls.length}'
                    : null,
                onTap: _showImageSourceOptions,
              ),
              // 表情按钮
              _buildToolButton(
                Icons.emoji_emotions,
                isDark,
                isActive: _showEmojiPicker,
                onTap: _toggleEmojiPicker,
              ),
              // @提及按钮
              _buildToolButton(
                Icons.alternate_email,
                isDark,
                isActive: _showMentionPicker,
                onTap: _toggleMentionPicker,
              ),
              // 投票按钮
              _buildToolButton(
                Icons.bar_chart,
                isDark,
                isActive: _showPollCreator,
                onTap: _togglePollCreator,
              ),

              const Spacer(),

              // 字数统计和进度
              Padding(
                padding: const EdgeInsets.only(right: 16),
                child: Row(
                  children: [
                    // 字数统计
                    Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(
                          '$_characterCount / $_maxCharacters',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                            color: isOverLimit ? Colors.red : Colors.grey[400],
                          ),
                        ),
                      ],
                    ),

                    const SizedBox(width: 12),

                    // 进度圆环
                    SizedBox(
                      width: 24,
                      height: 24,
                      child: Stack(
                        children: [
                          // 背景圆环
                          Container(
                            width: 24,
                            height: 24,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: isDark ? Colors.grey[800] : Colors.grey[100],
                            ),
                          ),
                          // 进度圆环
                          SizedBox(
                            width: 24,
                            height: 24,
                            child: CircularProgressIndicator(
                              value: progress.clamp(0.0, 1.0),
                              strokeWidth: 2.5,
                              backgroundColor: Colors.transparent,
                              color: isOverLimit
                                  ? Colors.red
                                  : (progress > 0.8
                                      ? Colors.orange
                                      : AppTheme.primary),
                            ),
                          ),
                        ],
                      ),
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

  Widget _buildToolButton(
    IconData icon,
    bool isDark, {
    VoidCallback? onTap,
    bool isActive = false,
    String? badge,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        height: 48,
        alignment: Alignment.center,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Icon(
              icon,
              size: 26,
              color: isActive
                  ? AppTheme.primary
                  : (isDark ? Colors.grey[400] : Colors.grey[700]),
            ),
            if (badge != null)
              Positioned(
                top: -4,
                right: -8,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppTheme.primary,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    badge,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 10,
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
}
