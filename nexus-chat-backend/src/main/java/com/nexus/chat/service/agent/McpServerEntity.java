package com.nexus.chat.service.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Independent persisted authority, never used as a public JSON DTO. */
@Entity
@Table(name = "agent_mcp_servers", indexes = @Index(name = "idx_mcp_owner",
        columnList = "owner_user_id,deleted,created_at"))
public class McpServerEntity {
    @Id
    @Column(name = "server_id", length = 36, nullable = false)
    public String serverId;

    @Column(name = "owner_user_id", nullable = false)
    public long ownerUserId;

    @Column(name = "config_version", nullable = false)
    public long configVersion = 1;

    @Column(name = "authorization_version", nullable = false)
    public long authorizationVersion = 1;

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;

    @Column(nullable = false)
    public boolean deleted;

    @Column(name = "config_json", nullable = false, columnDefinition = "LONGTEXT")
    public String configJson;

    @Column(name = "credential_ciphertext", columnDefinition = "LONGTEXT")
    public String credentialCiphertext;

    @Column(name = "catalog_json", columnDefinition = "LONGTEXT")
    public String catalogJson;

    @Column(name = "catalog_version", length = 64)
    public String catalogVersion;

    @Column(name = "status_json", columnDefinition = "LONGTEXT")
    public String statusJson;

    @Column(name = "registration_state", nullable = false, length = 16)
    public String registrationState = "pending";

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Override
    public String toString() {
        return "McpServerEntity[redacted]";
    }
}
