package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 帖子评论DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostCommentDTO {
    private Long id;
    private Long postId;

    // 作者信息
    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String authorAvatarUrl;

    // 评论内容
    private String content;
    private Long parentId;

    // 统计
    private Integer likeCount;
    private Boolean userLiked;

    // 时间
    private LocalDateTime createdAt;
}
