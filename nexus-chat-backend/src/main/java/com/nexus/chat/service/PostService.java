package com.nexus.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.chat.dto.*;
import com.nexus.chat.model.*;
import com.nexus.chat.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 帖子服务
 */
@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostVoteRepository postVoteRepository;

    @Autowired
    private PostCommentRepository postCommentRepository;

    @Autowired
    private PostBookmarkRepository postBookmarkRepository;

    @Autowired
    private CommentLikeRepository commentLikeRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 帖子 CRUD ====================

    /**
     * 创建帖子
     */
    @Transactional
    public PostDTO createPost(CreatePostRequest request) {
        User author = userRepository.findById(request.getAuthorId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Post post = new Post();
        post.setAuthor(author);
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());

        // 将图片列表转为JSON存储
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            try {
                post.setImages(objectMapper.writeValueAsString(request.getImages()));
            } catch (JsonProcessingException e) {
                post.setImages("[]");
            }
        }

        post = postRepository.save(post);
        return convertToDTO(post, null);
    }

    /**
     * 获取帖子详情
     */
    public PostDTO getPost(Long postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        // 增加浏览量
        post.setViewCount(post.getViewCount() + 1);
        postRepository.save(post);

        return convertToDTO(post, currentUserId);
    }

    /**
     * 删除帖子（级联删除关联的投票、收藏、评论）
     */
    @Transactional
    public void deletePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        if (!post.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("无权删除此帖子");
        }

        // 先删除关联数据，避免外键约束冲突
        postVoteRepository.deleteByPostId(postId);
        postBookmarkRepository.deleteByPostId(postId);
        postCommentRepository.deleteByPostId(postId);

        postRepository.delete(post);
    }

    // ==================== 帖子列表 ====================

    /**
     * 获取推荐帖子
     */
    public Page<PostDTO> getRecommendedPosts(int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Post> posts = postRepository.findRecommendedPosts(pageable);
        return posts.map(post -> convertToDTO(post, currentUserId));
    }

    /**
     * 获取热门帖子
     */
    public Page<PostDTO> getHotPosts(int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Post> posts = postRepository.findHotPosts(pageable);
        return posts.map(post -> convertToDTO(post, currentUserId));
    }

    /**
     * 获取最新帖子
     */
    public Page<PostDTO> getLatestPosts(int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Post> posts = postRepository.findAllByOrderByCreatedAtDesc(pageable);
        return posts.map(post -> convertToDTO(post, currentUserId));
    }

    /**
     * 获取用户的帖子
     */
    public Page<PostDTO> getUserPosts(Long userId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Post> posts = postRepository.findByAuthorIdOrderByCreatedAtDesc(userId, pageable);
        return posts.map(post -> convertToDTO(post, currentUserId));
    }

    /**
     * 搜索帖子
     */
    public Page<PostDTO> searchPosts(String keyword, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Post> posts = postRepository.searchPosts(keyword, pageable);
        return posts.map(post -> convertToDTO(post, currentUserId));
    }

    // ==================== 投票 ====================

    /**
     * 投票（点赞/踩）
     */
    @Transactional
    public PostDTO vote(Long postId, Long userId, int voteType) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Optional<PostVote> existingVote = postVoteRepository.findByPostIdAndUserId(postId, userId);

        if (existingVote.isPresent()) {
            PostVote vote = existingVote.get();
            int oldVoteType = vote.getVoteType();

            if (oldVoteType == voteType) {
                // 取消投票
                postVoteRepository.delete(vote);
                if (voteType == 1) {
                    post.setUpvoteCount(post.getUpvoteCount() - 1);
                } else {
                    post.setDownvoteCount(post.getDownvoteCount() - 1);
                }
            } else {
                // 切换投票
                vote.setVoteType(voteType);
                postVoteRepository.save(vote);
                if (voteType == 1) {
                    post.setUpvoteCount(post.getUpvoteCount() + 1);
                    post.setDownvoteCount(post.getDownvoteCount() - 1);
                } else {
                    post.setUpvoteCount(post.getUpvoteCount() - 1);
                    post.setDownvoteCount(post.getDownvoteCount() + 1);
                }
            }
        } else {
            // 新投票
            PostVote vote = new PostVote();
            vote.setPost(post);
            vote.setUser(user);
            vote.setVoteType(voteType);
            postVoteRepository.save(vote);

            if (voteType == 1) {
                post.setUpvoteCount(post.getUpvoteCount() + 1);
            } else {
                post.setDownvoteCount(post.getDownvoteCount() + 1);
            }
        }

        post = postRepository.save(post);
        return convertToDTO(post, userId);
    }

    // ==================== 收藏 ====================

    /**
     * 收藏/取消收藏帖子
     */
    @Transactional
    public PostDTO toggleBookmark(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Optional<PostBookmark> existingBookmark = postBookmarkRepository.findByPostIdAndUserId(postId, userId);

        if (existingBookmark.isPresent()) {
            postBookmarkRepository.delete(existingBookmark.get());
        } else {
            PostBookmark bookmark = new PostBookmark();
            bookmark.setPost(post);
            bookmark.setUser(user);
            postBookmarkRepository.save(bookmark);
        }

        return convertToDTO(post, userId);
    }

    /**
     * 获取用户收藏的帖子
     */
    public Page<PostDTO> getUserBookmarks(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PostBookmark> bookmarks = postBookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return bookmarks.map(bookmark -> convertToDTO(bookmark.getPost(), userId));
    }

    // ==================== 评论 ====================

    /**
     * 创建评论
     */
    @Transactional
    public PostCommentDTO createComment(CreateCommentRequest request) {
        Post post = postRepository.findById(request.getPostId())
                .orElseThrow(() -> new RuntimeException("帖子不存在"));

        User author = userRepository.findById(request.getAuthorId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        PostComment comment = new PostComment();
        comment.setPost(post);
        comment.setAuthor(author);
        comment.setContent(request.getContent());
        comment.setParentId(request.getParentId());

        comment = postCommentRepository.save(comment);

        // 更新帖子评论数
        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        return convertCommentToDTO(comment);
    }

    /**
     * 获取帖子的评论
     */
    public Page<PostCommentDTO> getPostComments(Long postId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PostComment> comments = postCommentRepository.findByPostIdAndParentIdIsNullOrderByCreatedAtDesc(postId, pageable);
        return comments.map(c -> convertCommentToDTO(c, currentUserId));
    }

    /**
     * 删除评论
     */
    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("评论不存在"));

        if (!comment.getAuthor().getId().equals(userId)) {
            throw new RuntimeException("无权删除此评论");
        }

        // 更新帖子评论数
        Post post = comment.getPost();
        post.setCommentCount(post.getCommentCount() - 1);
        postRepository.save(post);

        postCommentRepository.delete(comment);
    }

    /**
     * 评论点赞（切换）
     */
    @Transactional
    public PostCommentDTO toggleCommentLike(Long commentId, Long userId) {
        PostComment comment = postCommentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("评论不存在"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Optional<CommentLike> existingLike = commentLikeRepository.findByCommentIdAndUserId(commentId, userId);

        if (existingLike.isPresent()) {
            // 已点赞 -> 取消点赞
            commentLikeRepository.delete(existingLike.get());
            comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
        } else {
            // 未点赞 -> 点赞
            CommentLike like = new CommentLike();
            like.setComment(comment);
            like.setUser(user);
            commentLikeRepository.save(like);
            comment.setLikeCount(comment.getLikeCount() + 1);
        }

        comment = postCommentRepository.save(comment);
        return convertCommentToDTO(comment, userId);
    }

    /**
     * 获取评论的回复列表
     */
    public List<PostCommentDTO> getCommentReplies(Long commentId, Long currentUserId) {
        List<PostComment> replies = postCommentRepository.findByParentIdOrderByCreatedAtAsc(commentId);
        return replies.stream().map(c -> convertCommentToDTO(c, currentUserId)).toList();
    }

    // ==================== 辅助方法 ====================

    /**
     * 转换帖子为DTO
     */
    private PostDTO convertToDTO(Post post, Long currentUserId) {
        PostDTO dto = new PostDTO();
        dto.setId(post.getId());

        // 作者信息
        User author = post.getAuthor();
        dto.setAuthorId(author.getId());
        dto.setAuthorUsername(author.getUsername());
        dto.setAuthorNickname(author.getNickname());
        dto.setAuthorAvatarUrl(author.getAvatarUrl());

        // 帖子内容
        dto.setTitle(post.getTitle());
        dto.setContent(post.getContent());

        // 解析图片JSON
        if (post.getImages() != null && !post.getImages().isEmpty()) {
            try {
                List<String> images = objectMapper.readValue(post.getImages(), new TypeReference<List<String>>() {});
                dto.setImages(images);
            } catch (JsonProcessingException e) {
                dto.setImages(new ArrayList<>());
            }
        } else {
            dto.setImages(new ArrayList<>());
        }

        // 统计数据
        dto.setUpvoteCount(post.getUpvoteCount());
        dto.setDownvoteCount(post.getDownvoteCount());
        dto.setCommentCount(post.getCommentCount());
        dto.setShareCount(post.getShareCount());
        dto.setViewCount(post.getViewCount());

        // 当前用户交互状态
        if (currentUserId != null) {
            Optional<PostVote> vote = postVoteRepository.findByPostIdAndUserId(post.getId(), currentUserId);
            dto.setUserVote(vote.map(PostVote::getVoteType).orElse(0));
            dto.setIsBookmarked(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), currentUserId));
        } else {
            dto.setUserVote(0);
            dto.setIsBookmarked(false);
        }

        // 状态
        dto.setIsPinned(post.getIsPinned());

        // 时间
        dto.setCreatedAt(post.getCreatedAt());
        dto.setUpdatedAt(post.getUpdatedAt());

        return dto;
    }

    /**
     * 转换评论为DTO
     */
    private PostCommentDTO convertCommentToDTO(PostComment comment) {
        return convertCommentToDTO(comment, null);
    }

    /**
     * 转换评论为DTO（含用户点赞状态）
     */
    private PostCommentDTO convertCommentToDTO(PostComment comment, Long currentUserId) {
        PostCommentDTO dto = new PostCommentDTO();
        dto.setId(comment.getId());
        dto.setPostId(comment.getPost().getId());

        // 作者信息
        User author = comment.getAuthor();
        dto.setAuthorId(author.getId());
        dto.setAuthorUsername(author.getUsername());
        dto.setAuthorNickname(author.getNickname());
        dto.setAuthorAvatarUrl(author.getAvatarUrl());

        // 评论内容
        dto.setContent(comment.getContent());
        dto.setParentId(comment.getParentId());

        // 统计
        dto.setLikeCount(comment.getLikeCount());

        // 当前用户是否点赞
        if (currentUserId != null) {
            dto.setUserLiked(commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), currentUserId));
        } else {
            dto.setUserLiked(false);
        }

        // 时间
        dto.setCreatedAt(comment.getCreatedAt());

        return dto;
    }
}
