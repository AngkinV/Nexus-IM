package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_long_memory", uniqueConstraints=@UniqueConstraint(name="uk_agent_memory_source",columnNames={"user_id","scope_key","source_hash"}))
@Data
public class AgentLongMemory {

    @Column(name = "scope_key", length = 64)
    private String scopeKey;

    @Column(name = "session_generation")
    private Long sessionGeneration;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "memory_type", nullable = false, length = 32)
    private String memoryType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "source_session_id", nullable = false, length = 64)
    private String sourceSessionId;

    @Column(name = "source_trace_id", nullable = false, length = 64)
    private String sourceTraceId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name="source_created_at")
    private java.time.Instant sourceCreatedAt;

    @Column(name="source_run_id", length=64)
    private String sourceRunId;
    @Column(name="source_hash", length=64)
    private String sourceHash;
    @Column(columnDefinition="boolean default false")
    private Boolean confirmed=false;
    @Column(name="embedding_revision",length=64)
    private String embeddingRevision;
    @Column(name="embedding_vector",columnDefinition="MEDIUMTEXT")
    private String embeddingVector;
    @Version
    @Column(nullable=false,columnDefinition="bigint default 0")
    private Long version=0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
