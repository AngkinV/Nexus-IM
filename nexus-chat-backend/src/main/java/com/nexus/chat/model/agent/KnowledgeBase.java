package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Module B knowledge base — a per-user collection of documents that an Agent
 * session can be linked to for retrieval-augmented answering.
 *
 * <p>Document binary data lives under the existing FileUpload path; embedded
 * vector chunks live in the ChromaDB {@code kb_chunks} collection on the
 * Python side. This row owns only the user-facing metadata and ingestion
 * counters that Java endpoints expose.
 *
 * <p>The {@code documentCount} / {@code chunkCount} fields are eventually
 * consistent — they are bumped after Python reports a successful ingestion,
 * so a brief drift right after upload is expected.
 *
 * <p>Backed by migration {@code 2026_05_15_agent_rag.sql}.
 */
@Entity
@Table(name = "agent_knowledge_base", uniqueConstraints = {
        @UniqueConstraint(name = "uk_kb_id", columnNames = {"kb_id"})
})
@Data
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Public knowledge-base identifier ({@code kb_xxxxxxxx}). */
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** Embedding model used at ingestion time; pinned per-KB so re-indexing stays consistent. */
    @Column(name = "embedding_model", nullable = false, length = 80)
    private String embeddingModel = "text-embedding-3-small";

    /**
     * FK to {@code model_credentials.id} (with {@code purpose='embedding'}). NULL means
     * fall back to the Python service's environment-level embedding defaults — supported
     * for legacy KBs but discouraged for new ones since it makes the chat-vs-embedding
     * provider split implicit instead of explicit.
     */
    @Column(name = "embedding_credential_id")
    private Long embeddingCredentialId;

    /**
     * Vector dimension produced by the embedding model on first successful ingestion.
     * Locked thereafter so the UI can disable the embedding-credential selector — Chroma
     * locks dimension at first write of a collection, so silently switching providers
     * to one with a different dim would corrupt retrieval.
     */
    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize = 512;

    @Column(name = "chunk_overlap", nullable = false)
    private Integer chunkOverlap = 64;

    @Column(name = "document_count", nullable = false)
    private Integer documentCount = 0;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
