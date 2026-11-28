package com.nexus.chat.repository;

import com.nexus.chat.model.TavernSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TavernSessionRepository extends JpaRepository<TavernSession, Long> {

    Optional<TavernSession> findByIdAndUserId(Long id, Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from TavernSession s where s.userId = :userId and s.roleId = :roleId")
    int deleteHistory(Long userId, Long roleId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update TavernSession s set s.rollingSummary=null, s.summary=null, s.summaryThroughId=null, s.summaryResetThroughId=:through, s.version=coalesce(s.version,0)+1 where s.userId=:userId and s.roleId=:roleId")
    int invalidateSummaries(Long userId, Long roleId, Long through);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(s.summaryResetThroughId),0) from TavernSession s where s.userId=:owner and s.roleId=:role")
    Long forgetBarrier(Long owner,Long role);

    /** The evening currently in progress for this role, if any. */
    Optional<TavernSession> findFirstByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
            Long userId, Long roleId, TavernSession.Status status);

    /** Closed evenings, newest first — the archive. */
    List<TavernSession> findByUserIdAndRoleIdAndStatusOrderByStartedAtDesc(
            Long userId, Long roleId, TavernSession.Status status);
}
