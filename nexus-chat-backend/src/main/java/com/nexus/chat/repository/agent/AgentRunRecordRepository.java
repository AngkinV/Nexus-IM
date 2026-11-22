package com.nexus.chat.repository.agent;

import com.nexus.chat.model.agent.AgentRunRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AgentRunRecordRepository extends JpaRepository<AgentRunRecord, Long> {
    boolean existsByRunId(String runId);

    Optional<AgentRunRecord> findByUserIdAndSessionIdAndRunId(Long userId, String sessionId, String runId);

    List<AgentRunRecord> findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(Long userId, String sessionId);

    @Modifying
    @Transactional
    @Query("delete from AgentRunRecord r where r.userId = :userId and r.sessionId = :sessionId")
    void deleteByUserIdAndSessionId(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
