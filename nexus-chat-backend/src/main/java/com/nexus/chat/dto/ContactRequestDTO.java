package com.nexus.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 好友申请DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactRequestDTO {

    private Long id;

    /**
     * 发起申请的用户ID
     */
    private Long fromUserId;

    /**
     * 发起申请的用户信息
     */
    private String fromUsername;
    private String fromNickname;
    private String fromAvatarUrl;
    private Boolean fromIsOnline;

    /**
     * 接收申请的用户ID
     */
    private Long toUserId;

    /**
     * 接收申请的用户信息
     */
    private String toUsername;
    private String toNickname;
    private String toAvatarUrl;

    /**
     * 申请附言
     */
    private String message;

    /**
     * 申请状态: PENDING, ACCEPTED, REJECTED
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
