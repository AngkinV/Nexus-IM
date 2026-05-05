import '../../../core/network/dio_client.dart';
import '../../models/post/post_models.dart';

/// 帖子 API 服务
class PostApiService {
  final DioClient _dioClient = DioClient();

  // ==================== 帖子 CRUD ====================

  /// 创建帖子
  Future<PostModel> createPost(CreatePostRequest request) async {
    final response = await _dioClient.post(
      '/api/posts',
      data: request.toJson(),
    );
    return PostModel.fromJson(response.data);
  }

  /// 获取帖子详情
  Future<PostModel> getPost(int postId, {int? userId}) async {
    final response = await _dioClient.get(
      '/api/posts/$postId',
      queryParameters: userId != null ? {'userId': userId} : null,
    );
    return PostModel.fromJson(response.data);
  }

  /// 删除帖子
  Future<void> deletePost(int postId, int userId) async {
    await _dioClient.delete(
      '/api/posts/$postId',
      queryParameters: {'userId': userId},
    );
  }

  // ==================== 帖子列表 ====================

  /// 获取推荐帖子
  Future<PostListResponse> getRecommendedPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/recommended',
      queryParameters: {
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  /// 获取热门帖子
  Future<PostListResponse> getHotPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/hot',
      queryParameters: {
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  /// 获取最新帖子
  Future<PostListResponse> getLatestPosts({
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/latest',
      queryParameters: {
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  /// 获取用户的帖子
  Future<PostListResponse> getUserPosts(
    int authorId, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/user/$authorId',
      queryParameters: {
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  /// 搜索帖子
  Future<PostListResponse> searchPosts(
    String keyword, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/search',
      queryParameters: {
        'keyword': keyword,
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  // ==================== 投票 ====================

  /// 点赞帖子
  Future<PostModel> upvotePost(int postId, int userId) async {
    final response = await _dioClient.post(
      '/api/posts/$postId/upvote',
      queryParameters: {'userId': userId},
    );
    return PostModel.fromJson(response.data);
  }

  /// 踩帖子
  Future<PostModel> downvotePost(int postId, int userId) async {
    final response = await _dioClient.post(
      '/api/posts/$postId/downvote',
      queryParameters: {'userId': userId},
    );
    return PostModel.fromJson(response.data);
  }

  // ==================== 收藏 ====================

  /// 收藏/取消收藏帖子
  Future<PostModel> toggleBookmark(int postId, int userId) async {
    final response = await _dioClient.post(
      '/api/posts/$postId/bookmark',
      queryParameters: {'userId': userId},
    );
    return PostModel.fromJson(response.data);
  }

  /// 获取用户收藏的帖子
  Future<PostListResponse> getUserBookmarks(
    int userId, {
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/bookmarks',
      queryParameters: {
        'userId': userId,
        'page': page,
        'size': size,
      },
    );
    return PostListResponse.fromJson(response.data);
  }

  // ==================== 评论 ====================

  /// 创建评论
  Future<PostCommentModel> createComment(CreateCommentRequest request) async {
    final response = await _dioClient.post(
      '/api/posts/${request.postId}/comments',
      data: request.toJson(),
    );
    return PostCommentModel.fromJson(response.data);
  }

  /// 获取帖子的评论
  Future<CommentListResponse> getPostComments(
    int postId, {
    int page = 0,
    int size = 20,
    int? userId,
  }) async {
    final response = await _dioClient.get(
      '/api/posts/$postId/comments',
      queryParameters: {
        'page': page,
        'size': size,
        if (userId != null) 'userId': userId,
      },
    );
    return CommentListResponse.fromJson(response.data);
  }

  /// 删除评论
  Future<void> deleteComment(int commentId, int userId) async {
    await _dioClient.delete(
      '/api/posts/comments/$commentId',
      queryParameters: {'userId': userId},
    );
  }

  /// 评论点赞（切换）
  Future<PostCommentModel> toggleCommentLike(int commentId, int userId) async {
    final response = await _dioClient.post(
      '/api/posts/comments/$commentId/like',
      queryParameters: {'userId': userId},
    );
    return PostCommentModel.fromJson(response.data);
  }

  /// 获取评论的回复列表
  Future<List<PostCommentModel>> getCommentReplies(int commentId, {int? userId}) async {
    final response = await _dioClient.get(
      '/api/posts/comments/$commentId/replies',
      queryParameters: {
        if (userId != null) 'userId': userId,
      },
    );
    return (response.data as List)
        .map((json) => PostCommentModel.fromJson(json))
        .toList();
  }
}
