package com.nexus.chat.repository;

import com.nexus.chat.model.LoginHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    /**
     * Find login history for a user (ordered by most recent)
     */
    List<LoginHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Find login history for a user (paginated)
     */
    Page<LoginHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Find recent login history (limit)
     */
    @Query("SELECT lh FROM LoginHistory lh WHERE lh.userId = :userId ORDER BY lh.createdAt DESC LIMIT :limit")
    List<LoginHistory> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * Find failed login attempts for a user
     */
    List<LoginHistory> findByUserIdAndSuccessFalseOrderByCreatedAtDesc(Long userId);

    /**
     * Count failed login attempts in time range
     */
    @Query("SELECT COUNT(lh) FROM LoginHistory lh WHERE lh.userId = :userId AND lh.success = false AND lh.createdAt > :since")
    long countFailedLoginsSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * Delete old login history
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM LoginHistory lh WHERE lh.createdAt < :before")
    void deleteOldHistory(@Param("before") LocalDateTime before);

    /**
     * Delete all login history for a user
     */
    @Transactional
    @Modifying
    void deleteByUserId(Long userId);
}
