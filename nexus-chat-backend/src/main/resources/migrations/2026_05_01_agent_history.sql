-- ============================================================
-- Migration: 2026_05_01_agent_history
-- Purpose: Backing store for the AI Assistant's "history conversations" feature.
--          Holds session metadata only — actual messages live in Redis short-term
--          memory and are loaded on demand when the user reopens a session.
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_session (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  VARCHAR(64)  NOT NULL,
    title       VARCHAR(100) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_session_sid (session_id),
    INDEX idx_agent_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
