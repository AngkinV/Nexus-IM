package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-user chat session with the Nexus AI Assistant. Acts as the index that backs the
 * "history conversations" dropdown — actual messages still live in Redis short-term
 * memory keyed by {@code agent:ctx:{userId}:{sessionId}:messages}.
 */
@Entity
@Table(name = "agent_session", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_session_sid", columnNames = {"session_id"})
})
@Data
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Public session identifier (the {@code as_xxxx} string clients see). */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /**
     * User-friendly title shown in the history dropdown. Auto-generated from the first
     * user message if blank.
     */
    @Column(length = 100)
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
