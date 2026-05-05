package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentLongMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentLongMemoryRepository extends JpaRepository<AgentLongMemory, Long> {
    List<AgentLongMemory> findTop8ByUserIdAndIsActiveTrueOrderByUpdatedAtDesc(Long userId);
}
