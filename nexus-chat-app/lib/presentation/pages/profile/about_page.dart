import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'package:open_filex/open_filex.dart';
import '../../../core/config/theme_config.dart';
import '../../../data/datasources/remote/user_api_service.dart';

/// 关于 Nexus 页面
class AboutPage extends StatefulWidget {
  const AboutPage({super.key});

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  final UserApiService _apiService = UserApiService();

  String _versionName = '';
  int _versionCode = 0;
  bool _isChecking = false;

  @override
  void initState() {
    super.initState();
    _loadVersionInfo();
  }

  Future<void> _loadVersionInfo() async {
    final info = await PackageInfo.fromPlatform();
    setState(() {
      _versionName = info.version;
      _versionCode = int.tryParse(info.buildNumber) ?? 0;
    });
  }

  Future<void> _checkUpdate() async {
    if (_isChecking) return;
    setState(() => _isChecking = true);

    try {
      final update = await _apiService.checkUpdate(_versionCode);
      if (!mounted) return;

      if (!update.hasUpdate) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('当前已是最新版本'),
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            backgroundColor: AppTheme.primary,
          ),
        );
      } else {
        _showUpdateSheet(update);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('检查更新失败，请稍后重试'),
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isChecking = false);
    }
  }

  void _showUpdateSheet(AppUpdateModel update) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    showModalBottomSheet(
      context: context,
      isDismissible: !update.forceUpdate,
      enableDrag: !update.forceUpdate,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _UpdateSheet(
        update: update,
        isDark: isDark,
        forceUpdate: update.forceUpdate,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bgColor = isDark ? const Color(0xFF0A0A0A) : const Color(0xFFF7F7F7);
    final surfaceColor = isDark ? const Color(0xFF18181B) : Colors.white;
    final textColor = isDark ? Colors.white : const Color(0xFF18181B);
    final mutedColor = isDark ? const Color(0xFF71717A) : const Color(0xFFA1A1AA);

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      child: Scaffold(
        backgroundColor: bgColor,
        appBar: AppBar(
          backgroundColor: isDark ? const Color(0xFF18181B) : Colors.white,
          elevation: 0,
          scrolledUnderElevation: 0,
          leading: IconButton(
            onPressed: () => Navigator.pop(context),
            icon: Icon(Icons.arrow_back_ios, size: 20,
                color: isDark ? Colors.white : Colors.black),
          ),
          title: Text(
            '关于 Nexus',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              color: isDark ? Colors.white : Colors.black,
            ),
          ),
          centerTitle: true,
        ),
        body: Column(
          children: [
            Expanded(
              child: ListView(
                padding: EdgeInsets.zero,
                children: [
                  const SizedBox(height: 48),

                  // Logo + 版本
                  Center(
                    child: Column(
                      children: [
                        Container(
                          width: 80,
                          height: 80,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(20),
                            boxShadow: [
                              BoxShadow(
                                color: AppTheme.primary.withValues(alpha: 0.3),
                                blurRadius: 24,
                                offset: const Offset(0, 8),
                              ),
                            ],
                          ),
                          clipBehavior: Clip.antiAlias,
                          child: Image.asset(
                            'assets/icon/app_icon.jpg',
                            fit: BoxFit.cover,
                          ),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Nexus Chat',
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            color: textColor,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Version $_versionName',
                          style: TextStyle(
                            fontSize: 14,
                            color: mutedColor,
                          ),
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 40),

                  // 检查更新按钮
                  Container(
                    color: surfaceColor,
                    child: InkWell(
                      onTap: _isChecking ? null : _checkUpdate,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                '检查更新',
                                style: TextStyle(
                                  fontSize: 17,
                                  color: textColor,
                                ),
                              ),
                            ),
                            if (_isChecking)
                              const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: AppTheme.primary,
                                ),
                              )
                            else
                              Icon(Icons.chevron_right, size: 22,
                                  color: isDark ? Colors.grey[600] : Colors.grey[300]),
                          ],
                        ),
                      ),
                    ),
                  ),

                  const SizedBox(height: 8),

                  // 功能协议等
                  Container(
                    color: surfaceColor,
                    child: Column(
                      children: [
                        _buildRow('功能介绍', isDark, textColor, mutedColor),
                        Divider(height: 1, indent: 16,
                            color: isDark ? const Color(0xFF27272A) : const Color(0xFFF4F4F5)),
                        _buildRow('隐私政策', isDark, textColor, mutedColor),
                        Divider(height: 1, indent: 16,
                            color: isDark ? const Color(0xFF27272A) : const Color(0xFFF4F4F5)),
                        _buildRow('用户协议', isDark, textColor, mutedColor),
                      ],
                    ),
                  ),
                ],
              ),
            ),

            // 底部版权
            Padding(
              padding: const EdgeInsets.only(bottom: 32),
              child: Text(
                'Copyright \u00a9 2025 Nexus. All rights reserved.',
                style: TextStyle(fontSize: 12, color: mutedColor),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildRow(String title, bool isDark, Color textColor, Color mutedColor) {
    return InkWell(
      onTap: () {},
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: TextStyle(fontSize: 17, color: textColor),
              ),
            ),
            Icon(Icons.chevron_right, size: 22,
                color: isDark ? Colors.grey[600] : Colors.grey[300]),
          ],
        ),
      ),
    );
  }
}

