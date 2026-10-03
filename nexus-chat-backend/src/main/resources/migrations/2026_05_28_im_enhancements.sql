-- ============================================================
-- Migration: 2026_05_28_im_enhancements
-- Purpose: Message edit/recall, reply references, reactions, delivery state,
--          and basic chat message search indexes.
-- ============================================================

ALTER TABLE messages
    MODIFY COLUMN message_type ENUM('text', 'image', 'file', 'emoji', 'video', 'audio') NOT NULL DEFAULT 'text',
    ADD COLUMN reply_to_message_id BIGINT NULL,
    ADD COLUMN is_edited BOOLEAN DEFAULT FALSE,
    ADD COLUMN edited_at TIMESTAMP NULL,
    ADD COLUMN is_recalled BOOLEAN DEFAULT FALSE,
    ADD COLUMN recalled_at TIMESTAMP NULL,
    ADD INDEX idx_messages_reply_to (reply_to_message_id),
    ADD INDEX idx_messages_chat_content (chat_id, created_at);

CREATE TABLE IF NOT EXISTS message_reactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    emoji VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_message_reaction_user_emoji (message_id, user_id, emoji),
    INDEX idx_message_reactions_message (message_id),
    INDEX idx_message_reactions_chat (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_delivery_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    state ENUM('pending', 'delivered') NOT NULL DEFAULT 'pending',
    delivered_at TIMESTAMP NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_message_delivery_user (message_id, user_id),
    INDEX idx_message_delivery_message (message_id),
    INDEX idx_message_delivery_user_state (user_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_edit_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    editor_user_id BIGINT NOT NULL,
    previous_content TEXT,
    new_content TEXT,
    edited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
    FOREIGN KEY (editor_user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_message_edit_history_message (message_id),
    INDEX idx_message_edit_history_chat (chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
