package com.nexus.chat.repository;

import com.nexus.chat.model.UserPrivacySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPrivacySettingsRepository extends JpaRepository<UserPrivacySettings, Long> {

    Optional<UserPrivacySettings> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /**
     * Batch load privacy settings for multiple users.
     * Eliminates N+1 when loading contacts with privacy settings.
     */
    List<UserPrivacySettings> findByUserIdIn(Collection<Long> userIds);

}
