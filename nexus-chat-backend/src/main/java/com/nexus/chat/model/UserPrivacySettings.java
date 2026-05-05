package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_privacy_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPrivacySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false)
    private Long userId;

    @Column(name = "show_online_status")
    private Boolean showOnlineStatus = true;

    @Column(name = "show_last_seen")
    private Boolean showLastSeen = true;

    @Column(name = "show_email")
    private Boolean showEmail = false;

    @Column(name = "show_phone")
    private Boolean showPhone = false;

    /**
     * 好友验证方式:
     * DIRECT - 直接同意，无需验证
     * VERIFY - 需要验证同意
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "friend_request_mode")
    private FriendRequestMode friendRequestMode = FriendRequestMode.VERIFY;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum FriendRequestMode {
        DIRECT,  // 直接同意
        VERIFY   // 验证同意
    }
}
