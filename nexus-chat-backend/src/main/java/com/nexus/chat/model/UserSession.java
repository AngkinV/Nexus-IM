package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_token", unique = true, nullable = false)
    private String sessionToken;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type")
    private DeviceType deviceType = DeviceType.unknown;

    @Column(length = 100)
    private String browser;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(length = 200)
    private String location;

    @Column(name = "is_current")
    private Boolean isCurrent = false;

    @UpdateTimestamp
    @Column(name = "last_active")
    private LocalDateTime lastActive;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public enum DeviceType {
        desktop, mobile, tablet, unknown
    }
}
