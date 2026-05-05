package com.nexus.chat.repository;

import com.nexus.chat.model.PostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 帖子评论仓库
 */
@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    // 获取帖子的顶级评论
    Page<PostComment> findByPostIdAndParentIdIsNullOrderByCreatedAtDesc(Long postId, Pageable pageable);

    // 获取帖子的所有评论
    Page<PostComment> findByPostIdOrderByCreatedAtDesc(Long postId, Pageable pageable);

    // 获取评论的回复
    List<PostComment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    // 统计帖子评论数
    long countByPostId(Long postId);

    // 统计用户评论数
    long countByAuthorId(Long authorId);

    // 删除帖子的所有评论
    void deleteByPostId(Long postId);
}
