import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/config/api_config.dart';
import '../../../core/config/theme_config.dart';
import '../../../core/storage/secure_storage.dart';
import '../../../data/models/post/post_models.dart';
import '../../../data/repositories/post_repository.dart';
import '../../../data/datasources/remote/user_api_service.dart';

/// 帖子详情页
class PostDetailPage extends StatefulWidget {
  final PostModel post;
  final bool autoFocusComment;

  const PostDetailPage({
    super.key,
    required this.post,
    this.autoFocusComment = false,
  });

  @override
  State<PostDetailPage> createState() => _PostDetailPageState();
}

class _PostDetailPageState extends State<PostDetailPage> {
  final PostRepository _postRepository = PostRepository();
  final UserApiService _userApiService = UserApiService();
  final SecureStorageService _secureStorage = SecureStorageService();
  final ScrollController _scrollController = ScrollController();

  late PostModel _post;
  final List<PostCommentModel> _comments = [];
  // 子评论缓存: parentCommentId -> List<PostCommentModel>
  final Map<int, List<PostCommentModel>> _repliesMap = {};
  // 展开状态
  final Set<int> _expandedComments = {};

  int _commentPage = 0;
  bool _commentHasMore = true;
  bool _isLoadingComments = false;
  int? _currentUserId;
  bool _isFollowing = false;
  bool _isFollowLoading = false;
  bool _userLoaded = false;
  bool _followChanged = false;

