package com.nexus.chat.repository;

import com.nexus.chat.model.UserSocialLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSocialLinkRepository extends JpaRepository<UserSocialLink, Long> {

    /**
     * Find all social links for a user
     */
    List<UserSocialLink> findByUserId(Long userId);

    /**
     * Find a specific social link by user and platform
     */
    Optional<UserSocialLink> findByUserIdAndPlatform(Long userId, String platform);

    /**
     * Check if a social link exists
     */
    boolean existsByUserIdAndPlatform(Long userId, String platform);

    /**
     * Delete a specific social link
     */
    @Transactional
    @Modifying
    void deleteByUserIdAndPlatform(Long userId, String platform);

    /**
     * Delete all social links for a user
     */
    @Transactional
    @Modifying
    void deleteByUserId(Long userId);

    /**
     * Count social links for a user
     */
    long countByUserId(Long userId);
}
