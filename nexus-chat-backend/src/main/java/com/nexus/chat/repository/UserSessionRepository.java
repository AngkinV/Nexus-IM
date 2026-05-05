package com.nexus.chat.repository;

import com.nexus.chat.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * Find all sessions for a user
     */
    List<UserSession> findByUserIdOrderByLastActiveDesc(Long userId);

    /**
     * Find session by token
     */
    Optional<UserSession> findBySessionToken(String sessionToken);

    /**
     * Find current session for a user
     */
    Optional<UserSession> findByUserIdAndIsCurrentTrue(Long userId);

    /**
     * Count active sessions for a user
     */
    long countByUserId(Long userId);

    /**
     * Delete session by token
     */
    @Transactional
    @Modifying
    void deleteBySessionToken(String sessionToken);

    /**
     * Delete all sessions for a user except current
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UserSession us WHERE us.userId = :userId AND us.isCurrent = false")
    void deleteAllExceptCurrent(@Param("userId") Long userId);

    /**
     * Delete all sessions for a user
     */
    @Transactional
    @Modifying
    void deleteByUserId(Long userId);

    /**
     * Delete expired sessions
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UserSession us WHERE us.expiresAt < :now")
    void deleteExpiredSessions(@Param("now") LocalDateTime now);

    /**
     * Mark all sessions as not current for a user
     */
    @Transactional
    @Modifying
    @Query("UPDATE UserSession us SET us.isCurrent = false WHERE us.userId = :userId")
    void clearCurrentSession(@Param("userId") Long userId);
}
