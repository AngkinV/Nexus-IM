package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Module B knowledge document — one uploaded file inside a {@link KnowledgeBase}.
 *
 * <p>Carries the ingestion lifecycle state machine: {@code PENDING → PROCESSING →
 * READY | FAILED}. The Python service is the writer of the terminal states
 * (READY/FAILED) via the upload-callback channel; Java seeds rows in PENDING
 * on file upload.
 *
 * <p>The row is the canonical handle for client-facing operations (list,
 * delete, status poll). Vectors live in the ChromaDB {@code kb_chunks}
 * collection keyed by {@code docId}.
 *
 * <p>Backed by migration {@code 2026_05_15_agent_rag.sql}.
 */
@Entity
@Table(name = "agent_knowledge_document", uniqueConstraints = {
        @UniqueConstraint(name = "uk_doc_id", columnNames = {"doc_id"})
})
@Data
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning {@link KnowledgeBase#getKbId()}. Not a FK by design — looked up lazily. */
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;

    /** Public document identifier ({@code doc_xxxxxxxx}). */
    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** Storage path on the shared FileUpload directory. */
    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** Lowercase short suffix: {@code pdf / md / txt / docx}. */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** Number of chunks produced after a successful ingestion. 0 until READY. */
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestionStatus status = IngestionStatus.PENDING;

    /** Failure reason populated when {@link #status} is FAILED. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum IngestionStatus {
        PENDING,
        PROCESSING,
        READY,
        FAILED
    }
}
