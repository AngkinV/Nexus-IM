package com.nexus.chat.repository;

import com.nexus.chat.model.UserSecuritySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSecuritySettingsRepository extends JpaRepository<UserSecuritySettings, Long> {

    /**
     * Find security settings by user ID
     */
    Optional<UserSecuritySettings> findByUserId(Long userId);

    /**
     * Check if security settings exist for a user
     */
    boolean existsByUserId(Long userId);

    /**
     * Delete security settings for a user
     */
    void deleteByUserId(Long userId);
}
