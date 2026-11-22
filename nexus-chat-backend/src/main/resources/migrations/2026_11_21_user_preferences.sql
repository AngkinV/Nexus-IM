-- ============================================================
-- Migration: 2026_11_21_user_preferences
-- Purpose: Account-scoped interface preferences (hour cycle, glass
--          intensity, UI sounds). These follow the user across
--          machines, unlike device-bound settings (zoom, frame cap,
--          dock badge, local encryption) which stay in localStorage.
-- Note: ddl-auto=update also creates this table automatically from
--       the UserPreference entity; this file documents the change for
--       environments that apply migrations manually.
-- ============================================================

CREATE TABLE IF NOT EXISTS user_preferences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    hour_cycle VARCHAR(2) DEFAULT '24',
    glass_intensity INT DEFAULT 100,
    sound_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_user_preference (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
