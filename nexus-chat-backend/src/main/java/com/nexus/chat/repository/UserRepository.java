package com.nexus.chat.repository;

import com.nexus.chat.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Search users by username, nickname or email (case-insensitive)
     */
    @Query("SELECT u FROM User u WHERE " +
            "LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(u.nickname) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<User> searchUsers(@Param("query") String query);

    /**
     * Get recommended users (random selection excluding current user)
     */
    @Query(value = "SELECT * FROM users WHERE id != :userId ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    List<User> findRecommendedUsers(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * Find online users
     */
    List<User> findByIsOnlineTrue();

    /**
     * Count users by online status
     */
    long countByIsOnlineTrue();

    /**
     * Batch find users by IDs (eliminates N+1 in ChatService.mapToDTO)
     */
    List<User> findAllByIdIn(Collection<Long> ids);

}
