package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_session_summary", uniqueConstraints = {
        @UniqueConstraint(name = "uk_session_version", columnNames = {"session_id", "summary_version"})
})
@Data
public class AgentSessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "summary_version", nullable = false)
    private Integer summaryVersion;

    @Column(name = "covered_from_msg_id", nullable = false)
    private Long coveredFromMsgId;

    @Column(name = "covered_to_msg_id", nullable = false)
    private Long coveredToMsgId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
