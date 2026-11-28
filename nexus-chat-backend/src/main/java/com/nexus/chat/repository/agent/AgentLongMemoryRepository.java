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
    @org.springframework.data.jpa.repository.Query("select m from AgentLongMemory m where m.userId=:owner and m.sourceSessionId=:session and m.isActive=true order by m.createdAt desc, m.id desc")
    List<AgentLongMemory> findSessionActive(Long owner,String session,org.springframework.data.domain.Pageable pageable);
    List<AgentLongMemory> findByUserIdAndSourceSessionId(Long userId, String sourceSessionId);
    boolean existsByUserIdAndScopeKeyAndSourceHash(Long userId,String scopeKey,String sourceHash);
    @org.springframework.data.jpa.repository.Query("select m from AgentLongMemory m where m.userId=:owner and m.sourceSessionId=:session and m.scopeKey=:key and m.sessionGeneration=:generation and m.isActive=true order by m.confirmed desc, m.updatedAt desc")
    List<AgentLongMemory> findRecall(Long owner,String session,String key,long generation,org.springframework.data.domain.Pageable pageable);

}
