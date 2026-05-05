package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建帖子请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostRequest {
    private Long authorId;
    private String title;
    private String content;
    private List<String> images;
}
