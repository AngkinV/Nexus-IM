import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';

/// 应用内通知横幅组件 - 微信风格
/// 显示在屏幕顶部的简洁消息提醒
class InAppNotificationBanner extends StatefulWidget {
  final String senderName;
  final String? senderAvatar;
  final String messagePreview;
  final int chatId;
  final VoidCallback? onTap;
  final VoidCallback? onDismiss;
  final Duration duration;

  const InAppNotificationBanner({
    super.key,
    required this.senderName,
    this.senderAvatar,
    required this.messagePreview,
    required this.chatId,
    this.onTap,
    this.onDismiss,
    this.duration = const Duration(seconds: 3),
  });

  @override
  State<InAppNotificationBanner> createState() => _InAppNotificationBannerState();
}

class _InAppNotificationBannerState extends State<InAppNotificationBanner>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<Offset> _slideAnimation;
  Timer? _dismissTimer;

  @override
  void initState() {
    super.initState();

    _controller = AnimationController(
      duration: const Duration(milliseconds: 250),
      vsync: this,
    );

    _slideAnimation = Tween<Offset>(
      begin: const Offset(0, -1),
      end: Offset.zero,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Curves.easeOutCubic,
    ));

    // 触发轻微震动反馈
    HapticFeedback.lightImpact();

    // 进入动画
    _controller.forward();

    // 自动消失定时器
    _dismissTimer = Timer(widget.duration, _dismiss);
  }

  @override
  void dispose() {
    _dismissTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _dismiss() {
    _dismissTimer?.cancel();
    _controller.reverse().then((_) {
      widget.onDismiss?.call();
    });
  }

  void _handleTap() {
    HapticFeedback.selectionClick();
    _dismissTimer?.cancel();
    _controller.reverse().then((_) {
      widget.onTap?.call();
    });
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final topPadding = MediaQuery.of(context).padding.top;

    return SlideTransition(
      position: _slideAnimation,
      child: GestureDetector(
        onTap: _handleTap,
        onVerticalDragEnd: (details) {
          // 向上滑动关闭
          if (details.primaryVelocity != null && details.primaryVelocity! < -100) {
            _dismiss();
          }
        },
        child: ClipRRect(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 25, sigmaY: 25),
            child: Container(
              width: double.infinity,
              padding: EdgeInsets.only(
                top: topPadding + 8,
                bottom: 12,
                left: 16,
                right: 16,
              ),
              decoration: BoxDecoration(
                color: isDark
                    ? const Color(0xFF1C1C1E).withValues(alpha: 0.92)
                    : Colors.white.withValues(alpha: 0.92),
                border: Border(
                  bottom: BorderSide(
                    color: isDark
                        ? Colors.white.withValues(alpha: 0.08)
                        : Colors.black.withValues(alpha: 0.06),
                    width: 0.5,
                  ),
                ),
              ),
              child: Row(
                children: [
                  // 头像
                  _buildAvatar(isDark),
                  const SizedBox(width: 12),
                  // 内容
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // 应用名 + 时间
                        Row(
                          children: [
                            Text(
                              'Nexus Chat',
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: isDark ? Colors.grey[500] : Colors.grey[500],
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              '现在',
                              style: TextStyle(
                                fontSize: 12,
                                color: isDark ? Colors.grey[600] : Colors.grey[400],
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 2),
                        // 发送者名称
                        Text(
                          widget.senderName,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: isDark ? Colors.white : Colors.black87,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 1),
                        // 消息预览
                        Text(
                          widget.messagePreview,
                          style: TextStyle(
                            fontSize: 14,
                            color: isDark ? Colors.grey[400] : Colors.grey[600],
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
        ),
      ),
    );
  }

  Widget _buildAvatar(bool isDark) {
    final fullAvatarUrl = ApiConfig.getFullUrl(widget.senderAvatar);

    if (fullAvatarUrl.isNotEmpty) {
      return Container(
        width: 40,
        height: 40,
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
          child: CachedNetworkImage(
            imageUrl: fullAvatarUrl,
            width: 40,
            height: 40,
            fit: BoxFit.cover,
            placeholder: (context, url) => _buildDefaultAvatar(isDark),
            errorWidget: (context, url, error) => _buildDefaultAvatar(isDark),
          ),
        ),
      );
    }
    return _buildDefaultAvatar(isDark);
  }

  Widget _buildDefaultAvatar(bool isDark) {
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            AppTheme.primary,
            AppTheme.primary.withValues(alpha: 0.8),
          ],
        ),
        borderRadius: BorderRadius.circular(10),
        boxShadow: [
          BoxShadow(
            color: AppTheme.primary.withValues(alpha: 0.3),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Center(
        child: Text(
          widget.senderName.isNotEmpty ? widget.senderName[0].toUpperCase() : '?',
          style: const TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}

/// 应用内通知管理器
/// 用于在全局显示通知横幅
class InAppNotificationOverlay {
  static OverlayEntry? _currentEntry;
  static bool _isShowing = false;

  /// 显示通知横幅
  static void show(
    BuildContext context, {
    required String senderName,
    String? senderAvatar,
    required String messagePreview,
    required int chatId,
    VoidCallback? onTap,
  }) {
    // 如果正在显示，先移除
    if (_isShowing) {
      dismiss();
    }

    _isShowing = true;

    _currentEntry = OverlayEntry(
      builder: (context) => Positioned(
        top: 0,
        left: 0,
        right: 0,
        child: Material(
          color: Colors.transparent,
          child: InAppNotificationBanner(
            senderName: senderName,
            senderAvatar: senderAvatar,
            messagePreview: messagePreview,
            chatId: chatId,
            onTap: () {
              dismiss();
              onTap?.call();
            },
            onDismiss: dismiss,
          ),
        ),
      ),
    );

    Overlay.of(context).insert(_currentEntry!);
  }

  /// 移除通知横幅
  static void dismiss() {
    _currentEntry?.remove();
    _currentEntry = null;
    _isShowing = false;
  }
}
