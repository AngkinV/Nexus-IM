-- ============================================================
-- Migration: 2026_05_30_security_password_strength
-- Purpose: Cache a 0-100 password strength score on the security
--          settings row, computed from the plaintext at register /
--          change-password time (the hash itself can't be scored later).
-- Note: ddl-auto=update also adds this column automatically from the
--       UserSecuritySettings entity; this file documents the change for
--       environments that apply migrations manually.
-- ============================================================

ALTER TABLE user_security_settings
    ADD COLUMN password_strength INT DEFAULT NULL;
