package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentSessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentSessionSummaryRepository extends JpaRepository<AgentSessionSummary, Long> {
    Optional<AgentSessionSummary> findTopByUserIdAndSessionIdOrderBySummaryVersionDesc(Long userId, String sessionId);
    Optional<AgentSessionSummary> findTopByUserIdAndSessionIdAndScopeKeyAndSessionGenerationOrderBySummaryVersionDesc(Long userId,String sessionId,String scopeKey,long generation);
    @org.springframework.transaction.annotation.Transactional
    void deleteByUserIdAndSessionId(Long userId,String sessionId);
}
