package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentMemoryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentMemoryAuditRepository extends JpaRepository<AgentMemoryAudit, Long> {
}
