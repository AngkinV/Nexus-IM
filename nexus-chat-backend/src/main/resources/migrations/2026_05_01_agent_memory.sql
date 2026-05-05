-- ============================================================
-- Migration: 2026_05_01_agent_memory
-- Purpose: Tables for the Agent memory layer
--   * agent_long_memory     - stable user-level facts/preferences
--   * agent_memory_audit    - audit trail for memory mutations
--   * agent_session_summary - compressed session summary blocks
-- Reference: agent开发文档/Agent 记忆设计（短期:长期:压缩:治理）.md (sections 5, 8)
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_long_memory (
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    memory_type        VARCHAR(32)  NOT NULL COMMENT 'PREFERENCE / FACT / HABIT / CONSTRAINT',
    content            TEXT         NOT NULL COMMENT 'masked memory text',
    confidence         DECIMAL(4,3) NOT NULL COMMENT '0.000 ~ 1.000',
    source_session_id  VARCHAR(64)  NOT NULL,
    source_trace_id    VARCHAR(64)  NOT NULL,
    is_active          TINYINT(1)   NOT NULL DEFAULT 1,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_type_active (user_id, memory_type, is_active),
    INDEX idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory_audit (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_id      BIGINT      NOT NULL,
    action         VARCHAR(32) NOT NULL COMMENT 'CREATE / UPDATE / DISABLE / DELETE',
    operator_type  VARCHAR(16) NOT NULL COMMENT 'SYSTEM / USER / ADMIN',
    operator_id    BIGINT      NULL,
    reason         VARCHAR(255) NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_memory_action (memory_id, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_session_summary (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT      NOT NULL,
    session_id          VARCHAR(64) NOT NULL,
    summary_version     INT         NOT NULL,
    covered_from_msg_id BIGINT      NOT NULL,
    covered_to_msg_id   BIGINT      NOT NULL,
    summary_text        TEXT        NOT NULL,
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_version (session_id, summary_version),
    INDEX idx_user_session (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
