-- T4A: owner-scoped MCP configuration, encrypted credentials and probe evidence.
-- Apply only through T1's migration/integration queue. No network/runtime proof.
-- Credentials contain AES-GCM ciphertext only; never put a plaintext key in SQL.
CREATE TABLE IF NOT EXISTS agent_mcp_servers (
    server_id              VARCHAR(36)  NOT NULL,
    owner_user_id          BIGINT       NOT NULL,
    config_version         BIGINT       NOT NULL DEFAULT 1,
    authorization_version  BIGINT       NOT NULL DEFAULT 1,
    row_version            BIGINT       NOT NULL DEFAULT 0,
    deleted                TINYINT(1)   NOT NULL DEFAULT 0,
    config_json            LONGTEXT     NOT NULL,
    credential_ciphertext  LONGTEXT     NULL,
    catalog_json           LONGTEXT     NULL,
    catalog_version        VARCHAR(64)  NULL,
    status_json            LONGTEXT     NULL,
    registration_state     VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (server_id),
    KEY idx_mcp_owner (owner_user_id, deleted, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
