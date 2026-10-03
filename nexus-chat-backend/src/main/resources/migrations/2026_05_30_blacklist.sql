-- ============================================================
-- Migration: 2026_05_30_blacklist
-- Purpose: User blacklist (blocked_users). user_id blocks
--          blocked_user_id; blocking also removes any existing
--          contact relationship between the two users.
-- Note: ddl-auto=update also creates this table automatically from
--       the BlockedUser entity; this file documents the change for
--       environments that apply migrations manually.
-- ============================================================

CREATE TABLE IF NOT EXISTS blocked_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    blocked_user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (blocked_user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_block (user_id, blocked_user_id),
    INDEX idx_blocked_owner (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
