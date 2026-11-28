package com.nexus.chat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "companion_memories", uniqueConstraints = @UniqueConstraint(name="uk_companion_memory_source", columnNames={"user_id","role_id","source_hash"}), indexes=@Index(name="idx_companion_memory_owner_role",columnList="user_id,role_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanionMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false)
    private MemoryType memoryType = MemoryType.mid;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Boolean confirmed = false;

    @Column(nullable = false)
    private Boolean shared = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "source_session_id")
    private Long sourceSessionId;
    @Column(name = "source_message_id")
    private Long sourceMessageId;
    @Column(name = "source_hash", length = 64)
    private String sourceHash;
    private LocalDateTime occurredAt;
    private LocalDateTime sourceCreatedAt;
    @Column(columnDefinition = "boolean default false")
    private Boolean deleted = false;
    private Long correctsId;
    @Column(name = "embedding_revision", length = 64)
    private String embeddingRevision;
    @Column(name = "embedding_vector", columnDefinition = "MEDIUMTEXT")
    private String embeddingVector;
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version = 0L;

    public enum MemoryType {
        short_term, mid, long_term
    }
}
