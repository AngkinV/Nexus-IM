package com.nexus.chat.repository;

import com.nexus.chat.model.ModelCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface ModelCredentialRepository extends JpaRepository<ModelCredential, Long> {
    /**
     * Legacy lookup — kept only because existing chat code paths still call it.
     * New callers should use {@link #findByUserIdAndProviderAndPurpose} so chat
     * and embedding credentials with the same provider id ("openai") don't
     * collide.
     */
    Optional<ModelCredential> findByUserIdAndProvider(Long userId, String provider);

    Optional<ModelCredential> findByUserIdAndProviderAndPurpose(Long userId, String provider, String purpose);

    List<ModelCredential> findByUserIdOrderByIsDefaultDescUpdatedAtDesc(Long userId);

    List<ModelCredential> findByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(Long userId, String purpose);

    Optional<ModelCredential> findFirstByUserIdAndIsDefaultTrue(Long userId);

    Optional<ModelCredential> findFirstByUserIdAndPurposeAndIsDefaultTrue(Long userId, String purpose);

    /** Any chat credential, default first — used when nothing is marked default. */
    Optional<ModelCredential> findFirstByUserIdAndPurposeOrderByIsDefaultDescUpdatedAtDesc(Long userId, String purpose);

    Optional<ModelCredential> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndPurpose(Long userId, String purpose);

    boolean existsByUserIdAndProviderAndPurpose(Long userId, String provider, String purpose);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ModelCredential c set c.status = :status where c.id = :id and c.userId = :userId
            and (c.apiKeyEncrypted = :cipher or (c.apiKeyEncrypted is null and :cipher is null))
            and (c.baseUrl = :base or (c.baseUrl is null and :base is null))
            and (c.defaultModel = :model or (c.defaultModel is null and :model is null))
            and (c.updatedAt = :updated or (c.updatedAt is null and :updated is null))
            """)
    int setTestStatus(Long id, Long userId, String cipher, String base, String model,
                      LocalDateTime updated, ModelCredential.CredentialStatus status);
}
