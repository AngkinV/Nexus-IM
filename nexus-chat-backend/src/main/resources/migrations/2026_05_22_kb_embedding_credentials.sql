-- ============================================================
-- Migration: 2026_05_22_kb_embedding_credentials
-- Purpose:
--   1) Split ModelCredential by `purpose` so a single user can hold separate
--      chat-only and embedding-only credentials (different providers, different
--      keys, often different default models). Embedding/chat credentials must
--      not share a row because the BYOK chat provider (DeepSeek / Moonshot /
--      "国产模型" aggregators / etc.) frequently does not expose
--      OpenAI-compatible /embeddings.
--   2) Bind each KnowledgeBase to a specific embedding credential
--      (`embedding_credential_id`) and remember the vector dimension produced
--      by that embedding model (`embedding_dimension`). Vector dimension is
--      locked by Chroma at first write, so storing it makes the "switch
--      embedding provider" mistake user-visible (UI can disable the change
--      once embedding_dimension is non-null).
-- Reference: agent开发文档/RAG扩展实施方案.md
-- ============================================================

-- ----------------------------------------------------------------
-- Step 1: model_credentials.purpose
--
-- We can't ALTER + change unique index atomically on every MySQL version, so
-- the order is: drop old unique key (if present) → add `purpose` column with
-- a safe default (`chat`) → recreate the composite unique key including
-- `purpose`. Wrapped in stored procs to stay idempotent on already-migrated DBs.
-- ----------------------------------------------------------------

DROP PROCEDURE IF EXISTS _mc_add_purpose_column;
DELIMITER $$
CREATE PROCEDURE _mc_add_purpose_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'model_credentials'
          AND COLUMN_NAME  = 'purpose'
    ) THEN
        ALTER TABLE model_credentials
            ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'chat'
            COMMENT 'chat | embedding — separates LLM credentials from embedding credentials'
            AFTER provider;
    END IF;
END$$
DELIMITER ;

CALL _mc_add_purpose_column();
DROP PROCEDURE IF EXISTS _mc_add_purpose_column;

-- Drop the legacy (user_id, provider) unique index if it still exists.
DROP PROCEDURE IF EXISTS _mc_drop_legacy_unique;
DELIMITER $$
CREATE PROCEDURE _mc_drop_legacy_unique()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'model_credentials'
          AND INDEX_NAME   = 'uk_model_credential'
    ) THEN
        ALTER TABLE model_credentials DROP INDEX uk_model_credential;
    END IF;
END$$
DELIMITER ;

CALL _mc_drop_legacy_unique();
DROP PROCEDURE IF EXISTS _mc_drop_legacy_unique;

-- Recreate as (user_id, provider, purpose). Idempotent: skip when present.
DROP PROCEDURE IF EXISTS _mc_add_purpose_unique;
DELIMITER $$
CREATE PROCEDURE _mc_add_purpose_unique()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'model_credentials'
          AND INDEX_NAME   = 'uk_model_credential_purpose'
    ) THEN
        ALTER TABLE model_credentials
            ADD UNIQUE KEY uk_model_credential_purpose (user_id, provider, purpose);
    END IF;
END$$
DELIMITER ;

CALL _mc_add_purpose_unique();
DROP PROCEDURE IF EXISTS _mc_add_purpose_unique;

-- Helpful lookup: "give me this user's default credential for purpose X".
DROP PROCEDURE IF EXISTS _mc_add_purpose_default_index;
DELIMITER $$
CREATE PROCEDURE _mc_add_purpose_default_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'model_credentials'
          AND INDEX_NAME   = 'idx_mc_user_purpose_default'
    ) THEN
        CREATE INDEX idx_mc_user_purpose_default
            ON model_credentials (user_id, purpose, is_default);
    END IF;
END$$
DELIMITER ;

CALL _mc_add_purpose_default_index();
DROP PROCEDURE IF EXISTS _mc_add_purpose_default_index;

-- ----------------------------------------------------------------
-- Step 2: agent_knowledge_base.embedding_credential_id + embedding_dimension
--
-- embedding_credential_id is nullable so the "no credential bound" path
-- (server-side env-var fallback) keeps working for legacy KBs created
-- before this migration. embedding_dimension is set by the Python service
-- on the first successful ingestion; once set, the UI freezes the
-- embedding selector for that KB to prevent dimension mismatch.
-- ----------------------------------------------------------------

DROP PROCEDURE IF EXISTS _kb_add_embedding_credential_id;
DELIMITER $$
CREATE PROCEDURE _kb_add_embedding_credential_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'agent_knowledge_base'
          AND COLUMN_NAME  = 'embedding_credential_id'
    ) THEN
        ALTER TABLE agent_knowledge_base
            ADD COLUMN embedding_credential_id BIGINT NULL DEFAULT NULL
            COMMENT 'FK to model_credentials.id (purpose=embedding); NULL = use server default'
            AFTER embedding_model;
    END IF;
END$$
DELIMITER ;

CALL _kb_add_embedding_credential_id();
DROP PROCEDURE IF EXISTS _kb_add_embedding_credential_id;

DROP PROCEDURE IF EXISTS _kb_add_embedding_dimension;
DELIMITER $$
CREATE PROCEDURE _kb_add_embedding_dimension()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'agent_knowledge_base'
          AND COLUMN_NAME  = 'embedding_dimension'
    ) THEN
        ALTER TABLE agent_knowledge_base
            ADD COLUMN embedding_dimension INT NULL DEFAULT NULL
            COMMENT 'Vector dim locked at first ingestion; UI blocks credential changes once set'
            AFTER embedding_credential_id;
    END IF;
END$$
DELIMITER ;

CALL _kb_add_embedding_dimension();
DROP PROCEDURE IF EXISTS _kb_add_embedding_dimension;

DROP PROCEDURE IF EXISTS _kb_add_embedding_cred_index;
DELIMITER $$
CREATE PROCEDURE _kb_add_embedding_cred_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'agent_knowledge_base'
          AND INDEX_NAME   = 'idx_kb_embedding_credential'
    ) THEN
        CREATE INDEX idx_kb_embedding_credential
            ON agent_knowledge_base (embedding_credential_id);
    END IF;
END$$
DELIMITER ;

CALL _kb_add_embedding_cred_index();
DROP PROCEDURE IF EXISTS _kb_add_embedding_cred_index;
