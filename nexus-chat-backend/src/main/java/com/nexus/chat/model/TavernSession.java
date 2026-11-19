package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One visit to the tavern.
 *
 * The conversation table holds the *relationship* between a user and a role — one
 * row, forever. A session holds one *evening*: which room, when it opened, when
 * it closed, and how it ended. Messages belong to a session, which is what makes
 * "散场" meaningful: the next visit starts clean, and the last one stays readable
 * in the archive without feeding into the new context.
 */
@Entity
@Table(name = "tavern_sessions", indexes = {
        @Index(name = "idx_tavern_session_user_role", columnList = "user_id,role_id"),
        @Index(name = "idx_tavern_session_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavernSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "scene_key", length = 50)
    private String sceneKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.active;

    @CreationTimestamp
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** What the character said on the way out. Her words, not a summary of them. */
    @Column(name = "closing_line", columnDefinition = "TEXT")
    private String closingLine;

    /** One sentence kept as long-term memory. Null when nothing was worth keeping. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    public enum Status {
        active, closed
    }
}
