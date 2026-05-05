package com.nexus.chat.repository;

import com.nexus.chat.model.PostBookmark;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 帖子收藏仓库
 */
@Repository
public interface PostBookmarkRepository extends JpaRepository<PostBookmark, Long> {

    // 查询用户是否收藏帖子
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 查询用户收藏
    Optional<PostBookmark> findByPostIdAndUserId(Long postId, Long userId);

    // 删除收藏
    void deleteByPostIdAndUserId(Long postId, Long userId);

    // 获取用户的所有收藏
    Page<PostBookmark> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 统计帖子收藏数
    long countByPostId(Long postId);

    // 删除帖子的所有收藏
    void deleteByPostId(Long postId);
}
