-- ============================================================
-- Nexus Chat 一键完整建表脚本（含 IM + Profile + Companion + Agent + RAG）
-- ============================================================
-- 用法：mysql -uroot -p12345678 nexus_chat < schema_full.sql
--
-- 特性：
--   * 全部 CREATE TABLE 都用 IF NOT EXISTS——已存在的表不会被覆盖
--   * 所有 ALTER 都通过 information_schema 判断后执行——重复跑安全
--   * 不 DROP 任何表/列，旧数据不会丢
--
-- 涵盖：
--   1. IM 核心：users / contacts / chats / messages …
--   2. 个人资料：user_security_settings / user_sessions / login_history …
--   3. 陪伴系统：companion_roles / companion_growths …
--   4. 模型凭证：model_credentials（含 BYOK 多 Provider 字段）
--   5. Agent 记忆：agent_long_memory / agent_memory_audit / agent_session_summary / agent_session
--   6. RAG（本次新增）：agent_memory_embedding / agent_knowledge_base / agent_knowledge_document
--      + agent_session.linked_kb_id 列
-- ============================================================

-- 数据库本身在 application.properties 的 jdbc URL 中通过
-- createDatabaseIfNotExist=true 自动建。这里假设已经选好库。
-- 如果是手工执行，请先：CREATE DATABASE IF NOT EXISTS nexus_chat
--   DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci; USE nexus_chat;


