-- =============================================================================
-- Nexus Chat Database Schema Backup
-- Generated: 2026-02-06
-- Database: nexus_chat
-- Charset: utf8mb4
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================================================
-- 1. Users Table - Core user information
-- =============================================================================
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL,
    `email` VARCHAR(100) NOT NULL,
    `phone` VARCHAR(20) DEFAULT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(100) NOT NULL,
    `avatar_url` TEXT DEFAULT NULL,
    `bio` VARCHAR(150) DEFAULT NULL,
    `profile_background` MEDIUMTEXT DEFAULT NULL,
    `is_online` BIT(1) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `last_seen` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_users_email` (`email`),
    UNIQUE KEY `UK_users_username` (`username`),
    FULLTEXT KEY `idx_search` (`username`, `nickname`, `email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 2. User Privacy Settings Table
-- =============================================================================
DROP TABLE IF EXISTS `user_privacy_settings`;
CREATE TABLE `user_privacy_settings` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `show_online_status` BIT(1) DEFAULT NULL,
    `show_last_seen` BIT(1) DEFAULT NULL,
    `show_email` BIT(1) DEFAULT NULL,
    `show_phone` BIT(1) DEFAULT NULL,
    `friend_request_mode` ENUM('DIRECT','VERIFY') DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `updated_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_privacy_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 3. User Security Settings Table - Two-factor authentication etc.
-- =============================================================================
DROP TABLE IF EXISTS `user_security_settings`;
CREATE TABLE `user_security_settings` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `two_factor_enabled` BIT(1) DEFAULT NULL,
    `two_factor_secret` VARCHAR(255) DEFAULT NULL,
    `backup_codes` TEXT DEFAULT NULL,
    `password_changed_at` DATETIME(6) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `updated_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_security_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 4. User Social Links Table
