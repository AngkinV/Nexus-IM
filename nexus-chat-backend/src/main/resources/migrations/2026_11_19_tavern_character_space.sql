-- ============================================================
-- Migration: 2026_11_19_tavern_character_space
-- Purpose: What the tavern needs to put a character in a room instead
--          of a chat box:
--            * a real character card (backstory, opening line)
--            * the state she reports with each reply (mood/motion/doing)
--            * a client id per send, so a retry is never filed twice
--            * indexes for reading one evening's messages
-- Note: ddl-auto=update adds these columns and indexes automatically
--       from the entities; this file documents the change for
--       environments that apply migrations manually. Every column is
--       nullable and nothing existing is rewritten. Re-running it fails
--       on the first statement (column exists) and changes nothing.
-- ============================================================

ALTER TABLE companion_roles
    ADD COLUMN background TEXT NULL,
    ADD COLUMN greeting TEXT NULL;

ALTER TABLE companion_messages
    ADD COLUMN state_json TEXT NULL,
    ADD COLUMN client_msg_id VARCHAR(64) NULL,
    ADD INDEX idx_companion_messages_session (session_id),
    ADD INDEX idx_companion_messages_client (role_id, client_msg_id);

ALTER TABLE companion_status
    ADD COLUMN mood VARCHAR(20) NULL,
    ADD COLUMN motion VARCHAR(20) NULL,
    ADD COLUMN summary_authored BIT(1) NULL;
