import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/storage/secure_storage.dart';
import '../../../data/models/post/post_models.dart';
import '../../../data/repositories/post_repository.dart';
import 'create_post_page.dart';
import 'post_detail_page.dart';

/// 社区页面 - 书卷风格
class CommunityPage extends StatefulWidget {
  const CommunityPage({super.key});

  @override
  State<CommunityPage> createState() => _CommunityPageState();
}

class _CommunityPageState extends State<CommunityPage> {
  final PostRepository _postRepository = PostRepository();
  final SecureStorageService _secureStorage = SecureStorageService();

  // 三个标签的数据
  final List<PostModel> _recommendedPosts = [];
  final List<PostModel> _hotPosts = [];
  final List<PostModel> _latestPosts = [];

  // 分页状态
  int _recommendedPage = 0;
  int _hotPage = 0;
  int _latestPage = 0;
  bool _recommendedHasMore = true;
  bool _hotHasMore = true;
  bool _latestHasMore = true;

  // 加载状态
  bool _isLoading = false;
  bool _isLoadingMore = false;
  int? _currentUserId;
  int _currentTabIndex = 0;

  @override
  void initState() {
    super.initState();
    _loadCurrentUser();
  }

  Future<void> _loadCurrentUser() async {
    final userId = await _secureStorage.getUserId();
    setState(() {
      _currentUserId = userId;
    });
    _loadPostsForCurrentTab();
  }

  Future<void> _loadPostsForCurrentTab() async {
    switch (_currentTabIndex) {
      case 0:
        if (_recommendedPosts.isEmpty) _loadRecommendedPosts();
        break;
      case 1:
        if (_hotPosts.isEmpty) _loadHotPosts();
        break;
      case 2:
        if (_latestPosts.isEmpty) _loadLatestPosts();
        break;
    }
  }

  Future<void> _loadRecommendedPosts({bool refresh = false}) async {
    if (_isLoading) return;
    if (refresh) {
      _recommendedPage = 0;
      _recommendedHasMore = true;
    }
    if (!_recommendedHasMore && !refresh) return;

    setState(() => _isLoading = refresh || _recommendedPosts.isEmpty);

    try {
      final response = await _postRepository.getRecommendedPosts(
        page: _recommendedPage,
        userId: _currentUserId,
      );
      setState(() {
        if (refresh) _recommendedPosts.clear();
        _recommendedPosts.addAll(response.content);
        _recommendedPage++;
        _recommendedHasMore = !response.last;
        _isLoading = false;
      });
      _precachePostImages(response.content);
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('加载失败');
    }
  }

  Future<void> _loadHotPosts({bool refresh = false}) async {
    if (_isLoading) return;
    if (refresh) {
      _hotPage = 0;
      _hotHasMore = true;
    }
    if (!_hotHasMore && !refresh) return;

    setState(() => _isLoading = refresh || _hotPosts.isEmpty);

    try {
      final response = await _postRepository.getHotPosts(
        page: _hotPage,
        userId: _currentUserId,
      );
      setState(() {
        if (refresh) _hotPosts.clear();
        _hotPosts.addAll(response.content);
        _hotPage++;
        _hotHasMore = !response.last;
        _isLoading = false;
      });
      _precachePostImages(response.content);
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('加载失败');
    }
  }

  Future<void> _loadLatestPosts({bool refresh = false}) async {
    if (_isLoading) return;
    if (refresh) {
      _latestPage = 0;
      _latestHasMore = true;
    }
    if (!_latestHasMore && !refresh) return;

    setState(() => _isLoading = refresh || _latestPosts.isEmpty);

    try {
      final response = await _postRepository.getLatestPosts(
        page: _latestPage,
        userId: _currentUserId,
      );
      setState(() {
        if (refresh) _latestPosts.clear();
        _latestPosts.addAll(response.content);
        _latestPage++;
        _latestHasMore = !response.last;
        _isLoading = false;
      });
      _precachePostImages(response.content);
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('加载失败');
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore) return;
    setState(() => _isLoadingMore = true);

    try {
      switch (_currentTabIndex) {
        case 0:
          await _loadRecommendedPosts();
          break;
        case 1:
          await _loadHotPosts();
          break;
        case 2:
          await _loadLatestPosts();
          break;
      }
    } finally {
      setState(() => _isLoadingMore = false);
    }
  }

