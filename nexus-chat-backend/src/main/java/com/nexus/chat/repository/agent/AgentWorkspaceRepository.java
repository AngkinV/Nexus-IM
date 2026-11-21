package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspace, Long> {
    Optional<AgentWorkspace> findByUserIdAndSessionId(Long userId, String sessionId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