-- ================================================================
-- 1. IM 核心
-- ================================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    phone VARCHAR(20),
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(100) NOT NULL,
    avatar_url MEDIUMTEXT,
    bio VARCHAR(150) DEFAULT NULL,
    is_online BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_is_online (is_online),
    FULLTEXT INDEX idx_search (username, nickname, email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_privacy_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    show_online_status BOOLEAN DEFAULT TRUE,
    show_last_seen BOOLEAN DEFAULT TRUE,
    show_email BOOLEAN DEFAULT FALSE,
    show_phone BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contacts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    contact_user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (contact_user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_contact (user_id, contact_user_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    type ENUM('direct', 'group') NOT NULL,
    name VARCHAR(100),
    description VARCHAR(200) DEFAULT NULL,
    avatar_url MEDIUMTEXT,
    is_private BOOLEAN DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    member_count INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_type (type),
    INDEX idx_last_message_at (last_message_at),
    INDEX idx_is_private (is_private)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role ENUM('owner', 'admin', 'member') DEFAULT 'member',
    is_admin BOOLEAN DEFAULT FALSE,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unread_count INT DEFAULT 0,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_chat_member (chat_id, user_id),
    INDEX idx_chat_id (chat_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chat_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content TEXT,
    message_type ENUM('text', 'image', 'file', 'emoji') NOT NULL DEFAULT 'text',
    file_url TEXT,
    sequence_number BIGINT DEFAULT NULL,
    client_message_id VARCHAR(36) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_chat_id_created_at (chat_id, created_at),
    INDEX idx_sender_id (sender_id),
    UNIQUE INDEX idx_messages_client_msg_id (client_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_read_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    read_at TIMESTAMP NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_read_status (message_id, user_id),
    INDEX idx_message_id (message_id),
    INDEX idx_user_id_is_read (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_uploads (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT,
    filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_path TEXT NOT NULL,
    chunk_count INT DEFAULT 1,
    upload_complete BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    INDEX idx_message_id (message_id),
    INDEX idx_upload_complete (upload_complete)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ================================================================
-- 2. 个人资料相关
-- ================================================================

-- users.profile_background：原 schema.sql 的 ALTER MODIFY 在新库会因列不存在直接失败。
-- 这里改成"不存在则 ADD，存在则确保为 MEDIUMTEXT"。
DROP PROCEDURE IF EXISTS _ensure_users_profile_background;
DELIMITER $$
CREATE PROCEDURE _ensure_users_profile_background()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'users'
          AND COLUMN_NAME  = 'profile_background'
    ) THEN
        ALTER TABLE users
            ADD COLUMN profile_background MEDIUMTEXT NULL DEFAULT NULL
            COMMENT '个人资料背景：CSS gradient 或图片 URL';
    ELSE
        ALTER TABLE users MODIFY COLUMN profile_background MEDIUMTEXT;
    END IF;
END$$
DELIMITER ;
CALL _ensure_users_profile_background();
DROP PROCEDURE IF EXISTS _ensure_users_profile_background;


CREATE TABLE IF NOT EXISTS user_social_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    platform VARCHAR(50) NOT NULL,
    url VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_user_platform (user_id, platform),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_security_settings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    two_factor_enabled BOOLEAN DEFAULT FALSE,
    two_factor_secret VARCHAR(255) DEFAULT NULL,
    backup_codes TEXT DEFAULT NULL,
    password_changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_token VARCHAR(255) NOT NULL UNIQUE,
    device_name VARCHAR(100) DEFAULT NULL,
    device_type ENUM('desktop', 'mobile', 'tablet', 'unknown') DEFAULT 'unknown',
    browser VARCHAR(100) DEFAULT NULL,
    ip_address VARCHAR(45) DEFAULT NULL,
    location VARCHAR(200) DEFAULT NULL,
    is_current BOOLEAN DEFAULT FALSE,
    last_active TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_session_token (session_token),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS login_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    success BOOLEAN DEFAULT TRUE,
    ip_address VARCHAR(45) DEFAULT NULL,
    location VARCHAR(200) DEFAULT NULL,
    device VARCHAR(200) DEFAULT NULL,
    browser VARCHAR(100) DEFAULT NULL,
    failure_reason VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at),
    INDEX idx_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_activities (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    activity_type ENUM('message', 'contact', 'group', 'login', 'profile_update') NOT NULL,
    description TEXT DEFAULT NULL,
    related_id BIGINT DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_activity_type (activity_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contact_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    message VARCHAR(200) DEFAULT NULL,
    status ENUM('PENDING', 'ACCEPTED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (from_user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (to_user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_request (from_user_id, to_user_id),
    INDEX idx_from_user_id (from_user_id),
    INDEX idx_to_user_id (to_user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- user_privacy_settings.friend_request_mode：原 schema.sql 注释要求手动执行
DROP PROCEDURE IF EXISTS _ensure_friend_request_mode;
DELIMITER $$
CREATE PROCEDURE _ensure_friend_request_mode()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = 'user_privacy_settings'
          AND COLUMN_NAME  = 'friend_request_mode'
    ) THEN
        ALTER TABLE user_privacy_settings
            ADD COLUMN friend_request_mode ENUM('DIRECT', 'VERIFY') DEFAULT 'DIRECT';
    END IF;
END$$
DELIMITER ;
CALL _ensure_friend_request_mode();
DROP PROCEDURE IF EXISTS _ensure_friend_request_mode;

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL,
    code VARCHAR(6) NOT NULL,
    type ENUM('REGISTER', 'RESET_PASSWORD', 'CHANGE_EMAIL') DEFAULT 'REGISTER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    INDEX idx_email (email),
    INDEX idx_email_code (email, code),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 性能索引（MySQL 不支持 CREATE INDEX IF NOT EXISTS，用存储过程做幂等）
DROP PROCEDURE IF EXISTS _ensure_index;
DELIMITER $$
CREATE PROCEDURE _ensure_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME   = p_table
          AND INDEX_NAME   = p_index
    ) THEN
        SET @sql = p_ddl;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;
CALL _ensure_index('contacts',         'idx_contacts_contact_user',
                   'CREATE INDEX idx_contacts_contact_user ON contacts(contact_user_id)');
CALL _ensure_index('contact_requests', 'idx_contact_requests_to_status',
                   'CREATE INDEX idx_contact_requests_to_status ON contact_requests(to_user_id, status)');
DROP PROCEDURE IF EXISTS _ensure_index;


-- ================================================================
-- 3. 陪伴系统（Companion）
-- ================================================================

CREATE TABLE IF NOT EXISTS companion_roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    traits TEXT,
    tone TEXT,
    baseline_mood VARCHAR(50),
    avatar_url TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_companion_roles_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_growths (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    intimacy INT DEFAULT 0,
    trust INT DEFAULT 0,
    stability INT DEFAULT 80,
    co_growth INT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_companion_growth (user_id, role_id),
    INDEX idx_companion_growth_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    memory_type ENUM('short_term','mid','long_term') DEFAULT 'mid',
    content TEXT,
    confirmed BOOLEAN DEFAULT FALSE,
    shared BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_companion_memories_user (user_id),
    INDEX idx_companion_memories_role (role_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_conversations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_companion_conversation (user_id, role_id),
    INDEX idx_companion_conversation_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    sender_type ENUM('user','role','system') NOT NULL,
    content TEXT,
    is_fallback BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_companion_messages_conversation (conversation_id),
    INDEX idx_companion_messages_user (user_id),
    FOREIGN KEY (conversation_id) REFERENCES companion_conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_model_bindings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    provider VARCHAR(50) DEFAULT 'openai-compatible',
    model_name VARCHAR(100),
    endpoint VARCHAR(500),
    enabled BOOLEAN DEFAULT TRUE,
    UNIQUE KEY uk_companion_binding (user_id, role_id),
    INDEX idx_companion_binding_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS companion_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    status_type ENUM('reading','listening','walking','thinking','resting','chatting','writing','organizing') DEFAULT 'resting',
    summary TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_companion_status (user_id, role_id),
    INDEX idx_companion_status_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES companion_roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ================================================================
-- 4. 模型凭证（BYOK 多 Provider）
-- ================================================================

CREATE TABLE IF NOT EXISTS model_credentials (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(50) NOT NULL,
    purpose VARCHAR(20) NOT NULL DEFAULT 'chat',
    display_name VARCHAR(80) DEFAULT NULL,
    base_url VARCHAR(255) DEFAULT NULL,
    default_model VARCHAR(120) DEFAULT NULL,
    api_key_encrypted TEXT,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    status ENUM('ok','invalid','unknown') DEFAULT 'unknown',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_credential_purpose (user_id, provider, purpose),
    INDEX idx_model_credential_user (user_id),
    INDEX idx_model_credential_user_default (user_id, is_default),
    INDEX idx_mc_user_purpose_default (user_id, purpose, is_default),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 老库可能是没加新列的旧 model_credentials；下面 4 列任一缺失就补上
DROP PROCEDURE IF EXISTS _patch_model_credentials_columns;
DELIMITER $$
CREATE PROCEDURE _patch_model_credentials_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'display_name') THEN
        ALTER TABLE model_credentials
            ADD COLUMN display_name VARCHAR(80) DEFAULT NULL AFTER provider;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'base_url') THEN
        ALTER TABLE model_credentials
            ADD COLUMN base_url VARCHAR(255) DEFAULT NULL AFTER display_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'default_model') THEN
        ALTER TABLE model_credentials
            ADD COLUMN default_model VARCHAR(120) DEFAULT NULL AFTER base_url;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'is_default') THEN
        ALTER TABLE model_credentials
            ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0 AFTER api_key_encrypted;
    END IF;
END$$
DELIMITER ;
CALL _patch_model_credentials_columns();
DROP PROCEDURE IF EXISTS _patch_model_credentials_columns;


-- ================================================================
-- 5. Agent 记忆基表
-- ================================================================

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


-- ================================================================
-- 6. RAG（Module A 元信息镜像 + Module B 知识库）
-- ================================================================

-- Module A：会话历史向量元信息（向量数据在 ChromaDB；这里供 Java 侧审计/重建用）
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

-- Module B：知识库
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

-- Module B：知识库文档（ingestion 状态机）
--   PENDING    → 行已建，待处理
--   PROCESSING → loader/splitter/embedding 进行中
--   READY      → 切片已入 Chroma，可检索
--   FAILED     → ingestion 失败，看 error_message
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

-- agent_session.linked_kb_id：把会话关联到某个知识库
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


-- ============================================================
-- 完成。建议执行后跑一遍：
--   SHOW TABLES;
--   SHOW COLUMNS FROM agent_session LIKE 'linked_kb_id';
--   SHOW COLUMNS FROM model_credentials LIKE 'is_default';
-- 上面三个都应有结果。
-- ============================================================
