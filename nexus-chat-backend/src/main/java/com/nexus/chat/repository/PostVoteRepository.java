package com.nexus.chat.repository;

import com.nexus.chat.model.PostVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 帖子投票仓库
 */
@Repository
public interface PostVoteRepository extends JpaRepository<PostVote, Long> {

    // 查询用户对帖子的投票
    Optional<PostVote> findByPostIdAndUserId(Long postId, Long userId);

    // 检查用户是否对帖子投票
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 删除用户对帖子的投票
    void deleteByPostIdAndUserId(Long postId, Long userId);

    // 统计帖子点赞数
    long countByPostIdAndVoteType(Long postId, Integer voteType);

    // 删除帖子的所有投票
    void deleteByPostId(Long postId);
}