  Future<void> _refresh() async {
    switch (_currentTabIndex) {
      case 0:
        await _loadRecommendedPosts(refresh: true);
        break;
      case 1:
        await _loadHotPosts(refresh: true);
        break;
      case 2:
        await _loadLatestPosts(refresh: true);
        break;
    }
  }

  void _showError(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message)),
      );
    }
  }

  void _precachePostImages(List<PostModel> posts, {int count = 5}) {
    final postsToPreload = posts.take(count);
    for (final post in postsToPreload) {
      for (final imageUrl in post.images) {
        if (imageUrl.isNotEmpty) {
          precacheImage(
            CachedNetworkImageProvider(imageUrl),
            context,
          ).catchError((_) {});
        }
      }
    }
  }

  Future<void> _handleVote(PostModel post, int voteType) async {
    if (_currentUserId == null) {
      _showError('请先登录');
      return;
    }

    try {
      PostModel updatedPost;
      if (voteType == 1) {
        updatedPost = await _postRepository.upvotePost(post.id, _currentUserId!);
      } else {
        updatedPost = await _postRepository.downvotePost(post.id, _currentUserId!);
      }
      _updatePostInLists(updatedPost);
    } catch (e) {
      _showError('操作失败');
    }
  }

  Future<void> _handleBookmark(PostModel post) async {
    if (_currentUserId == null) {
      _showError('请先登录');
      return;
    }

    try {
      final updatedPost = await _postRepository.toggleBookmark(post.id, _currentUserId!);
      _updatePostInLists(updatedPost);
    } catch (e) {
      _showError('操作失败');
    }
  }

  void _updatePostInLists(PostModel updatedPost) {
    setState(() {
      _updatePostInList(_recommendedPosts, updatedPost);
      _updatePostInList(_hotPosts, updatedPost);
      _updatePostInList(_latestPosts, updatedPost);
    });
  }

  void _updatePostInList(List<PostModel> posts, PostModel updatedPost) {
    final index = posts.indexWhere((p) => p.id == updatedPost.id);
    if (index != -1) {
      posts[index] = updatedPost;
    }
  }

  void _removePostFromLists(int postId) {
    setState(() {
      _recommendedPosts.removeWhere((p) => p.id == postId);
      _hotPosts.removeWhere((p) => p.id == postId);
      _latestPosts.removeWhere((p) => p.id == postId);
    });
  }

  /// 显示帖子更多操作菜单
  void _showPostMenu(PostModel post, bool isDark) {
    final isAuthor = post.authorId == _currentUserId;
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (ctx) => Container(
        decoration: BoxDecoration(
          color: surfaceColor,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // 拖拽指示条
              Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(top: 12, bottom: 8),
                decoration: BoxDecoration(
                  color: isDark ? Colors.grey[700] : Colors.grey[300],
                  borderRadius: BorderRadius.circular(2),
                ),
              ),

              // 举报
              ListTile(
                leading: Icon(Icons.flag_outlined, color: mutedColor),
                title: Text('举报', style: TextStyle(
                  fontSize: 15, color: isDark ? Colors.white : const Color(0xFF1F2937),
                )),
                onTap: () {
                  Navigator.pop(ctx);
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('感谢反馈，我们会尽快处理'), duration: Duration(seconds: 2)),
                  );
                },
              ),

              // 删除（仅作者可见）
              if (isAuthor) ...[
                Divider(height: 1, color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey[200]),
                ListTile(
                  leading: const Icon(Icons.delete_outline, color: Colors.red),
                  title: const Text('删除', style: TextStyle(
                    fontSize: 15, color: Colors.red, fontWeight: FontWeight.w500,
                  )),
                  onTap: () {
                    Navigator.pop(ctx);
                    _confirmDelete(post, isDark);
                  },
                ),
              ],

              const SizedBox(height: 8),

              // 取消
              Divider(height: 1, color: isDark ? Colors.white.withValues(alpha: 0.05) : Colors.grey[200]),
              ListTile(
                title: Text(
                  '取消',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 15,
                    color: mutedColor,
                  ),
                ),
                onTap: () => Navigator.pop(ctx),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 确认删除对话框
  void _confirmDelete(PostModel post, bool isDark) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: isDark ? const Color(0xFF2C2C2E) : Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          '确认删除',
          style: TextStyle(
            fontSize: 17,
            fontWeight: FontWeight.w700,
            color: isDark ? Colors.white : const Color(0xFF1F2937),
          ),
        ),
        content: Text(
          '确定要删除这条帖子吗？删除后无法恢复。',
          style: TextStyle(
            fontSize: 14,
            color: isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(
              '取消',
              style: TextStyle(
                color: isDark ? Colors.grey[400] : Colors.grey[600],
              ),
            ),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              _handleDelete(post);
            },
            child: const Text(
              '删除',
              style: TextStyle(color: Colors.red, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  /// 执行删除
  Future<void> _handleDelete(PostModel post) async {
    if (_currentUserId == null) return;
    try {
      await _postRepository.deletePost(post.id, _currentUserId!);
      _removePostFromLists(post.id);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('已删除'), duration: Duration(seconds: 1)),
        );
      }
    } catch (e) {
      _showError('删除失败');
    }
  }

  /// 社区卡片快捷评论 - 弹出输入面板直接评论
  void _showQuickComment(PostModel post) {
    if (_currentUserId == null) {
      _showError('请先登录');
      return;
    }
    final isDark = Theme.of(context).brightness == Brightness.dark;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _QuickCommentSheet(
        isDark: isDark,
        onSend: (text) async {
          try {
            await _postRepository.createComment(
              postId: post.id,
              authorId: _currentUserId!,
              content: text,
            );
            final updated = post.copyWith(commentCount: post.commentCount + 1);
            _updatePostInLists(updated);
          } catch (e) {
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('发送失败')),
              );
            }
          }
        },
      ),
    );
  }

  void _navigateToCreatePost() async {
    final result = await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const CreatePostPage()),
    );
    if (result == true) {
      _refresh();
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
        _updatePostInLists(updatedPost);
      }
    }
  }

  void _onTabChanged(int index) {
    setState(() {
      _currentTabIndex = index;
    });
    _loadPostsForCurrentTab();
  }

  List<PostModel> get _currentPosts {
    switch (_currentTabIndex) {
      case 0:
        return _recommendedPosts;
      case 1:
        return _hotPosts;
      case 2:
        return _latestPosts;
      default:
        return _recommendedPosts;
    }
  }

  bool get _currentHasMore {
    switch (_currentTabIndex) {
      case 0:
        return _recommendedHasMore;
      case 1:
        return _hotHasMore;
      case 2:
        return _latestHasMore;
      default:
        return false;
    }
  }

  // ==================== UI ====================

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bgColor = isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F5F0);

    return Scaffold(
      backgroundColor: bgColor,
      body: Stack(
        children: [
          Column(
            children: [
              _buildHeader(context, isDark),
              Expanded(
                child: _buildPostList(_currentPosts, _currentHasMore, isDark),
              ),
            ],
          ),
          // FAB
          Positioned(
            right: 20,
            bottom: 24,
            child: _buildFAB(isDark),
          ),
        ],
      ),
    );
  }

  /// 头部 - 标题 + 搜索 + 标签导航
  Widget _buildHeader(BuildContext context, bool isDark) {
    final surfaceColor = isDark
        ? const Color(0xFF1C1C1E).withValues(alpha: 0.95)
        : const Color(0xFFF7F5F0).withValues(alpha: 0.95);

    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Container(
          decoration: BoxDecoration(
            color: surfaceColor,
            border: Border(
              bottom: BorderSide(
                color: isDark
                    ? Colors.white.withValues(alpha: 0.05)
                    : Colors.black.withValues(alpha: 0.05),
              ),
            ),
          ),
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
              child: Column(
                children: [
                  // 标题行 + 搜索框
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        '社区',
                        style: TextStyle(
                          fontSize: 30,
                          fontWeight: FontWeight.w900,
                          letterSpacing: -0.5,
                          color: isDark ? Colors.white : const Color(0xFF1F2937),
                        ),
                      ),
                      // 搜索框
                      Container(
                        width: 160,
                        height: 36,
                        decoration: BoxDecoration(
                          color: isDark
                              ? Colors.white.withValues(alpha: 0.1)
                              : Colors.black.withValues(alpha: 0.05),
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: Row(
                          children: [
                            const SizedBox(width: 12),
                            Icon(
                              Icons.search,
                              size: 18,
                              color: isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              '搜索话题...',
                              style: TextStyle(
                                fontSize: 13,
                                color: isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 20),

                  // 标签导航 - 文字风格
                  _buildTabNav(isDark),

                  const SizedBox(height: 12),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 标签导航 - 书卷风格文字导航
  Widget _buildTabNav(bool isDark) {
    final tabs = ['推荐', '热门', '最新'];
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);

    return Row(
      children: List.generate(tabs.length, (index) {
        final isSelected = _currentTabIndex == index;
        return Padding(
          padding: EdgeInsets.only(right: index < tabs.length - 1 ? 32 : 0),
          child: GestureDetector(
            onTap: () => _onTabChanged(index),
            child: Column(
              children: [
                Text(
                  tabs[index],
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                    color: isSelected ? AppTheme.primary : mutedColor,
                  ),
                ),
                const SizedBox(height: 6),
                // 圆点指示器
                AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: 5,
                  height: 5,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: isSelected ? AppTheme.primary : Colors.transparent,
                  ),
                ),
              ],
            ),
          ),
        );
      }),
    );
  }

  /// 帖子列表
  Widget _buildPostList(List<PostModel> posts, bool hasMore, bool isDark) {
    if (_isLoading && posts.isEmpty) {
      return const Center(child: CircularProgressIndicator(color: AppTheme.primary));
    }

    if (posts.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.article_outlined, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '暂无帖子',
              style: TextStyle(color: Colors.grey[600], fontSize: 16),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _refresh,
              child: const Text('点击刷新'),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _refresh,
      color: AppTheme.primary,
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification is ScrollEndNotification &&
              notification.metrics.extentAfter < 200 &&
              hasMore &&
              !_isLoadingMore) {
            _loadMore();
          }
          return false;
        },
        child: ListView.builder(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
          itemCount: posts.length + (hasMore ? 1 : 0),
          itemBuilder: (context, index) {
            if (index == posts.length) {
              return const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator(color: AppTheme.primary)),
              );
            }
            return _buildPostCard(posts[index], isDark);
          },
        ),
      ),
    );
  }

  /// 帖子卡片 - 书卷纸面风格
  Widget _buildPostCard(PostModel post, bool isDark) {
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
      margin: const EdgeInsets.only(bottom: 20),
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
      child: Stack(
        children: [
          // 右上角装饰
          Positioned(
            top: -16,
            right: -16,
            child: Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  colors: [
                    AppTheme.primary.withValues(alpha: 0.06),
                    Colors.transparent,
                  ],
                ),
              ),
            ),
          ),

          // 主内容
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 作者信息
                Row(
                  children: [
                    // 头像 - 40px
                    Container(
                      width: 40,
                      height: 40,
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
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            post.displayName,
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: inkColor,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Row(
                            children: [
                              Text(
                                _formatTime(post.createdAt),
                                style: TextStyle(fontSize: 12, color: mutedColor),
                              ),
                              Container(
                                width: 3,
                                height: 3,
                                margin: const EdgeInsets.symmetric(horizontal: 6),
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: mutedColor.withValues(alpha: 0.5),
                                ),
                              ),
                              Text(
                                '社区频道',
                                style: TextStyle(fontSize: 12, color: mutedColor),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    GestureDetector(
                      onTap: () => _showPostMenu(post, isDark),
                      child: Icon(
                        Icons.more_horiz,
                        color: mutedColor,
                        size: 20,
                      ),
                    ),
                  ],
                ),

                // 标题
                if (post.title != null && post.title!.isNotEmpty) ...[
                  const SizedBox(height: 16),
                  Text(
                    post.title!,
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      height: 1.3,
                      color: inkColor,
                    ),
                  ),
                ],

                // 内容
                const SizedBox(height: 10),
                RichText(
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  text: TextSpan(
                    style: TextStyle(
                      fontSize: 15,
                      height: 1.7,
                      color: inkColor.withValues(alpha: 0.8),
                    ),
                    children: [
                      TextSpan(text: post.content),
                      TextSpan(
                        text: '  阅读更多',
                        style: TextStyle(
                          color: AppTheme.primary,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ),

                // 图片
                if (post.images.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  _buildImages(post.images, isDark),
                ],

                // 操作栏
                const SizedBox(height: 16),
                Container(
                  padding: const EdgeInsets.only(top: 14),
                  decoration: BoxDecoration(
                    border: Border(top: BorderSide(color: dividerColor)),
                  ),
                  child: Row(
                    children: [
                      // 投票按钮组
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
                                    horizontal: 12, vertical: 6),
                                child: Row(
                                  children: [
                                    Icon(
                                      Icons.arrow_upward,
                                      size: 18,
                                      color: post.userVote == 1
                                          ? AppTheme.primary
                                          : mutedColor,
                                    ),
                                    const SizedBox(width: 4),
                                    Text(
                                      _formatCount(post.upvoteCount),
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w500,
                                        color: post.userVote == 1
                                            ? AppTheme.primary
                                            : mutedColor,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                            Container(
                              width: 1,
                              height: 14,
                              color: isDark
                                  ? Colors.white.withValues(alpha: 0.1)
                                  : const Color(0xFFD1D5DB),
                            ),
                            GestureDetector(
                              onTap: () => _handleVote(post, -1),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 12, vertical: 6),
                                child: Icon(
                                  Icons.arrow_downward,
                                  size: 18,
                                  color: post.userVote == -1
                                      ? Colors.red
                                      : mutedColor,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),

                      const SizedBox(width: 16),

                      // 评论
                      GestureDetector(
                        onTap: () => _showQuickComment(post),
                        child: Row(
                          children: [
                            Icon(
                              Icons.chat_bubble_outline,
                              size: 18,
                              color: mutedColor,
                            ),
                            const SizedBox(width: 5),
                            Text(
                              _formatCount(post.commentCount),
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: mutedColor,
                              ),
                            ),
                          ],
                        ),
                      ),

                      const Spacer(),

                      // 分享
                      GestureDetector(
                        onTap: () {},
                        child: Padding(
                          padding: const EdgeInsets.all(6),
                          child: Icon(
                            Icons.share_outlined,
                            size: 18,
                            color: mutedColor,
                          ),
                        ),
                      ),

                      const SizedBox(width: 12),

                      // 收藏
                      GestureDetector(
                        onTap: () => _handleBookmark(post),
                        child: Padding(
                          padding: const EdgeInsets.all(6),
                          child: Icon(
                            post.isBookmarked
                                ? Icons.bookmark
                                : Icons.bookmark_border,
                            size: 18,
                            color: post.isBookmarked
                                ? AppTheme.primary
                                : mutedColor,
                          ),
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
    );
  }

  /// 渐变头像 - 翡翠色渐变底色
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
            fontSize: 16,
          ),
        ),
      ),
    );
  }

  /// 图片区域
  Widget _buildImages(List<String> images, bool isDark) {
    if (images.isEmpty) return const SizedBox.shrink();

    final placeholderColor = isDark ? const Color(0xFF3A3A3C) : const Color(0xFFF3F4F6);

    // 单图 - 圆角卡片
    if (images.length == 1) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: CachedNetworkImage(
          imageUrl: images[0],
          width: double.infinity,
          height: 200,
          fit: BoxFit.cover,
          placeholder: (context, url) => Container(
            height: 200,
            color: placeholderColor,
            child: const Center(
              child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
            ),
          ),
          errorWidget: (context, url, error) => Container(
            height: 200,
            color: placeholderColor,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.image_not_supported,
                  color: isDark ? Colors.grey[600] : Colors.grey[400],
                ),
                const SizedBox(height: 4),
                Text(
                  '图片加载失败',
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark ? Colors.grey[600] : Colors.grey[500],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    // 多图 - 横向滚动
    return SizedBox(
      height: 180,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        itemCount: images.length,
        itemBuilder: (context, index) {
          return Container(
            width: 240,
            margin: EdgeInsets.only(right: index < images.length - 1 ? 12 : 0),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: CachedNetworkImage(
                imageUrl: images[index],
                fit: BoxFit.cover,
                placeholder: (context, url) => Container(
                  color: placeholderColor,
                  child: const Center(
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary),
                  ),
                ),
                errorWidget: (context, url, error) => Container(
                  color: placeholderColor,
                  child: Icon(
                    Icons.image_not_supported,
                    color: isDark ? Colors.grey[600] : Colors.grey[400],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  /// 悬浮按钮 - 圆形
  Widget _buildFAB(bool isDark) {
    return GestureDetector(
      onTap: _navigateToCreatePost,
      child: Container(
        width: 56,
        height: 56,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: AppTheme.primary,
          boxShadow: [
            BoxShadow(
              color: AppTheme.primary.withValues(alpha: 0.3),
              blurRadius: 16,
              offset: const Offset(0, 6),
            ),
          ],
        ),
        child: const Icon(
          Icons.edit,
          size: 24,
          color: Colors.white,
        ),
      ),
    );
  }

  // ==================== 工具方法 ====================

  String _formatTime(DateTime dateTime) {
    final now = DateTime.now();
    final diff = now.difference(dateTime);

    if (diff.inMinutes < 1) {
      return '刚刚';
    } else if (diff.inMinutes < 60) {
      return '${diff.inMinutes}分钟前';
    } else if (diff.inHours < 24) {
      return '${diff.inHours}小时前';
    } else if (diff.inDays < 7) {
      return '${diff.inDays}天前';
    } else {
      return '${dateTime.month}月${dateTime.day}日';
    }
  }

  String _formatCount(int count) {
    if (count >= 10000) {
      return '${(count / 10000).toStringAsFixed(1)}万';
    } else if (count >= 1000) {
      return '${(count / 1000).toStringAsFixed(1)}k';
    }
    return count.toString();
  }
}

/// 快捷评论输入面板
class _QuickCommentSheet extends StatefulWidget {
  final bool isDark;
  final Future<void> Function(String text) onSend;

  const _QuickCommentSheet({
    required this.isDark,
    required this.onSend,
  });

  @override
  State<_QuickCommentSheet> createState() => _QuickCommentSheetState();
}

class _QuickCommentSheetState extends State<_QuickCommentSheet> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  bool _isSending = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focusNode.requestFocus();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final text = _controller.text.trim();
    if (text.isEmpty || _isSending) return;
    setState(() => _isSending = true);
    await widget.onSend(text);
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final surfaceColor = widget.isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final inputBg = widget.isDark ? Colors.black.withValues(alpha: 0.2) : const Color(0xFFF7F5F0);
    final hintColor = widget.isDark ? Colors.grey[600] : Colors.grey[500];
    final textColor = widget.isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = widget.isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Container(
        decoration: BoxDecoration(
          color: surfaceColor,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
        ),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  constraints: const BoxConstraints(maxHeight: 120),
                  decoration: BoxDecoration(
                    color: inputBg,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: TextField(
                    controller: _controller,
                    focusNode: _focusNode,
                    maxLines: 5,
                    minLines: 1,
                    style: TextStyle(fontSize: 15, color: textColor),
                    decoration: InputDecoration(
                      hintText: '写下你的想法...',
                      hintStyle: TextStyle(fontSize: 15, color: hintColor),
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Icon(Icons.image_outlined, size: 22, color: mutedColor),
                    const SizedBox(width: 12),
                    Icon(Icons.emoji_emotions_outlined, size: 22, color: mutedColor),
                    const SizedBox(width: 12),
                    Icon(Icons.alternate_email, size: 22, color: mutedColor),
                    const Spacer(),
                    GestureDetector(
                      onTap: _send,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                        decoration: BoxDecoration(
                          color: AppTheme.primary,
                          borderRadius: BorderRadius.circular(20),
                          boxShadow: [
                            BoxShadow(
                              color: AppTheme.primary.withValues(alpha: 0.3),
                              blurRadius: 8,
                              offset: const Offset(0, 2),
                            ),
                          ],
                        ),
                        child: _isSending
                            ? const SizedBox(
                                width: 16, height: 16,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text('发送',
                                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white)),
                                  SizedBox(width: 4),
                                  Icon(Icons.send, size: 14, color: Colors.white),
                                ],
                              ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