-- =============================================================================
DROP TABLE IF EXISTS `user_social_links`;
CREATE TABLE `user_social_links` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `platform` VARCHAR(50) NOT NULL,
    `url` VARCHAR(500) NOT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `updated_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_social_user_platform` (`user_id`, `platform`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 5. User Sessions Table - Active device management
-- =============================================================================
DROP TABLE IF EXISTS `user_sessions`;
CREATE TABLE `user_sessions` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `session_token` VARCHAR(255) NOT NULL,
    `device_name` VARCHAR(100) DEFAULT NULL,
    `device_type` ENUM('desktop','mobile','tablet','unknown') DEFAULT NULL,
    `browser` VARCHAR(100) DEFAULT NULL,
    `ip_address` VARCHAR(45) DEFAULT NULL,
    `location` VARCHAR(200) DEFAULT NULL,
    `is_current` BIT(1) DEFAULT NULL,
    `last_active` DATETIME(6) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `expires_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_session_token` (`session_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 6. User Activities Table - Recent activity logging
-- =============================================================================
DROP TABLE IF EXISTS `user_activities`;
CREATE TABLE `user_activities` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `activity_type` ENUM('message','contact','group','login','profile_update') NOT NULL,
    `description` TEXT DEFAULT NULL,
    `related_id` BIGINT DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 7. Login History Table
-- =============================================================================
DROP TABLE IF EXISTS `login_history`;
CREATE TABLE `login_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `success` BIT(1) DEFAULT NULL,
    `ip_address` VARCHAR(45) DEFAULT NULL,
    `location` VARCHAR(200) DEFAULT NULL,
    `device` VARCHAR(200) DEFAULT NULL,
    `browser` VARCHAR(100) DEFAULT NULL,
    `failure_reason` VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 8. Contacts Table
-- =============================================================================
DROP TABLE IF EXISTS `contacts`;
CREATE TABLE `contacts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `contact_user_id` BIGINT NOT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_contacts_user_contact` (`user_id`, `contact_user_id`),
    KEY `idx_contacts_contact_user` (`contact_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 9. Contact Requests Table - Friend request verification
-- =============================================================================
DROP TABLE IF EXISTS `contact_requests`;
CREATE TABLE `contact_requests` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `from_user_id` BIGINT NOT NULL,
    `to_user_id` BIGINT NOT NULL,
    `message` VARCHAR(200) DEFAULT NULL,
    `status` ENUM('PENDING','ACCEPTED','REJECTED') NOT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `updated_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_contact_request` (`from_user_id`, `to_user_id`),
    KEY `idx_contact_requests_to_status` (`to_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 10. Chats Table - Direct and group chats
-- =============================================================================
DROP TABLE IF EXISTS `chats`;
CREATE TABLE `chats` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `type` ENUM('direct','group') NOT NULL,
    `name` VARCHAR(100) DEFAULT NULL,
    `description` VARCHAR(200) DEFAULT NULL,
    `avatar_url` TEXT DEFAULT NULL,
    `is_private` BIT(1) DEFAULT NULL,
    `created_by` BIGINT NOT NULL,
    `member_count` INT DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `last_message_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_type` (`type`),
    KEY `idx_last_message_at` (`last_message_at`),
    KEY `idx_is_private` (`is_private`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 11. Chat Members Table
-- =============================================================================
DROP TABLE IF EXISTS `chat_members`;
CREATE TABLE `chat_members` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `chat_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `role` ENUM('owner','admin','member') DEFAULT NULL,
    `is_admin` BIT(1) DEFAULT NULL,
    `joined_at` DATETIME(6) DEFAULT NULL,
    `unread_count` INT DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_chat_member` (`chat_id`, `user_id`),
    KEY `idx_chat_id` (`chat_id`),
    KEY `idx_cm_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 12. Messages Table
-- =============================================================================
DROP TABLE IF EXISTS `messages`;
CREATE TABLE `messages` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `chat_id` BIGINT NOT NULL,
    `sender_id` BIGINT NOT NULL,
    `content` TEXT DEFAULT NULL,
    `message_type` ENUM('text','image','file','emoji') NOT NULL,
    `file_url` TEXT DEFAULT NULL,
    `sequence_number` BIGINT DEFAULT NULL,
    `client_message_id` VARCHAR(36) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_messages_client_msg_id` (`client_message_id`),
    KEY `idx_chat_id_created_at` (`chat_id`, `created_at`),
    KEY `idx_sender_id` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 13. Message Read Status Table
-- =============================================================================
DROP TABLE IF EXISTS `message_read_status`;
CREATE TABLE `message_read_status` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `message_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `is_read` BIT(1) DEFAULT NULL,
    `read_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_read_status` (`message_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 14. File Uploads Table
-- =============================================================================
DROP TABLE IF EXISTS `file_uploads`;
CREATE TABLE `file_uploads` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_id` VARCHAR(36) NOT NULL,
    `message_id` BIGINT DEFAULT NULL,
    `uploader_id` BIGINT DEFAULT NULL,
    `filename` VARCHAR(255) NOT NULL,
    `original_name` VARCHAR(255) DEFAULT NULL,
    `stored_name` VARCHAR(255) DEFAULT NULL,
    `file_size` BIGINT NOT NULL,
    `file_path` TEXT NOT NULL,
    `thumbnail_path` VARCHAR(500) DEFAULT NULL,
    `mime_type` VARCHAR(100) DEFAULT NULL,
    `md5_hash` VARCHAR(32) DEFAULT NULL,
    `chunk_count` INT DEFAULT NULL,
    `upload_complete` BIT(1) DEFAULT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `expires_at` DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_file_id` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 15. Email Verification Codes Table
-- =============================================================================
DROP TABLE IF EXISTS `email_verification_codes`;
CREATE TABLE `email_verification_codes` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `email` VARCHAR(100) NOT NULL,
    `code` VARCHAR(6) NOT NULL,
    `type` ENUM('REGISTER','RESET_PASSWORD','CHANGE_EMAIL') NOT NULL,
    `created_at` DATETIME(6) DEFAULT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `used` BIT(1) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- Table Summary:
-- =============================================================================
-- 1.  users                    - Core user accounts
-- 2.  user_privacy_settings    - Privacy preferences
-- 3.  user_security_settings   - 2FA and security options
-- 4.  user_social_links        - Social media links
-- 5.  user_sessions            - Active login sessions
-- 6.  user_activities          - Activity logging
-- 7.  login_history            - Login attempt records
-- 8.  contacts                 - User contacts/friends
-- 9.  contact_requests         - Friend request queue
-- 10. chats                    - Chat rooms (direct/group)
-- 11. chat_members             - Chat membership
-- 12. messages                 - Chat messages
-- 13. message_read_status      - Read receipts
-- 14. file_uploads             - Uploaded files metadata
-- 15. email_verification_codes - Email verification codes
-- =============================================================================