// ============================================================
// 更新弹窗
// ============================================================
class _UpdateSheet extends StatefulWidget {
  final AppUpdateModel update;
  final bool isDark;
  final bool forceUpdate;

  const _UpdateSheet({
    required this.update,
    required this.isDark,
    required this.forceUpdate,
  });

  @override
  State<_UpdateSheet> createState() => _UpdateSheetState();
}

class _UpdateSheetState extends State<_UpdateSheet> {
  double _progress = 0;
  bool _isDownloading = false;
  bool _downloadComplete = false;
  String? _filePath;

  Future<void> _startDownload() async {
    if (_isDownloading) return;
    setState(() => _isDownloading = true);

    try {
      final dir = await getTemporaryDirectory();
      final fileName = 'nexus-chat-${widget.update.versionName}.apk';
      final savePath = '${dir.path}/$fileName';

      // 如果文件已存在，先删除
      final file = File(savePath);
      if (await file.exists()) await file.delete();

      // 使用独立的 Dio 实例下载，避免 baseUrl 拼接和 JSON header 干扰
      final downloadDio = Dio(BaseOptions(
        connectTimeout: const Duration(seconds: 60),
        receiveTimeout: const Duration(minutes: 10),
      ));
      await downloadDio.download(
        widget.update.downloadUrl!,
        savePath,
        onReceiveProgress: (received, total) {
          if (total > 0 && mounted) {
            setState(() => _progress = received / total);
          }
        },
      );

      if (mounted) {
        setState(() {
          _downloadComplete = true;
          _filePath = savePath;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isDownloading = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('下载失败，请稍后重试'),
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
        );
      }
    }
  }

  Future<void> _installApk() async {
    if (_filePath == null) return;
    await OpenFilex.open(_filePath!);
  }

  @override
  Widget build(BuildContext context) {
    final bgColor = widget.isDark ? const Color(0xFF1E1E1E) : Colors.white;
    final textColor = widget.isDark ? Colors.white : const Color(0xFF18181B);
    final mutedColor = widget.isDark ? const Color(0xFF71717A) : const Color(0xFFA1A1AA);

    return PopScope(
      canPop: !widget.forceUpdate,
      child: Container(
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 32),
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(24),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 顶部装饰条
            Container(
              height: 100,
              width: double.infinity,
              decoration: BoxDecoration(
                borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppTheme.primary, AppTheme.primaryDark],
                ),
              ),
              child: Stack(
                children: [
                  Positioned(
                    right: 20,
                    top: 10,
                    child: Icon(Icons.system_update_alt,
                        size: 70, color: Colors.white.withValues(alpha: 0.15)),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          '发现新版本',
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'v${widget.update.versionName}  ${widget.update.fileSizeDisplay}',
                          style: TextStyle(
                            fontSize: 14,
                            color: Colors.white.withValues(alpha: 0.8),
                          ),
                        ),
                      ],
                    ),
                  ),
                  // 关闭按钮（非强制更新时显示）
                  if (!widget.forceUpdate)
                    Positioned(
                      right: 8,
                      top: 8,
                      child: IconButton(
                        icon: Icon(Icons.close, color: Colors.white.withValues(alpha: 0.7), size: 22),
                        onPressed: () => Navigator.pop(context),
                      ),
                    ),
                ],
              ),
            ),

            // 更新日志
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 20, 24, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '更新内容',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: textColor,
                  ),
                ),
              ),
            ),
            Container(
              width: double.infinity,
              constraints: const BoxConstraints(maxHeight: 160),
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: SingleChildScrollView(
                child: Text(
                  widget.update.updateLog ?? '- 性能优化与问题修复',
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.6,
                    color: mutedColor,
                  ),
                ),
              ),
            ),

            const SizedBox(height: 20),

            // 进度条（下载中显示）
            if (_isDownloading && !_downloadComplete)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Column(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: _progress,
                        backgroundColor: widget.isDark
                            ? Colors.white.withValues(alpha: 0.1)
                            : const Color(0xFFE5E7EB),
                        valueColor: const AlwaysStoppedAnimation(AppTheme.primary),
                        minHeight: 6,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${(_progress * 100).toStringAsFixed(0)}%',
                      style: TextStyle(fontSize: 13, color: mutedColor),
                    ),
                  ],
                ),
              ),

            // 按钮
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  onPressed: _isDownloading && !_downloadComplete
                      ? null
                      : (_downloadComplete ? _installApk : _startDownload),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppTheme.primary,
                    disabledBackgroundColor: AppTheme.primary.withValues(alpha: 0.5),
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                    elevation: 0,
                  ),
                  child: Text(
                    _downloadComplete
                        ? '立即安装'
                        : (_isDownloading ? '下载中...' : '立即更新'),
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
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
