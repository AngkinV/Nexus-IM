package com.nexus.chat.model.agent;
import jakarta.persistence.*;
@Entity
@Table(name="agent_scope_binding")
public class AgentScopeBinding {
@Id public String sessionId;
 public Long actorUserId;
 public long bindingVersion;
 public long sessionGeneration = 1;
 public boolean tombstone;
 @Lob @Column(columnDefinition="LONGTEXT", nullable=false) public String selectionJson;
 @Version public long rowVersion;
}
