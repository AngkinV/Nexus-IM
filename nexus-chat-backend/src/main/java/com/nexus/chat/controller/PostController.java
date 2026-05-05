package com.nexus.chat.controller;

import com.nexus.chat.dto.*;
import com.nexus.chat.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 帖子控制器
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    @Autowired
    private PostService postService;

    // ==================== 帖子 CRUD ====================

    /**
     * 创建帖子
     */
    @PostMapping
    public ResponseEntity<?> createPost(@RequestBody CreatePostRequest request) {
        try {
            PostDTO post = postService.createPost(request);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取帖子详情
     */
    @GetMapping("/{postId}")
    public ResponseEntity<?> getPost(
            @PathVariable Long postId,
            @RequestParam(required = false) Long userId) {
        try {
            PostDTO post = postService.getPost(postId, userId);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 删除帖子
     */
    @DeleteMapping("/{postId}")
    public ResponseEntity<?> deletePost(
            @PathVariable Long postId,
            @RequestParam Long userId) {
        try {
            postService.deletePost(postId, userId);
            return ResponseEntity.ok(successResponse("删除成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    // ==================== 帖子列表 ====================

    /**
     * 获取推荐帖子
     */
    @GetMapping("/recommended")
    public ResponseEntity<?> getRecommendedPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostDTO> posts = postService.getRecommendedPosts(page, size, userId);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取热门帖子
     */
    @GetMapping("/hot")
    public ResponseEntity<?> getHotPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostDTO> posts = postService.getHotPosts(page, size, userId);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取最新帖子
     */
    @GetMapping("/latest")
    public ResponseEntity<?> getLatestPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostDTO> posts = postService.getLatestPosts(page, size, userId);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取用户的帖子
     */
    @GetMapping("/user/{authorId}")
    public ResponseEntity<?> getUserPosts(
            @PathVariable Long authorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostDTO> posts = postService.getUserPosts(authorId, page, size, userId);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 搜索帖子
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchPosts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostDTO> posts = postService.searchPosts(keyword, page, size, userId);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    // ==================== 投票 ====================

    /**
     * 点赞帖子
     */
    @PostMapping("/{postId}/upvote")
    public ResponseEntity<?> upvotePost(
            @PathVariable Long postId,
            @RequestParam Long userId) {
        try {
            PostDTO post = postService.vote(postId, userId, 1);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 踩帖子
     */
    @PostMapping("/{postId}/downvote")
    public ResponseEntity<?> downvotePost(
            @PathVariable Long postId,
            @RequestParam Long userId) {
        try {
            PostDTO post = postService.vote(postId, userId, -1);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    // ==================== 收藏 ====================

    /**
     * 收藏/取消收藏帖子
     */
    @PostMapping("/{postId}/bookmark")
    public ResponseEntity<?> toggleBookmark(
            @PathVariable Long postId,
            @RequestParam Long userId) {
        try {
            PostDTO post = postService.toggleBookmark(postId, userId);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取用户收藏的帖子
     */
    @GetMapping("/bookmarks")
    public ResponseEntity<?> getUserBookmarks(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Page<PostDTO> posts = postService.getUserBookmarks(userId, page, size);
            return ResponseEntity.ok(posts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    // ==================== 评论 ====================

    /**
     * 创建评论
     */
    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> createComment(
            @PathVariable Long postId,
            @RequestBody CreateCommentRequest request) {
        try {
            request.setPostId(postId);
            PostCommentDTO comment = postService.createComment(request);
            return ResponseEntity.ok(comment);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取帖子的评论
     */
    @GetMapping("/{postId}/comments")
    public ResponseEntity<?> getPostComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        try {
            Page<PostCommentDTO> comments = postService.getPostComments(postId, page, size, userId);
            return ResponseEntity.ok(comments);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 删除评论
     */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable Long commentId,
            @RequestParam Long userId) {
        try {
            postService.deleteComment(commentId, userId);
            return ResponseEntity.ok(successResponse("删除成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 评论点赞
     * POST /api/posts/comments/{commentId}/like?userId={userId}
     */
    @PostMapping("/comments/{commentId}/like")
    public ResponseEntity<?> likeComment(
            @PathVariable Long commentId,
            @RequestParam Long userId) {
        try {
            return ResponseEntity.ok(postService.toggleCommentLike(commentId, userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    /**
     * 获取评论的回复列表
     * GET /api/posts/comments/{commentId}/replies?userId={userId}
     */
    @GetMapping("/comments/{commentId}/replies")
    public ResponseEntity<?> getCommentReplies(
            @PathVariable Long commentId,
            @RequestParam(required = false) Long userId) {
        try {
            return ResponseEntity.ok(postService.getCommentReplies(commentId, userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorResponse(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> successResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", message);
        return response;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
