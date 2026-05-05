package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Java-side metadata mirror for a Module A semantic memory chunk.
 *
 * <p>Vector data lives in ChromaDB inside the Python service. This row is the
 * canonical Java handle on the chunk for listing, debugging, and audit. The
 * {@code chunkId} matches the document id used in the ChromaDB
 * {@code memory_chunks} collection so the two stores can be cross-referenced.
 *
 * <p>Backed by migration {@code 2026_05_15_agent_rag.sql}.
 */
@Entity
@Table(name = "agent_memory_embedding", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chunk", columnNames = {"chunk_id"})
})
@Data
public class AgentMemoryEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** Public chunk identifier; also the document id in the ChromaDB collection. */
    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "user_text", nullable = false, columnDefinition = "TEXT")
    private String userText;

    @Column(name = "assistant_text", nullable = false, columnDefinition = "TEXT")
    private String assistantText;

    /** Optional one-line summary the UI may display in a recall preview. */
    @Column(length = 500)
    private String summary;

    /** Trace id of the request that produced this chunk; aids cross-service log correlation. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