  @override
  void initState() {
    super.initState();
    _post = widget.post;
    _loadCurrentUser();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadCurrentUser() async {
    final userId = await _secureStorage.getUserId();
    setState(() {
      _currentUserId = userId;
      _userLoaded = true;
    });
    _fetchPostDetail();
    _loadComments();
    _checkFollowStatus();
    if (widget.autoFocusComment) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _showCommentSheet();
      });
    }
  }

  Future<void> _fetchPostDetail() async {
    try {
      final fresh = await _postRepository.getPost(
        _post.id,
        userId: _currentUserId,
      );
      setState(() => _post = fresh);
    } catch (_) {}
  }

  Future<void> _checkFollowStatus() async {
    if (_currentUserId == null || _post.authorId == _currentUserId) return;
    try {
      final following = await _userApiService.isFollowing(
        _post.authorId,
        _currentUserId!,
      );
      if (mounted) setState(() => _isFollowing = following);
    } catch (_) {}
  }

  Future<void> _toggleFollow() async {
    if (_currentUserId == null || _isFollowLoading) return;
    setState(() => _isFollowLoading = true);
    try {
      if (_isFollowing) {
        await _userApiService.unfollowUser(_post.authorId, _currentUserId!);
      } else {
        await _userApiService.followUser(_post.authorId, _currentUserId!);
      }
      setState(() {
        _isFollowing = !_isFollowing;
        _followChanged = true;
      });
    } catch (_) {}
    setState(() => _isFollowLoading = false);
  }

  Future<void> _loadComments({bool refresh = false}) async {
    if (_isLoadingComments) return;
    if (refresh) {
      _commentPage = 0;
      _commentHasMore = true;
    }
    if (!_commentHasMore && !refresh) return;

    setState(() => _isLoadingComments = true);

    try {
      final response = await _postRepository.getPostComments(
        _post.id,
        page: _commentPage,
        userId: _currentUserId,
      );
      setState(() {
        if (refresh) {
          _comments.clear();
          _repliesMap.clear();
          _expandedComments.clear();
        }
        _comments.addAll(response.content);
        _commentPage++;
        _commentHasMore = !response.last;
        _isLoadingComments = false;
      });
    } catch (e) {
      setState(() => _isLoadingComments = false);
    }
  }

  Future<void> _loadReplies(int commentId) async {
    try {
      final replies = await _postRepository.getCommentReplies(commentId, userId: _currentUserId);
      setState(() {
        _repliesMap[commentId] = replies;
        _expandedComments.add(commentId);
      });
    } catch (_) {}
  }

  void _toggleReplies(int commentId) {
    if (_expandedComments.contains(commentId)) {
      setState(() => _expandedComments.remove(commentId));
    } else {
      if (_repliesMap.containsKey(commentId)) {
        setState(() => _expandedComments.add(commentId));
      } else {
        _loadReplies(commentId);
      }
    }
  }

  Future<void> _handleVote(int voteType) async {
    if (_currentUserId == null) return;
    try {
      PostModel updated;
      if (voteType == 1) {
        updated = await _postRepository.upvotePost(_post.id, _currentUserId!);
      } else {
        updated = await _postRepository.downvotePost(_post.id, _currentUserId!);
      }
      setState(() => _post = updated);
    } catch (_) {}
  }

  Future<void> _handleBookmark() async {
    if (_currentUserId == null) return;
    try {
      final updated = await _postRepository.toggleBookmark(_post.id, _currentUserId!);
      setState(() => _post = updated);
    } catch (_) {}
  }

  Future<void> _handleCommentLike(PostCommentModel comment) async {
    if (_currentUserId == null) return;
    try {
      final updated = await _postRepository.toggleCommentLike(comment.id, _currentUserId!);
      setState(() {
        final idx = _comments.indexWhere((c) => c.id == comment.id);
        if (idx != -1) _comments[idx] = updated;
        for (final entry in _repliesMap.entries) {
          final rIdx = entry.value.indexWhere((c) => c.id == comment.id);
          if (rIdx != -1) entry.value[rIdx] = updated;
        }
      });
    } catch (_) {}
  }

  void _showCommentSheet({int? replyToCommentId, String? replyToName}) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _CommentInputSheet(
        isDark: isDark,
        replyToName: replyToName,
        onSend: (text) async {
          if (_currentUserId == null) return;
          try {
            final comment = await _postRepository.createComment(
              postId: _post.id,
              authorId: _currentUserId!,
              content: text,
              parentId: replyToCommentId,
            );
            setState(() {
              _post = _post.copyWith(commentCount: _post.commentCount + 1);
              if (replyToCommentId != null) {
                // 追加到子评论列表
                final replies = _repliesMap[replyToCommentId] ?? [];
                replies.add(comment);
                _repliesMap[replyToCommentId] = replies;
                _expandedComments.add(replyToCommentId);
              } else {
                _comments.insert(0, comment);
              }
            });
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

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final bgColor = isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F5F0);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          Navigator.pop(context, {
            'post': _post,
            'followChanged': _followChanged,
          });
        }
      },
      child: Scaffold(
      backgroundColor: bgColor,
      body: Column(
        children: [
          _buildAppBar(isDark),
          Expanded(
            child: NotificationListener<ScrollNotification>(
              onNotification: (notification) {
                if (notification is ScrollEndNotification &&
                    notification.metrics.extentAfter < 200 &&
                    _commentHasMore &&
                    !_isLoadingComments) {
                  _loadComments();
                }
                return false;
              },
              child: ListView(
                controller: _scrollController,
                padding: EdgeInsets.zero,
                children: [
                  _buildArticle(isDark),
                  _buildCommentHeader(isDark),
                  ..._comments.expand((c) => [
                    _buildCommentItem(c, isDark),
                    ..._buildRepliesSection(c, isDark),
                  ]),
                  if (_isLoadingComments)
                    const Padding(
                      padding: EdgeInsets.all(20),
                      child: Center(child: CircularProgressIndicator(color: AppTheme.primary)),
                    ),
                  if (!_isLoadingComments && !_commentHasMore && _comments.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(0, 16, 0, 32),
                      child: Center(
                        child: Text(
                          '已显示全部评论',
                          style: TextStyle(
                            fontSize: 13,
                            color: isDark ? Colors.grey[600] : Colors.grey[500],
                          ),
                        ),
                      ),
                    ),
                  if (!_isLoadingComments && _comments.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(40),
                      child: Center(
                        child: Column(
                          children: [
                            Icon(Icons.chat_bubble_outline, size: 48,
                                color: isDark ? Colors.grey[700] : Colors.grey[400]),
                            const SizedBox(height: 12),
                            Text('暂无评论，来发表第一条吧',
                                style: TextStyle(fontSize: 14,
                                    color: isDark ? Colors.grey[600] : Colors.grey[500])),
                          ],
                        ),
                      ),
                    ),
                  const SizedBox(height: 80),
                ],
              ),
            ),
          ),
          _buildBottomBar(isDark),
        ],
      ),
      ),
    );
  }

  Widget _buildAppBar(bool isDark) {
    final bgColor = isDark
        ? const Color(0xFF1C1C1E).withValues(alpha: 0.95)
        : const Color(0xFFF7F5F0).withValues(alpha: 0.95);

    return Container(
      decoration: BoxDecoration(
        color: bgColor,
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
        child: SizedBox(
          height: 48,
          child: Row(
            children: [
              IconButton(
                icon: Icon(Icons.arrow_back_ios_new, size: 20,
                    color: isDark ? Colors.white : const Color(0xFF1F2937)),
                onPressed: () => Navigator.pop(context, {
                  'post': _post,
                  'followChanged': _followChanged,
                }),
              ),
              const Spacer(),
              Text(
                '详情',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                  color: isDark ? Colors.white : const Color(0xFF1F2937),
                ),
              ),
              const Spacer(),
              IconButton(
                icon: Icon(Icons.more_horiz, size: 22,
                    color: isDark ? Colors.white : const Color(0xFF1F2937)),
                onPressed: () {},
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildArticle(bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final inkColor = isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);

    final fullAvatarUrl = _post.authorAvatarUrl != null
        ? ApiConfig.getFullUrl(_post.authorAvatarUrl)
        : '';

    return Container(
      color: surfaceColor,
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 作者行
          Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.08),
                      blurRadius: 6,
                      offset: const Offset(0, 2),
                    ),
                  ],
                ),
                child: ClipOval(
                  child: fullAvatarUrl.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: fullAvatarUrl,
                          fit: BoxFit.cover,
                          placeholder: (_, __) => _buildGradientAvatar(_post.displayName),
                          errorWidget: (_, __, ___) => _buildGradientAvatar(_post.displayName),
                        )
                      : _buildGradientAvatar(_post.displayName),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(_post.displayName,
                        style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: inkColor)),
                    const SizedBox(height: 2),
                    Row(
                      children: [
                        Text(_formatTime(_post.createdAt),
                            style: TextStyle(fontSize: 12, color: mutedColor)),
                        Container(
                          width: 3, height: 3,
                          margin: const EdgeInsets.symmetric(horizontal: 6),
                          decoration: BoxDecoration(shape: BoxShape.circle, color: mutedColor.withValues(alpha: 0.5)),
                        ),
                        Text('${_formatCount(_post.viewCount)} 阅读',
                            style: TextStyle(fontSize: 12, color: mutedColor)),
                      ],
                    ),
                  ],
                ),
              ),
              // 关注按钮 - 仅在用户已加载且非自己的帖子时显示
              if (_userLoaded && _post.authorId != _currentUserId)
                GestureDetector(
                  onTap: _toggleFollow,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(20),
                      color: _isFollowing ? null : AppTheme.primary,
                      border: _isFollowing ? Border.all(color: mutedColor.withValues(alpha: 0.4)) : null,
                    ),
                    child: _isFollowLoading
                        ? SizedBox(
                            width: 14, height: 14,
                            child: CircularProgressIndicator(
                              strokeWidth: 1.5,
                              color: _isFollowing ? mutedColor : Colors.white,
                            ),
                          )
                        : Text(
                            _isFollowing ? '已关注' : '关注',
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                              color: _isFollowing ? mutedColor : Colors.white,
                            ),
                          ),
                  ),
                ),
            ],
          ),

          const SizedBox(height: 20),

          // 标题
          if (_post.title != null && _post.title!.isNotEmpty) ...[
            Text(
              _post.title!,
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w700,
                height: 1.3,
                color: inkColor,
              ),
            ),
            const SizedBox(height: 16),
          ],

          // 正文
          Text(
            _post.content,
            style: TextStyle(
              fontSize: 16,
              height: 1.8,
              color: inkColor.withValues(alpha: 0.9),
            ),
          ),

          // 图片
          if (_post.images.isNotEmpty) ...[
            const SizedBox(height: 16),
            ..._post.images.map((url) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: CachedNetworkImage(
                  imageUrl: url,
                  width: double.infinity,
                  fit: BoxFit.cover,
                  placeholder: (_, __) => Container(
                    height: 200,
                    color: isDark ? const Color(0xFF3A3A3C) : const Color(0xFFF3F4F6),
                    child: const Center(child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.primary)),
                  ),
                  errorWidget: (_, __, ___) => Container(
                    height: 200,
                    color: isDark ? const Color(0xFF3A3A3C) : const Color(0xFFF3F4F6),
                    child: Icon(Icons.image_not_supported, color: Colors.grey[400]),
                  ),
                ),
              ),
            )),
          ],
        ],
      ),
    );
  }

  Widget _buildCommentHeader(bool isDark) {
    final bgColor = isDark ? const Color(0xFF1C1C1E) : const Color(0xFFF7F5F0);
    final inkColor = isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);

    return Container(
      color: bgColor,
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 12),
      child: Row(
        children: [
          Text('评论',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: inkColor)),
          const SizedBox(width: 6),
          Text('${_post.commentCount}',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w500, color: mutedColor)),
        ],
      ),
    );
  }

  Widget _buildCommentItem(PostCommentModel comment, bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final inkColor = isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final dividerColor = isDark ? Colors.white.withValues(alpha: 0.05) : const Color(0xFFF3F4F6);

    final fullAvatarUrl = comment.authorAvatarUrl != null
        ? ApiConfig.getFullUrl(comment.authorAvatarUrl)
        : '';

    final isLiked = comment.userLiked;
    final replies = _repliesMap[comment.id];
    final isExpanded = _expandedComments.contains(comment.id);

    return Container(
      color: surfaceColor,
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: const BoxDecoration(shape: BoxShape.circle),
                child: ClipOval(
                  child: fullAvatarUrl.isNotEmpty
                      ? CachedNetworkImage(
                          imageUrl: fullAvatarUrl,
                          fit: BoxFit.cover,
                          placeholder: (_, __) => _buildGradientAvatar(comment.displayName),
                          errorWidget: (_, __, ___) => _buildGradientAvatar(comment.displayName),
                        )
                      : _buildGradientAvatar(comment.displayName),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(comment.displayName,
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: inkColor)),
                        Text(_formatTime(comment.createdAt),
                            style: TextStyle(fontSize: 11, color: mutedColor)),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      comment.content,
                      style: TextStyle(fontSize: 14, height: 1.6, color: inkColor.withValues(alpha: 0.85)),
                    ),
                    const SizedBox(height: 10),
                    // 操作栏
                    Row(
                      children: [
                        GestureDetector(
                          onTap: () => _showCommentSheet(
                            replyToCommentId: comment.id,
                            replyToName: comment.displayName,
                          ),
                          child: Row(
                            children: [
                              Icon(Icons.chat_bubble_outline, size: 16, color: mutedColor),
                              const SizedBox(width: 4),
                              Text('回复', style: TextStyle(fontSize: 12, color: mutedColor)),
                            ],
                          ),
                        ),
                        const SizedBox(width: 20),
                        // 评论点赞
                        GestureDetector(
                          onTap: () => _handleCommentLike(comment),
                          child: Row(
                            children: [
                              Icon(
                                isLiked ? Icons.favorite : Icons.favorite_border,
                                size: 16,
                                color: isLiked ? const Color(0xFFEF4444) : mutedColor,
                              ),
                              const SizedBox(width: 4),
                              Text(
                              _formatCount(comment.likeCount),
                                style: TextStyle(
                                  fontSize: 12,
                                  color: isLiked ? const Color(0xFFEF4444) : mutedColor,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    // 展开/收起 回复
                    if ((replies != null && replies.isNotEmpty) || !isExpanded)
                      _buildReplyToggle(comment.id, replies, isExpanded, isDark),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Divider(height: 1, color: dividerColor),
        ],
      ),
    );
  }

  /// 「展开 N 条回复」/ 「收起回复」按钮
  Widget _buildReplyToggle(int commentId, List<PostCommentModel>? replies, bool isExpanded, bool isDark) {
    // 还没加载过 -> 显示「查看回复」
    // 已加载但收起 -> 显示「展开 N 条回复」
    // 已展开 -> 显示「收起回复」
    if (replies == null && !isExpanded) {
      // 还没加载，不知道有没有回复，先不显示（或总是显示入口）
      return GestureDetector(
        onTap: () => _toggleReplies(commentId),
        child: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Row(
            children: [
              Container(width: 16, height: 1, color: AppTheme.primary.withValues(alpha: 0.3)),
              const SizedBox(width: 6),
              Text('查看回复', style: TextStyle(fontSize: 12, color: AppTheme.primary)),
            ],
          ),
        ),
      );
    }
    if (replies != null && replies.isEmpty && !isExpanded) {
      return const SizedBox.shrink();
    }
    if (isExpanded) {
      return GestureDetector(
        onTap: () => _toggleReplies(commentId),
        child: Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Row(
            children: [
              Container(width: 16, height: 1, color: AppTheme.primary.withValues(alpha: 0.3)),
              const SizedBox(width: 6),
              Text('收起回复', style: TextStyle(fontSize: 12, color: AppTheme.primary)),
            ],
          ),
        ),
      );
    }
    return GestureDetector(
      onTap: () => _toggleReplies(commentId),
      child: Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Row(
          children: [
            Container(width: 16, height: 1, color: AppTheme.primary.withValues(alpha: 0.3)),
            const SizedBox(width: 6),
            Text('展开 ${replies!.length} 条回复',
                style: TextStyle(fontSize: 12, color: AppTheme.primary)),
          ],
        ),
      ),
    );
  }

  /// 子评论区域
  List<Widget> _buildRepliesSection(PostCommentModel parent, bool isDark) {
    if (!_expandedComments.contains(parent.id)) return [];
    final replies = _repliesMap[parent.id];
    if (replies == null || replies.isEmpty) return [];

    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final replyBg = isDark ? Colors.white.withValues(alpha: 0.03) : const Color(0xFFF9FAFB);

    return [
      Container(
        color: surfaceColor,
        padding: const EdgeInsets.only(left: 72, right: 24),
        child: Container(
          decoration: BoxDecoration(
            color: replyBg,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            children: replies.map((reply) => _buildReplyItem(reply, parent.id, isDark)).toList(),
          ),
        ),
      ),
    ];
  }

  Widget _buildReplyItem(PostCommentModel reply, int parentId, bool isDark) {
    final inkColor = isDark ? Colors.white : const Color(0xFF1F2937);
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);

    final fullAvatarUrl = reply.authorAvatarUrl != null
        ? ApiConfig.getFullUrl(reply.authorAvatarUrl)
        : '';

    final isLiked = reply.userLiked;

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: const BoxDecoration(shape: BoxShape.circle),
            child: ClipOval(
              child: fullAvatarUrl.isNotEmpty
                  ? CachedNetworkImage(
                      imageUrl: fullAvatarUrl,
                      fit: BoxFit.cover,
                      placeholder: (_, __) => _buildGradientAvatar(reply.displayName),
                      errorWidget: (_, __, ___) => _buildGradientAvatar(reply.displayName),
                    )
                  : _buildGradientAvatar(reply.displayName),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(reply.displayName,
                        style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: inkColor)),
                    Text(_formatTime(reply.createdAt),
                        style: TextStyle(fontSize: 10, color: mutedColor)),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  reply.content,
                  style: TextStyle(fontSize: 13, height: 1.5, color: inkColor.withValues(alpha: 0.85)),
                ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    GestureDetector(
                      onTap: () => _showCommentSheet(
                        replyToCommentId: parentId,
                        replyToName: reply.displayName,
                      ),
                      child: Text('回复', style: TextStyle(fontSize: 11, color: mutedColor)),
                    ),
                    const SizedBox(width: 16),
                    GestureDetector(
                      onTap: () => _handleCommentLike(reply),
                      child: Row(
                        children: [
                          Icon(
                            isLiked ? Icons.favorite : Icons.favorite_border,
                            size: 14,
                            color: isLiked ? const Color(0xFFEF4444) : mutedColor,
                          ),
                          if (reply.likeCount > 0) ...[
                            const SizedBox(width: 3),
                            Text(
                              _formatCount(reply.likeCount),
                              style: TextStyle(fontSize: 11, color: isLiked ? const Color(0xFFEF4444) : mutedColor),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomBar(bool isDark) {
    final surfaceColor = isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final mutedColor = isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    final inputBg = isDark ? Colors.white.withValues(alpha: 0.08) : const Color(0xFFF3F4F6);

    return Container(
      decoration: BoxDecoration(
        color: surfaceColor,
        border: Border(
          top: BorderSide(
            color: isDark
                ? Colors.white.withValues(alpha: 0.05)
                : Colors.black.withValues(alpha: 0.05),
          ),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
          child: Row(
            children: [
              Expanded(
                child: GestureDetector(
                  onTap: () => _showCommentSheet(),
                  child: Container(
                    height: 36,
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    decoration: BoxDecoration(
                      color: inputBg,
                      borderRadius: BorderRadius.circular(18),
                    ),
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '写评论...',
                      style: TextStyle(
                        fontSize: 14,
                        color: isDark ? Colors.grey[600] : Colors.grey[500],
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              _buildBottomAction(
                icon: Icons.chat_bubble_outline,
                count: _post.commentCount,
                isActive: false,
                activeColor: mutedColor,
                mutedColor: mutedColor,
                onTap: () => _showCommentSheet(),
              ),
              const SizedBox(width: 16),
              _buildBottomAction(
                icon: _post.userVote == 1 ? Icons.thumb_up : Icons.thumb_up_outlined,
                count: _post.upvoteCount,
                isActive: _post.userVote == 1,
                activeColor: AppTheme.primary,
                mutedColor: mutedColor,
                onTap: () => _handleVote(1),
              ),
              const SizedBox(width: 16),
              _buildBottomAction(
                icon: _post.isBookmarked ? Icons.bookmark : Icons.bookmark_border,
                count: null,
                isActive: _post.isBookmarked,
                activeColor: AppTheme.primary,
                mutedColor: mutedColor,
                onTap: _handleBookmark,
              ),
              const SizedBox(width: 16),
              GestureDetector(
                onTap: () {},
                child: Icon(Icons.share_outlined, size: 22, color: mutedColor),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBottomAction({
    required IconData icon,
    required int? count,
    required bool isActive,
    required Color activeColor,
    required Color mutedColor,
    required VoidCallback onTap,
  }) {
    final color = isActive ? activeColor : mutedColor;
    return GestureDetector(
      onTap: onTap,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 22, color: color),
          if (count != null && count > 0) ...[
            const SizedBox(width: 3),
            Text(
              _formatCount(count),
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: color),
            ),
          ],
        ],
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
          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 14),
        ),
      ),
    );
  }

  String _formatTime(DateTime dateTime) {
    final diff = DateTime.now().difference(dateTime);
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

/// 评论输入面板 - BottomSheet
class _CommentInputSheet extends StatefulWidget {
  final bool isDark;
  final String? replyToName;
  final Future<void> Function(String text) onSend;

  const _CommentInputSheet({
    required this.isDark,
    this.replyToName,
    required this.onSend,
  });

  @override
  State<_CommentInputSheet> createState() => _CommentInputSheetState();
}

class _CommentInputSheetState extends State<_CommentInputSheet> {
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
    if (mounted) {
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    final surfaceColor = widget.isDark ? const Color(0xFF2C2C2E) : Colors.white;
    final inputBg = widget.isDark ? Colors.black.withValues(alpha: 0.2) : const Color(0xFFF7F5F0);
    final hintColor = widget.isDark ? Colors.grey[600] : Colors.grey[500];
    final textColor = widget.isDark ? Colors.white : const Color(0xFF1F2937);

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
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
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (widget.replyToName != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      '回复 ${widget.replyToName}',
                      style: TextStyle(fontSize: 13, color: AppTheme.primary),
                    ),
                  ),
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
                      hintText: widget.replyToName != null
                          ? '回复 ${widget.replyToName}...'
                          : '写下你的想法...',
                      hintStyle: TextStyle(fontSize: 15, color: hintColor),
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    _buildTool(Icons.image_outlined),
                    const SizedBox(width: 12),
                    _buildTool(Icons.emoji_emotions_outlined),
                    const SizedBox(width: 12),
                    _buildTool(Icons.alternate_email),
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
                                child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Colors.white,
                                ),
                              )
                            : const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text('发送',
                                      style: TextStyle(
                                          fontSize: 13,
                                          fontWeight: FontWeight.w600,
                                          color: Colors.white)),
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

  Widget _buildTool(IconData icon) {
    final mutedColor = widget.isDark ? const Color(0xFF9CA3AF) : const Color(0xFF6B7280);
    return GestureDetector(
      child: Icon(icon, size: 22, color: mutedColor),
    );
  }
}
