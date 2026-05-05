-- ============================================================
-- Migration: 2026_05_15_agent_rag
-- Purpose: Tables for the RAG extension (Modules A + B + future C).
--   * agent_memory_embedding   - Module A: per-turn memory chunks
--                                (vector data lives in ChromaDB; this is
--                                the Java-queryable metadata mirror)
--   * agent_knowledge_base     - Module B: per-user knowledge bases
--   * agent_knowledge_document - Module B: documents in a knowledge base
--                                with ingestion lifecycle
--   * agent_session.linked_kb_id (ALTER) - link a chat session to a KB
-- Reference: agent开发文档/RAG扩展实施方案.md §2.1
-- ============================================================

-- ----------------------------------------------------------------
-- Module A: memory chunks (metadata mirror)
--
-- Chroma stores the embedding vectors and the page_content; we mirror only
-- the searchable fields here so Java endpoints (history listing, debug,
-- audit) don't have to round-trip into the Python service.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_memory_embedding (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    chunk_id        VARCHAR(64)  NOT NULL COMMENT 'mem_xxxxxxxx, also the ChromaDB document id',
    user_text       TEXT         NOT NULL,
    assistant_text  TEXT         NOT NULL,
    summary         VARCHAR(500) NULL    COMMENT 'optional one-line summary for UI display',
    trace_id        VARCHAR(64)  NULL    COMMENT 'request trace id that produced this chunk',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chunk (chunk_id),
    INDEX idx_user_session (user_id, session_id),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------
-- Module B: knowledge bases
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_knowledge_base (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    kb_id           VARCHAR(64)  NOT NULL COMMENT 'kb_xxxxxxxx, public identifier',
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500) NULL,
    embedding_model VARCHAR(80)  NOT NULL DEFAULT 'text-embedding-3-small',
    chunk_size      INT          NOT NULL DEFAULT 512,
    chunk_overlap   INT          NOT NULL DEFAULT 64,
    document_count  INT          NOT NULL DEFAULT 0,
    chunk_count     INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_id (kb_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------
-- Module B: knowledge base documents
--
-- status:
--   PENDING    - row created, ingestion not started
--   PROCESSING - loader/splitter/embeddings in progress
--   READY      - chunks indexed in Chroma; queryable
--   FAILED     - ingestion error; see error_message
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_knowledge_document (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id           VARCHAR(64)  NOT NULL,
    doc_id          VARCHAR(64)  NOT NULL COMMENT 'doc_xxxxxxxx, public identifier',
    file_name       VARCHAR(255) NOT NULL,
    file_path       VARCHAR(500) NOT NULL COMMENT 'shared FileUploadController path',
    file_size       BIGINT       NOT NULL,
    file_type       VARCHAR(20)  NOT NULL COMMENT 'pdf / md / txt / docx',
    chunk_count     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(500) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_id (doc_id),
    INDEX idx_kb (kb_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------
-- Module B: link an Agent session to a knowledge base
--
-- When set, the orchestrator will run the user's query through the kb's
-- retriever before answering. NULL means no kb is bound (default).
--
-- The ALTER below is wrapped in a procedure so the migration is idempotent
-- on a fresh DB and on already-migrated DBs alike (MySQL has no native
-- IF NOT EXISTS for ADD COLUMN).
-- ----------------------------------------------------------------
DROP PROCEDURE IF EXISTS _add_linked_kb_id_if_missing;
DELIMITER $$
CREATE PROCEDURE _add_linked_kb_id_if_missing()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'agent_session'
          AND COLUMN_NAME  = 'linked_kb_id'
    ) THEN
        ALTER TABLE agent_session
            ADD COLUMN linked_kb_id VARCHAR(64) NULL DEFAULT NULL
            COMMENT 'optional knowledge base bound to this session (Module B)';
    END IF;
END$$
DELIMITER ;

CALL _add_linked_kb_id_if_missing();
DROP PROCEDURE IF EXISTS _add_linked_kb_id_if_missing;
