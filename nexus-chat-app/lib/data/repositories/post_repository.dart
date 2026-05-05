import '../datasources/remote/post_api_service.dart';
import '../models/post/post_models.dart';

/// 帖子仓库
class PostRepository {
  final PostApiService _apiService = PostApiService();

  // ==================== 帖子 CRUD ====================

  /// 创建帖子
  Future<PostModel> createPost({
    required int authorId,
    String? title,
    required String content,
    List<String> images = const [],
  }) async {
    final request = CreatePostRequest(
      authorId: authorId,
      title: title,
      content: content,
      images: images,
    );
    return await _apiService.createPost(request);
  }

  /// 获取帖子详情
  Future<PostModel> getPost(int postId, {int? userId}) async {
    return await _apiService.getPost(postId, userId: userId);
  }

  /// 删除帖子
  Future<void> deletePost(int postId, int userId) async {
    await _apiService.deletePost(postId, userId);
  }

  // ==================== 帖子列表 ====================

  /// 获取推荐帖子
  Future<PostListResponse> getRecommendedPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.getRecommendedPosts(
      page: page,
      size: size,
      userId: userId,
    );
  }

  /// 获取热门帖子
  Future<PostListResponse> getHotPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.getHotPosts(
      page: page,
      size: size,
      userId: userId,
    );
  }

  /// 获取最新帖子
  Future<PostListResponse> getLatestPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.getLatestPosts(
      page: page,
      size: size,
      userId: userId,
    );
  }

  /// 获取用户的帖子
  Future<PostListResponse> getUserPosts(
    int authorId, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.getUserPosts(
      authorId,
      page: page,
      size: size,
      userId: userId,
    );
  }

  /// 搜索帖子
  Future<PostListResponse> searchPosts(
    String keyword, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.searchPosts(
      keyword,
      page: page,
      size: size,
      userId: userId,
    );
  }

  // ==================== 投票 ====================

  /// 点赞帖子
  Future<PostModel> upvotePost(int postId, int userId) async {
    return await _apiService.upvotePost(postId, userId);
  }

  /// 踩帖子
  Future<PostModel> downvotePost(int postId, int userId) async {
    return await _apiService.downvotePost(postId, userId);
  }

  // ==================== 收藏 ====================

  /// 收藏/取消收藏帖子
  Future<PostModel> toggleBookmark(int postId, int userId) async {
    return await _apiService.toggleBookmark(postId, userId);
  }

  /// 获取用户收藏的帖子
  Future<PostListResponse> getUserBookmarks(
    int userId, {
    int page = 0,
    int size = 20,
  }) async {
    return await _apiService.getUserBookmarks(
      userId,
      page: page,
      size: size,
    );
  }

  // ==================== 评论 ====================

  /// 创建评论
  Future<PostCommentModel> createComment({
    required int postId,
    required int authorId,
    required String content,
    int? parentId,
  }) async {
    final request = CreateCommentRequest(
      postId: postId,
      authorId: authorId,
      content: content,
      parentId: parentId,
    );
    return await _apiService.createComment(request);
  }

  /// 获取帖子的评论
  Future<CommentListResponse> getPostComments(
    int postId, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    return await _apiService.getPostComments(
      postId,
      page: page,
      size: size,
      userId: userId,
    );
  }

  /// 删除评论
  Future<void> deleteComment(int commentId, int userId) async {
    await _apiService.deleteComment(commentId, userId);
  }

  /// 评论点赞（切换）
  Future<PostCommentModel> toggleCommentLike(int commentId, int userId) async {
    return await _apiService.toggleCommentLike(commentId, userId);
  }

  /// 获取评论的回复列表
  Future<List<PostCommentModel>> getCommentReplies(int commentId, {int? userId}) async {
    return await _apiService.getCommentReplies(commentId, userId: userId);
  }
}
