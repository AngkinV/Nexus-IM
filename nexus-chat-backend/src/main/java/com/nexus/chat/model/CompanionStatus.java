package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_status",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_type", nullable = false)
    private StatusType statusType = StatusType.resting;

    @Column(columnDefinition = "TEXT")
    private String summary;

    /**
     * Her mood and motion as she last reported them (see CompanionStateTag).
     * Null until a model has said so — a non-null mood also marks `summary` as
     * her own words rather than the clock-based placeholder.
     */
    @Column(name = "mood", length = 20)
    private String mood;

    @Column(name = "motion", length = 20)
    private String motion;

    /** A short-lived cue from the latest reply; cleared when no gesture is reported. */
    @Column(name = "gesture", length = 20)
    private String gesture;

    /**
     * True when `summary` is something a model wrote about her (a reported
     * "doing", or the presence line of an invited guest). Such a line is hers and
     * is never replaced by the clock-based placeholder.
     */
    @Column(name = "summary_authored")
    private Boolean summaryAuthored;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum StatusType {
        reading, listening, walking, thinking, resting, chatting, writing, organizing
    }
}
