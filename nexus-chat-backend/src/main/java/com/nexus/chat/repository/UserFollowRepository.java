package com.nexus.chat.repository;

import com.nexus.chat.model.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户关注关系仓库
 */
@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    // 检查关注关系是否存在
    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    // 查找特定关注关系
    Optional<UserFollow> findByFollowerIdAndFollowingId(Long followerId, Long followingId);

    // 删除关注关系
    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    // 统计用户关注了多少人
    long countByFollowerId(Long followerId);

    // 统计用户有多少粉丝
    long countByFollowingId(Long followingId);

    // 获取用户关注列表（分页）
    Page<UserFollow> findByFollowerIdOrderByCreatedAtDesc(Long followerId, Pageable pageable);

    // 获取用户粉丝列表（分页）
    Page<UserFollow> findByFollowingIdOrderByCreatedAtDesc(Long followingId, Pageable pageable);
}
