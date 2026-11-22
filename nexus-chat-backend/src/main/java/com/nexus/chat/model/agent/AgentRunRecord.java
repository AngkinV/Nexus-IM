package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_run", uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_id", columnNames = "run_id"),
        indexes = @Index(name = "idx_agent_run_history", columnList = "user_id,session_id,started_at,id"))
@Getter
@Setter
public class AgentRunRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 64)
    private String sessionId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;
}
