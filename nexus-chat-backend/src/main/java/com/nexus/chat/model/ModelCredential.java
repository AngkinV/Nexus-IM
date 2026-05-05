package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "model_credentials",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider", "purpose"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelCredential {

    /** What this credential is allowed to be used for. */
    public static final String PURPOSE_CHAT = "chat";
    public static final String PURPOSE_EMBEDDING = "embedding";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String provider = "openai-compatible";

    /** {@value #PURPOSE_CHAT} or {@value #PURPOSE_EMBEDDING}. Default chat for backward compat. */
    @Column(nullable = false, length = 20)
    private String purpose = PURPOSE_CHAT;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(name = "default_model", length = 120)
    private String defaultModel;

    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status = CredentialStatus.unknown;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum CredentialStatus {
        ok, invalid, unknown
    }
}
