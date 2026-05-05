package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 社区帖子实体
 */
@Entity
@Table(name = "posts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发布者
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    /**
     * 帖子标题
     */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 帖子内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 图片URL列表 (JSON格式存储)
     */
    @Column(columnDefinition = "TEXT")
    private String images;

    /**
     * 点赞数
     */
    @Column(name = "upvote_count")
    private Integer upvoteCount = 0;

    /**
     * 踩数
     */
    @Column(name = "downvote_count")
    private Integer downvoteCount = 0;

    /**
     * 评论数
     */
    @Column(name = "comment_count")
    private Integer commentCount = 0;

    /**
     * 分享数
     */
    @Column(name = "share_count")
    private Integer shareCount = 0;

    /**
     * 浏览数
     */
    @Column(name = "view_count")
    private Integer viewCount = 0;

    /**
     * 是否置顶
     */
    @Column(name = "is_pinned")
    private Boolean isPinned = false;

    /**
     * 是否删除
     */
    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
