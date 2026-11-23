package com.nexus.chat.model.agent;
import jakarta.persistence.*;
@Entity
@Table(name="agent_scope_grant")
public class AgentScopeGrant {
@Id public String grantId;
 public String sessionId;
 public String runId;
 public Long actorUserId;
 public boolean active;
 @Lob @Column(columnDefinition="LONGTEXT", nullable=false) public String scopeJson;
}
