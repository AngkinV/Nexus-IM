-- Companion MVP1 schema + seed (idempotent)

-- ================================
-- Tables
-- ================================

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
    api_key_encrypted TEXT,
    status ENUM('ok','invalid','unknown') DEFAULT 'unknown',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_credential (user_id, provider),
    INDEX idx_model_credential_user (user_id),
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

-- ================================
-- Seed roles (idempotent per user)
-- ================================

INSERT INTO companion_roles (user_id, name, traits, tone, baseline_mood, is_active)
SELECT u.id, '温柔倾听者', '["温柔","慢节奏","共情强"]', '柔和、慢速、积极反馈', '安稳', TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM companion_roles r
    WHERE r.user_id = u.id AND r.name = '温柔倾听者'
);

INSERT INTO companion_roles (user_id, name, traits, tone, baseline_mood, is_active)
SELECT u.id, '理性伙伴', '["清晰","结构化","执行力"]', '简洁、清晰、问题导向', '理性平衡', TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM companion_roles r
    WHERE r.user_id = u.id AND r.name = '理性伙伴'
);

INSERT INTO companion_roles (user_id, name, traits, tone, baseline_mood, is_active)
SELECT u.id, '活力陪玩', '["轻松","活跃","鼓励型"]', '明快、轻松、带一点活力', '活力', TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM companion_roles r
    WHERE r.user_id = u.id AND r.name = '活力陪玩'
);

-- ================================
-- Seed growth/status (idempotent)
-- ================================

INSERT INTO companion_growths (user_id, role_id, intimacy, trust, stability, co_growth)
SELECT r.user_id, r.id, 20, 15, 80, 10
FROM companion_roles r
WHERE NOT EXISTS (
    SELECT 1 FROM companion_growths g
    WHERE g.user_id = r.user_id AND g.role_id = r.id
);

INSERT INTO companion_status (user_id, role_id, status_type, summary)
SELECT r.user_id, r.id, 'resting', CONCAT(r.name, '在休息一会儿。')
FROM companion_roles r
WHERE NOT EXISTS (
    SELECT 1 FROM companion_status s
    WHERE s.user_id = r.user_id AND s.role_id = r.id
);
