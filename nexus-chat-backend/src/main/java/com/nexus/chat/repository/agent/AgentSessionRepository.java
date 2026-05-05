package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    Optional<AgentSession> findBySessionId(String sessionId);

    Optional<AgentSession> findBySessionIdAndUserId(String sessionId, Long userId);

    List<AgentSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    long countByUserId(Long userId);
}
