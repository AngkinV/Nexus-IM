/// 帖子数据模型

class PostModel {
  final int id;
  final int authorId;
  final String authorUsername;
  final String? authorNickname;
  final String? authorAvatarUrl;
  final String? title;
  final String content;
  final List<String> images;
  final int upvoteCount;
  final int downvoteCount;
  final int commentCount;
  final int shareCount;
  final int viewCount;
  final int userVote; // 1=点赞, -1=踩, 0=未投票
  final bool isBookmarked;
  final bool isPinned;
  final DateTime createdAt;
  final DateTime? updatedAt;

  PostModel({
    required this.id,
    required this.authorId,
    required this.authorUsername,
    this.authorNickname,
    this.authorAvatarUrl,
    this.title,
    required this.content,
    required this.images,
    required this.upvoteCount,
    required this.downvoteCount,
    required this.commentCount,
    required this.shareCount,
    required this.viewCount,
    required this.userVote,
    required this.isBookmarked,
    required this.isPinned,
    required this.createdAt,
    this.updatedAt,
  });

  factory PostModel.fromJson(Map<String, dynamic> json) {
    return PostModel(
      id: json['id'] ?? 0,
      authorId: json['authorId'] ?? 0,
      authorUsername: json['authorUsername'] ?? '',
      authorNickname: json['authorNickname'],
      authorAvatarUrl: json['authorAvatarUrl'],
      title: json['title'],
      content: json['content'] ?? '',
      images: (json['images'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ??
          [],
      upvoteCount: json['upvoteCount'] ?? 0,
      downvoteCount: json['downvoteCount'] ?? 0,
      commentCount: json['commentCount'] ?? 0,
      shareCount: json['shareCount'] ?? 0,
      viewCount: json['viewCount'] ?? 0,
      userVote: json['userVote'] ?? 0,
      isBookmarked: json['isBookmarked'] ?? false,
      isPinned: json['isPinned'] ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.parse(json['createdAt'])
          : DateTime.now(),
      updatedAt:
          json['updatedAt'] != null ? DateTime.parse(json['updatedAt']) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'authorId': authorId,
      'authorUsername': authorUsername,
      'authorNickname': authorNickname,
      'authorAvatarUrl': authorAvatarUrl,
      'title': title,
      'content': content,
      'images': images,
      'upvoteCount': upvoteCount,
      'downvoteCount': downvoteCount,
      'commentCount': commentCount,
      'shareCount': shareCount,
      'viewCount': viewCount,
      'userVote': userVote,
      'isBookmarked': isBookmarked,
      'isPinned': isPinned,
      'createdAt': createdAt.toIso8601String(),
      'updatedAt': updatedAt?.toIso8601String(),
    };
  }

  PostModel copyWith({
    int? id,
    int? authorId,
    String? authorUsername,
    String? authorNickname,
    String? authorAvatarUrl,
    String? title,
    String? content,
    List<String>? images,
    int? upvoteCount,
    int? downvoteCount,
    int? commentCount,
    int? shareCount,
    int? viewCount,
    int? userVote,
    bool? isBookmarked,
    bool? isPinned,
    DateTime? createdAt,
    DateTime? updatedAt,
  }) {
    return PostModel(
      id: id ?? this.id,
      authorId: authorId ?? this.authorId,
      authorUsername: authorUsername ?? this.authorUsername,
      authorNickname: authorNickname ?? this.authorNickname,
      authorAvatarUrl: authorAvatarUrl ?? this.authorAvatarUrl,
      title: title ?? this.title,
      content: content ?? this.content,
      images: images ?? this.images,
      upvoteCount: upvoteCount ?? this.upvoteCount,
      downvoteCount: downvoteCount ?? this.downvoteCount,
      commentCount: commentCount ?? this.commentCount,
      shareCount: shareCount ?? this.shareCount,
      viewCount: viewCount ?? this.viewCount,
      userVote: userVote ?? this.userVote,
      isBookmarked: isBookmarked ?? this.isBookmarked,
      isPinned: isPinned ?? this.isPinned,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  String get displayName => authorNickname ?? authorUsername;

  int get netVotes => upvoteCount - downvoteCount;
}

class PostCommentModel {
  final int id;
  final int postId;
  final int authorId;
  final String authorUsername;
  final String? authorNickname;
  final String? authorAvatarUrl;
  final String content;
  final int? parentId;
  final int likeCount;
  final bool userLiked;
  final DateTime createdAt;

  PostCommentModel({
    required this.id,
    required this.postId,
    required this.authorId,
    required this.authorUsername,
    this.authorNickname,
    this.authorAvatarUrl,
    required this.content,
    this.parentId,
    required this.likeCount,
    this.userLiked = false,
    required this.createdAt,
  });

  factory PostCommentModel.fromJson(Map<String, dynamic> json) {
    return PostCommentModel(
      id: json['id'] ?? 0,
      postId: json['postId'] ?? 0,
      authorId: json['authorId'] ?? 0,
      authorUsername: json['authorUsername'] ?? '',
      authorNickname: json['authorNickname'],
      authorAvatarUrl: json['authorAvatarUrl'],
      content: json['content'] ?? '',
      parentId: json['parentId'],
      likeCount: json['likeCount'] ?? 0,
      userLiked: json['userLiked'] ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.parse(json['createdAt'])
          : DateTime.now(),
    );
  }

  String get displayName => authorNickname ?? authorUsername;
}

class CreatePostRequest {
  final int authorId;
  final String? title;
  final String content;
  final List<String> images;

  CreatePostRequest({
    required this.authorId,
    this.title,
    required this.content,
    required this.images,
  });

  Map<String, dynamic> toJson() {
    return {
      'authorId': authorId,
      'title': title,
      'content': content,
      'images': images,
    };
  }
}

class CreateCommentRequest {
  final int postId;
  final int authorId;
  final String content;
  final int? parentId;

  CreateCommentRequest({
    required this.postId,
    required this.authorId,
    required this.content,
    this.parentId,
  });

  Map<String, dynamic> toJson() {
    return {
      'postId': postId,
      'authorId': authorId,
      'content': content,
      'parentId': parentId,
    };
  }
}

class PostListResponse {
  final List<PostModel> content;
  final int totalElements;
  final int totalPages;
  final int number;
  final bool last;

  PostListResponse({
    required this.content,
    required this.totalElements,
    required this.totalPages,
    required this.number,
    required this.last,
  });

  factory PostListResponse.fromJson(Map<String, dynamic> json) {
    return PostListResponse(
      content: (json['content'] as List<dynamic>?)
              ?.map((e) => PostModel.fromJson(e as Map<String, dynamic>))
              .toList() ??
          [],
      totalElements: json['totalElements'] ?? 0,
      totalPages: json['totalPages'] ?? 0,
      number: json['number'] ?? 0,
      last: json['last'] ?? true,
    );
  }
}

class CommentListResponse {
  final List<PostCommentModel> content;
  final int totalElements;
  final int totalPages;
  final int number;
  final bool last;

  CommentListResponse({
    required this.content,
    required this.totalElements,
    required this.totalPages,
    required this.number,
    required this.last,
  });

  factory CommentListResponse.fromJson(Map<String, dynamic> json) {
    return CommentListResponse(
      content: (json['content'] as List<dynamic>?)
              ?.map((e) => PostCommentModel.fromJson(e as Map<String, dynamic>))
              .toList() ??
          [],
      totalElements: json['totalElements'] ?? 0,
      totalPages: json['totalPages'] ?? 0,
      number: json['number'] ?? 0,
      last: json['last'] ?? true,
    );
  }
}
