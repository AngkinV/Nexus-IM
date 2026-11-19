package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_roles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String traits;

    @Column(columnDefinition = "TEXT")
    private String tone;

    @Column(name = "baseline_mood", length = 50)
    private String baselineMood;

    /**
     * Where she comes from and what she carries — a few sentences of the user's
     * own writing (or the model's, for an invited guest). Goes into the prompt
     * verbatim; null means the card has no backstory and none is invented.
     */
    @Column(name = "background", columnDefinition = "TEXT")
    private String background;

    /** The first thing she says when an evening opens. Null = she waits for you. */
    @Column(name = "greeting", columnDefinition = "TEXT")
    private String greeting;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "model_url", columnDefinition = "TEXT")
    private String modelUrl;

    @Column(name = "model_type", length = 20)
    private String modelType;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    /**
     * Whether this character lives here or was met once.
     * regular = 常驻 (the "home" side), guest = 过客 (came back from one evening).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20)
    private Kind kind = Kind.regular;

    /** The session this guest was adopted from. Null for regulars. */
    @Column(name = "origin_session_id")
    private Long originSessionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum Kind {
        regular, guest
    }

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
