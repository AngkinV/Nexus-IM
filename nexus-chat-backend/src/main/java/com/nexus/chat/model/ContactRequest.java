package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 好友申请模型
 * 用于存储待处理的好友申请
 */
@Entity
@Table(name = "contact_requests", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"from_user_id", "to_user_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发起申请的用户ID
     */
    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    /**
     * 接收申请的用户ID
     */
    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    /**
     * 申请附言/验证消息
     */
    @Column(name = "message", length = 200)
    private String message;

    /**
     * 申请状态: PENDING, ACCEPTED, REJECTED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum RequestStatus {
        PENDING,    // 待处理
        ACCEPTED,   // 已接受
        REJECTED    // 已拒绝
    }
}
