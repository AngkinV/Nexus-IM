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
    @Modifying
    @Transactional
    @Query("update AgentRunRecord r set r.memoryProcessed=true where r.id=:id and r.status='done'")
    int markMemoryProcessed(Long id);

    @Modifying
    @Transactional
    @Query("update AgentRunRecord r set r.memorySuppressed=true where r.userId=:owner and r.sessionId=:session and (:source is null or r.runId=:source)")
    int suppressMemorySource(Long owner,String session,String source);

    boolean existsByRunId(String runId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRunRecord r where r.userId=:owner and r.sessionId=:session and r.memoryProcessed=false and (r.memorySuppressed=false or r.memorySuppressed is null) and r.status='done' order by r.id asc")
    List<AgentRunRecord> findPendingMemory(Long owner,String session,org.springframework.data.domain.Pageable pageable);
    @Query("select r from AgentRunRecord r where r.userId=:userId and r.sessionId=:sessionId order by r.id desc")
    List<AgentRunRecord> findRecent(Long userId,String sessionId,org.springframework.data.domain.Pageable pageable);
    @Query("select r from AgentRunRecord r where r.userId=:userId and r.sessionId=:sessionId and r.id>:after and r.id<:before order by r.id asc")
    List<AgentRunRecord> findSummarySource(Long userId,String sessionId,Long after,Long before,org.springframework.data.domain.Pageable pageable);

    Optional<AgentRunRecord> findByUserIdAndSessionIdAndRunId(Long userId, String sessionId, String runId);

    List<AgentRunRecord> findByUserIdAndSessionIdOrderByStartedAtAscIdAsc(Long userId, String sessionId);

    @Modifying
    @Transactional
    @Query("delete from AgentRunRecord r where r.userId = :userId and r.sessionId = :sessionId")
    void deleteByUserIdAndSessionId(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
