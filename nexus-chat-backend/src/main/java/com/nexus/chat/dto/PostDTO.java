package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostDTO {
    private Long id;

    // 作者信息
    private Long authorId;
    private String authorUsername;
    private String authorNickname;
    private String authorAvatarUrl;

    // 帖子内容
    private String title;
    private String content;
    private List<String> images;

    // 统计数据
    private Integer upvoteCount;
    private Integer downvoteCount;
    private Integer commentCount;
    private Integer shareCount;
    private Integer viewCount;

    // 当前用户交互状态
    private Integer userVote; // 1=点赞, -1=踩, 0=未投票
    private Boolean isBookmarked;

    // 状态
    private Boolean isPinned;

    // 时间
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
