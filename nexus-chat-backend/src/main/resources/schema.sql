-- Nexus Chat Database Schema

-- Users Table
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

-- User Privacy Settings Table
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

-- Contacts Table
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

-- Chats Table (supports both direct and group chats)
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

-- Chat Members Table
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

-- Messages Table
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

-- Message Read Status Table
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

-- File Uploads Table
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

  -- ============================================
  -- 1. 用户表新增字段 (个人资料背景)
  -- ============================================


  ALTER TABLE users MODIFY COLUMN profile_background MEDIUMTEXT;

  -- ============================================
  -- 2. 用户社交链接表 (新建)
  -- ============================================
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

  -- ============================================
  -- 3. 用户安全设置表 (新建 - 两步验证等)
  -- ============================================
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

  -- ============================================
  -- 4. 用户会话表 (新建 - 活跃设备管理)
  -- ============================================
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

  -- ============================================
  -- 5. 登录历史表 (新建 - 登录记录)
  -- ============================================
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

  -- ============================================
  -- 6. 用户活动记录表 (新建 - 最近活动)
  -- ============================================
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

  -- ============================================
  -- 7. 好友申请表 (新建 - 好友验证功能)
  -- ============================================
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

  -- ============================================
  -- 8. 更新隐私设置表 - 添加好友验证方式字段
  -- 注意: MySQL不支持 ADD COLUMN IF NOT EXISTS
  -- 请先检查列是否存在，不存在时手动执行下面的ALTER语句
  -- ============================================
  -- 检查列是否存在:
  -- SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
  -- WHERE TABLE_SCHEMA = 'nexus_chat' AND TABLE_NAME = 'user_privacy_settings'
  -- AND COLUMN_NAME = 'friend_request_mode';
  --
  -- 如果不存在，执行:
  -- ALTER TABLE user_privacy_settings
  -- ADD COLUMN friend_request_mode ENUM('DIRECT', 'VERIFY') DEFAULT 'DIRECT';

  -- ============================================
  -- 9. 邮箱验证码表 (新建 - 注册验证)
  -- ============================================
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

  -- ============================================
  -- 10. Performance Optimization Indexes
  -- ============================================

  -- Contacts: reverse lookup (who has this user as a contact)
  -- Needed for efficient status change notifications
  CREATE INDEX IF NOT EXISTS idx_contacts_contact_user ON contacts(contact_user_id);

  -- Contact requests: faster pending request lookup
  CREATE INDEX IF NOT EXISTS idx_contact_requests_to_status ON contact_requests(to_user_id, status);

  -- ============================================
  -- 11. Message delivery fields (ACK + sequence)
  -- Reserved for Phase 3 implementation
  -- ============================================
  -- Migration for existing databases (Phase 3: message sequencing + deduplication)
  -- Run these manually if the messages table already exists:
  -- ALTER TABLE messages ADD COLUMN sequence_number BIGINT DEFAULT NULL;
  -- ALTER TABLE messages ADD COLUMN client_message_id VARCHAR(36) DEFAULT NULL;
  -- CREATE UNIQUE INDEX idx_messages_client_msg_id ON messages(client_message_id);

-- ============================================
-- Companion System Tables (MVP1)
-- ============================================

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

-- ==================== Agent Memory ====================
CREATE TABLE IF NOT EXISTS agent_long_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  confidence DECIMAL(4,3) NOT NULL,
  source_session_id VARCHAR(64) NOT NULL,
  source_trace_id VARCHAR(64) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_user_type_active (user_id, memory_type, is_active),
  INDEX idx_user_updated (user_id, updated_at)
);

CREATE TABLE IF NOT EXISTS agent_memory_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  memory_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  operator_type VARCHAR(16) NOT NULL,
  operator_id BIGINT NULL,
  reason VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_memory_action (memory_id, action)
);

CREATE TABLE IF NOT EXISTS agent_session_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  summary_version INT NOT NULL,
  covered_from_msg_id BIGINT NOT NULL,
  covered_to_msg_id BIGINT NOT NULL,
  summary_text TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_session_version (session_id, summary_version),
  INDEX idx_user_session (user_id, session_id)
);

CREATE TABLE IF NOT EXISTS agent_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  title VARCHAR(100) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_session_sid (session_id),
  INDEX idx_agent_session_user_updated (user_id, updated_at)
);
