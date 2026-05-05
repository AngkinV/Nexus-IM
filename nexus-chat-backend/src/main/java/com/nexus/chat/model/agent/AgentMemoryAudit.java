package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_memory_audit")
@Data
public class AgentMemoryAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memory_id", nullable = false)
    private Long memoryId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "operator_type", nullable = false, length = 16)
    private String operatorType;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "reason", length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
