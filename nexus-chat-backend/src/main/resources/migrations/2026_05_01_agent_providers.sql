-- ============================================================
-- Migration: 2026_05_01_agent_providers
-- Purpose: Extend model_credentials with the columns required by the Agent
--          multi-provider feature (display name, base URL, default model,
--          per-user default flag).
-- Applies to: an existing model_credentials table created by schema.sql.
-- ============================================================

ALTER TABLE model_credentials
    ADD COLUMN IF NOT EXISTS display_name  VARCHAR(80)  DEFAULT NULL AFTER provider,
    ADD COLUMN IF NOT EXISTS base_url      VARCHAR(255) DEFAULT NULL AFTER display_name,
    ADD COLUMN IF NOT EXISTS default_model VARCHAR(120) DEFAULT NULL AFTER base_url,
    ADD COLUMN IF NOT EXISTS is_default    TINYINT(1)   NOT NULL DEFAULT 0 AFTER api_key_encrypted;

-- Ensure at most one default per user is enforced by application logic;
-- a partial unique index would require MySQL 8 + functional index, which we
-- avoid for portability. The composite index below speeds up the lookup.
CREATE INDEX IF NOT EXISTS idx_model_credential_user_default
    ON model_credentials (user_id, is_default);
