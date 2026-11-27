package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_security_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSecuritySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false)
    private Long userId;

    @Column(name = "two_factor_enabled")
    private Boolean twoFactorEnabled = false;

    @lombok.ToString.Exclude
    @Column(name = "two_factor_secret")
    private String twoFactorSecret;

    @lombok.ToString.Exclude
    @Column(name = "backup_codes", columnDefinition = "TEXT")
    private String backupCodes;

    /** Highest TOTP step accepted for public authentication; prevents replay in the drift window. */
    @Column(name = "last_accepted_totp_step")
    private Long lastAcceptedTotpStep;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /** Cached strength score (0-100) computed from the plaintext at set/change time. */
    @Column(name = "password_strength")
    private Integer passwordStrength;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
