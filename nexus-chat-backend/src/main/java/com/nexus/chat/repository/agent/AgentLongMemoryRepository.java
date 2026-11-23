package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentLongMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentLongMemoryRepository extends JpaRepository<AgentLongMemory, Long> {
    /** Legacy repository API retained for fixtures/admin only, never model recall. */
    List<AgentLongMemory> findTop8ByUserIdAndIsActiveTrueOrderByUpdatedAtDesc(Long userId);
    List<AgentLongMemory> findTop8ByUserIdAndScopeKeyAndSessionGenerationAndIsActiveTrueOrderByUpdatedAtDesc(Long userId, String scopeKey, Long sessionGeneration);
    List<AgentLongMemory> findByUserIdAndIsActiveTrue(Long userId);
    List<AgentLongMemory> findByUserIdAndSourceSessionId(Long userId, String sourceSessionId);

}
