package com.nexus.chat.repository;

import com.nexus.chat.model.ModelCredential;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Optional<ModelCredential> findByIdAndUserId(Long id, Long userId);
}
