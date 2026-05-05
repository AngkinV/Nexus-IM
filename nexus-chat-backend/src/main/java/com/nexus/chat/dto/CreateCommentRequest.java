package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建评论请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCommentRequest {
    private Long postId;
    private Long authorId;
    private String content;
    private Long parentId;
}
