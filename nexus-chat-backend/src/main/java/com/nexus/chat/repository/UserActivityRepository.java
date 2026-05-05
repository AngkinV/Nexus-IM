package com.nexus.chat.repository;

import com.nexus.chat.model.UserActivity;
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
public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {

    /**
     * Find activities for a user (ordered by most recent)
     */
    List<UserActivity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Find activities for a user (paginated)
     */
    Page<UserActivity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Find recent activities (limit)
     */
    @Query("SELECT ua FROM UserActivity ua WHERE ua.userId = :userId ORDER BY ua.createdAt DESC LIMIT :limit")
    List<UserActivity> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * Find activities by type
     */
    List<UserActivity> findByUserIdAndActivityTypeOrderByCreatedAtDesc(Long userId, UserActivity.ActivityType activityType);

    /**
     * Find activities for user's contacts (friend activity feed)
     */
    @Query("SELECT ua FROM UserActivity ua WHERE ua.userId IN " +
           "(SELECT c.contactUserId FROM Contact c WHERE c.userId = :userId) " +
           "ORDER BY ua.createdAt DESC")
    List<UserActivity> findFriendActivities(@Param("userId") Long userId, Pageable pageable);

    /**
     * Delete old activities
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM UserActivity ua WHERE ua.createdAt < :before")
    void deleteOldActivities(@Param("before") LocalDateTime before);

    /**
     * Delete all activities for a user
     */
    @Transactional
    @Modifying
    void deleteByUserId(Long userId);
}
