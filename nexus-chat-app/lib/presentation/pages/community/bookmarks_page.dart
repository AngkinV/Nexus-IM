import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/storage/secure_storage.dart';
import '../../../data/models/post/post_models.dart';
import '../../../data/repositories/post_repository.dart';
import 'post_detail_page.dart';

/// 我的收藏页面
class BookmarksPage extends StatefulWidget {
  const BookmarksPage({super.key});

  @override
  State<BookmarksPage> createState() => _BookmarksPageState();
}

class _BookmarksPageState extends State<BookmarksPage> {
  final PostRepository _postRepository = PostRepository();
  final SecureStorageService _secureStorage = SecureStorageService();

  final List<PostModel> _bookmarks = [];
  int _page = 0;
  bool _hasMore = true;
  bool _isLoading = false;
  bool _isLoadingMore = false;
  int? _currentUserId;

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
  }

  Future<void> _loadCurrentUser() async {
    final userId = await _secureStorage.getUserId();
    setState(() => _currentUserId = userId);
    _loadBookmarks();
  }

  Future<void> _loadBookmarks({bool refresh = false}) async {
    if (_isLoading) return;
    if (refresh) {
      _page = 0;
      _hasMore = true;
    }
    if (!_hasMore && !refresh) return;
    if (_currentUserId == null) return;

    setState(() => _isLoading = refresh || _bookmarks.isEmpty);

    try {
      final response = await _postRepository.getUserBookmarks(
        _currentUserId!,
        page: _page,
      );
      setState(() {
        if (refresh) _bookmarks.clear();
        _bookmarks.addAll(response.content);
        _page++;
        _hasMore = !response.last;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('加载失败')),
        );
      }
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore) return;
    setState(() => _isLoadingMore = true);
    try {
      await _loadBookmarks();
    } finally {
      setState(() => _isLoadingMore = false);
    }
  }

  Future<void> _handleUnbookmark(PostModel post) async {
    if (_currentUserId == null) return;
    try {
      await _postRepository.toggleBookmark(post.id, _currentUserId!);
      setState(() {
        _bookmarks.removeWhere((p) => p.id == post.id);
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('已取消收藏'),
            duration: Duration(seconds: 1),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('操作失败')),
        );
      }
    }
  }

  Future<void> _handleVote(PostModel post, int voteType) async {
    if (_currentUserId == null) return;
    try {
      PostModel updatedPost;
      if (voteType == 1) {
        updatedPost = await _postRepository.upvotePost(post.id, _currentUserId!);
      } else {
        updatedPost = await _postRepository.downvotePost(post.id, _currentUserId!);
      }
      setState(() {
        final index = _bookmarks.indexWhere((p) => p.id == updatedPost.id);
        if (index != -1) _bookmarks[index] = updatedPost;
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('操作失败')),
        );
      }
    }
  }

  void _navigateToDetail(PostModel post) async {
    final result = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(builder: (_) => PostDetailPage(post: post)),
    );
    if (result != null) {
      final updatedPost = result['post'] as PostModel?;
      if (updatedPost != null) {
        setState(() {
          if (!updatedPost.isBookmarked) {
            _bookmarks.removeWhere((p) => p.id == updatedPost.id);
          } else {
            final index = _bookmarks.indexWhere((p) => p.id == updatedPost.id);
            if (index != -1) _bookmarks[index] = updatedPost;
          }
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bgColor = isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F5F0);

    return Scaffold(
      backgroundColor: bgColor,
      appBar: AppBar(
        backgroundColor: bgColor,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: Icon(
            Icons.arrow_back_ios_new,
            size: 20,
            color: isDark ? Colors.white : const Color(0xFF1F2937),
          ),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(
          '我的收藏',
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: isDark ? Colors.white : const Color(0xFF1F2937),
          ),
        ),
        centerTitle: true,
      ),
      body: _buildBody(isDark),
    );
  }

  Widget _buildBody(bool isDark) {
    if (_isLoading && _bookmarks.isEmpty) {
      return const Center(child: CircularProgressIndicator(color: AppTheme.primary));
    }

    if (_bookmarks.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.bookmark_border, size: 72,
                color: isDark ? Colors.grey[600] : Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '暂无收藏内容',
              style: TextStyle(
                fontSize: 16,
                color: isDark ? Colors.grey[500] : Colors.grey[600],
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '在社区中收藏感兴趣的帖子',
              style: TextStyle(
                fontSize: 13,
                color: isDark ? Colors.grey[600] : Colors.grey[500],
              ),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _loadBookmarks(refresh: true),
      color: AppTheme.primary,
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification is ScrollEndNotification &&
              notification.metrics.extentAfter < 200 &&
              _hasMore &&
              !_isLoadingMore) {
            _loadMore();
          }
          return false;
        },
        child: ListView.builder(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
          itemCount: _bookmarks.length + (_hasMore ? 1 : 0),
          itemBuilder: (context, index) {
            if (index == _bookmarks.length) {
              return const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator(color: AppTheme.primary)),
              );
            }
            return _buildBookmarkCard(_bookmarks[index], isDark);
          },
        ),
      ),
    );
  }

  Widget _buildBookmarkCard(PostModel post, bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final borderColor = isDark
        ? Colors.white.withValues(alpha: 0.05)
        : Colors.black.withValues(alpha: 0.05);
    final inkColor = isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final dividerColor = isDark
        ? Colors.white.withValues(alpha: 0.05)
        : const Color(0xFFF3F4F6);
    final chipBg = isDark
        ? Colors.white.withValues(alpha: 0.05)
        : const Color(0xFFF9FAFB);

    final fullAvatarUrl = post.authorAvatarUrl != null
        ? ApiConfig.getFullUrl(post.authorAvatarUrl)
        : '';

    return GestureDetector(
      onTap: () => _navigateToDetail(post),
      child: Container(
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: borderColor),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: isDark ? 0.0 : 0.04),
            blurRadius: 20,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 作者信息
            Row(
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.08),
                        blurRadius: 4,
                        offset: const Offset(0, 2),
                      ),
                    ],
                  ),
                  child: ClipOval(
                    child: fullAvatarUrl.isNotEmpty
                        ? CachedNetworkImage(
                            imageUrl: fullAvatarUrl,
                            fit: BoxFit.cover,
                            placeholder: (context, url) =>
                                _buildGradientAvatar(post.displayName),
                            errorWidget: (context, url, error) =>
                                _buildGradientAvatar(post.displayName),
                          )
                        : _buildGradientAvatar(post.displayName),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        post.displayName,
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: inkColor,
                        ),
                      ),
                      Text(
                        _formatTime(post.createdAt),
                        style: TextStyle(fontSize: 11, color: mutedColor),
                      ),
                    ],
                  ),
                ),
              ],
            ),

            // 标题
            if (post.title != null && post.title!.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(
                post.title!,
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                  height: 1.3,
                  color: inkColor,
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ],

            // 内容
            const SizedBox(height: 8),
            Text(
              post.content,
              style: TextStyle(
                fontSize: 14,
                height: 1.6,
                color: inkColor.withValues(alpha: 0.75),
              ),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),

            // 图片预览
            if (post.images.isNotEmpty) ...[
              const SizedBox(height: 10),
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: CachedNetworkImage(
                  imageUrl: post.images.first,
                  width: double.infinity,
                  height: 140,
                  fit: BoxFit.cover,
                  placeholder: (context, url) => Container(
                    height: 140,
                    color: isDark ? const Color(0xFF3A3A3C) : const Color(0xFFF3F4F6),
                  ),
                  errorWidget: (context, url, error) => Container(
                    height: 140,
                    color: isDark ? const Color(0xFF3A3A3C) : const Color(0xFFF3F4F6),
                    child: Icon(Icons.image_not_supported,
                        color: isDark ? Colors.grey[600] : Colors.grey[400]),
                  ),
                ),
              ),
            ],

            // 操作栏
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.only(top: 12),
              decoration: BoxDecoration(
                border: Border(top: BorderSide(color: dividerColor)),
              ),
              child: Row(
                children: [
                  // 投票
                  Container(
                    decoration: BoxDecoration(
                      color: chipBg,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Row(
                      children: [
                        GestureDetector(
                          onTap: () => _handleVote(post, 1),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 10, vertical: 5),
                            child: Row(
                              children: [
                                Icon(Icons.arrow_upward, size: 16,
                                    color: post.userVote == 1
                                        ? AppTheme.primary : mutedColor),
                                const SizedBox(width: 3),
                                Text(
                                  _formatCount(post.upvoteCount),
                                  style: TextStyle(fontSize: 11,
                                      fontWeight: FontWeight.w500,
                                      color: post.userVote == 1
                                          ? AppTheme.primary : mutedColor),
                                ),
                              ],
                            ),
                          ),
                        ),
                        Container(width: 1, height: 12,
                            color: isDark
                                ? Colors.white.withValues(alpha: 0.1)
                                : const Color(0xFFD1D5DB)),
                        GestureDetector(
                          onTap: () => _handleVote(post, -1),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 10, vertical: 5),
                            child: Icon(Icons.arrow_downward, size: 16,
                                color: post.userVote == -1
                                    ? Colors.red : mutedColor),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  // 评论数
                  Icon(Icons.chat_bubble_outline, size: 16, color: mutedColor),
                  const SizedBox(width: 4),
                  Text(_formatCount(post.commentCount),
                      style: TextStyle(fontSize: 11, color: mutedColor)),

                  const Spacer(),

                  // 取消收藏
                  GestureDetector(
                    onTap: () => _handleUnbookmark(post),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: chipBg,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.bookmark, size: 16, color: AppTheme.primary),
                          const SizedBox(width: 4),
                          Text(
                            '已收藏',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w500,
                              color: AppTheme.primary,
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
      ),
      ),
    );
  }

  Widget _buildGradientAvatar(String name) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topRight,
          end: Alignment.bottomLeft,
          colors: [AppTheme.primary, Color(0xFF059669)],
        ),
      ),
      child: Center(
        child: Text(
          name.isNotEmpty ? name[0].toUpperCase() : '?',
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.w700,
            fontSize: 14,
          ),
        ),
      ),
    );
  }

  String _formatTime(DateTime dateTime) {
    final now = DateTime.now();
    final diff = now.difference(dateTime);
    if (diff.inMinutes < 1) return '刚刚';
    if (diff.inMinutes < 60) return '${diff.inMinutes}分钟前';
    if (diff.inHours < 24) return '${diff.inHours}小时前';
    if (diff.inDays < 7) return '${diff.inDays}天前';
    return '${dateTime.month}月${dateTime.day}日';
  }

  String _formatCount(int count) {
    if (count >= 10000) return '${(count / 10000).toStringAsFixed(1)}万';
    if (count >= 1000) return '${(count / 1000).toStringAsFixed(1)}k';
    return count.toString();
  }
}
