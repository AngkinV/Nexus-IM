-- ============================================================
-- Migration: 2026_11_23_model_channel_vendor_format
-- Purpose: Let one account keep any number of model channels,
--          official vendors and private relays alike.
--
--   model_credentials.provider used to carry three meanings at once: the
--   channel's unique id, the vendor it came from, and the wire protocol
--   (the Python model service chose its adapter from that name). Two channels
--   from one vendor therefore collided on the unique key
--   (user_id, provider, purpose), and saving the second replaced the first.
--
--   provider stays the unique id. The other two meanings get their own columns:
--     vendor      catalogue entry the channel was created from
--                 ("openai", "deepseek", "custom", ...)
--     api_format  openai-compatible | anthropic | gemini
--
--   Both are nullable. A row with NULL api_format is read exactly as before
--   this migration (protocol taken from the provider id), so existing channels
--   keep working untouched and are filled in the next time they are saved.
--
-- Note: ddl-auto=update also adds these columns from the ModelCredential
--       entity; this file is for environments that apply migrations by hand.
-- ============================================================

DROP PROCEDURE IF EXISTS _mc_add_vendor_format;
DELIMITER $$
CREATE PROCEDURE _mc_add_vendor_format()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'vendor') THEN
        ALTER TABLE model_credentials
            ADD COLUMN vendor VARCHAR(40) DEFAULT NULL
            COMMENT 'catalogue entry this channel was created from' AFTER display_name;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_credentials'
                     AND COLUMN_NAME = 'api_format') THEN
        ALTER TABLE model_credentials
            ADD COLUMN api_format VARCHAR(30) DEFAULT NULL
            COMMENT 'openai-compatible | anthropic | gemini; NULL = taken from provider id' AFTER vendor;
    END IF;
END$$
DELIMITER ;
CALL _mc_add_vendor_format();
DROP PROCEDURE IF EXISTS _mc_add_vendor_format;
