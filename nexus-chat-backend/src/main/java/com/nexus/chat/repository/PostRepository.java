package com.nexus.chat.repository;

import com.nexus.chat.model.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子仓库
 */
@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // 按作者查询帖子
    Page<Post> findByAuthorIdOrderByCreatedAtDesc(Long authorId, Pageable pageable);

    // 最新帖子
    Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 热门帖子（按综合得分：点赞数 - 踩数 + 评论数*2）
    @Query("SELECT p FROM Post p ORDER BY (p.upvoteCount - p.downvoteCount + p.commentCount * 2) DESC, p.createdAt DESC")
    Page<Post> findHotPosts(Pageable pageable);

    // 推荐帖子（按点赞数和时间综合排序）
    @Query("SELECT p FROM Post p ORDER BY p.upvoteCount DESC, p.createdAt DESC")
    Page<Post> findRecommendedPosts(Pageable pageable);

    // 搜索帖子
    @Query("SELECT p FROM Post p WHERE p.title LIKE %:keyword% OR p.content LIKE %:keyword% ORDER BY p.createdAt DESC")
    Page<Post> searchPosts(@Param("keyword") String keyword, Pageable pageable);

    // 统计用户帖子数
    long countByAuthorId(Long authorId);

    // 查询置顶帖子
    List<Post> findByIsPinnedTrueOrderByCreatedAtDesc();
}
