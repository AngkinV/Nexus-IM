package com.nexus.chat.model.agent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "agent_workspace", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_workspace_owner_session", columnNames = {"user_id", "session_id"}))
@Getter
@Setter
public class AgentWorkspace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", length = 64, nullable = false, updatable = false)
    private String sessionId;

    @Version
    private Long revision;

    @Column(name = "next_source_number", nullable = false)
    private long nextSourceNumber = 1;

    @Column(name = "attachments_json", columnDefinition = "LONGTEXT", nullable = false)
    private String attachmentsJson = "[]";

    @Column(name = "tasks_json", columnDefinition = "LONGTEXT", nullable = false)
    private String tasksJson = "[]";

    // Preserve original files (including binary documents) separately from the public text views.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "agent_workspace_upload", joinColumns = @JoinColumn(name = "workspace_id"))
    @MapKeyColumn(name = "attachment_id", length = 64)
    @Lob
    @Column(name = "file_bytes", columnDefinition = "MEDIUMBLOB", nullable = false)
    private Map<String, byte[]> uploadBytes = new HashMap<>();
}
