package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_messages", indexes = {
        // Tonight's history and the per-evening count both read by session.
        @Index(name = "idx_companion_messages_session", columnList = "session_id"),
        // Retry lookup: has this client id been filed for this character already?
        @Index(name = "idx_companion_messages_client", columnList = "role_id,client_msg_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType = SenderType.user;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_fallback", nullable = false)
    private Boolean fallback = false;

    /**
     * Which tavern session this message belongs to. Null for everything written
     * before sessions existed — those still render, they just belong to no
     * particular evening.
     */
    @Column(name = "session_id")
    private Long sessionId;

    /**
     * What she reported about herself with this reply — mood, motion, what she
     * is doing — as JSON. Null when the model sent no marker; never inferred.
     */
    @Column(name = "state_json", columnDefinition = "TEXT")
    private String stateJson;

    /**
     * The id the client generated for this send. A retry carries the same id, so
     * the server can tell "send again" from "send another" and never stores the
     * same line twice.
     */
    @Column(name = "client_msg_id", length = 64)
    private String clientMsgId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum SenderType {
        user, role, system
    }
}
