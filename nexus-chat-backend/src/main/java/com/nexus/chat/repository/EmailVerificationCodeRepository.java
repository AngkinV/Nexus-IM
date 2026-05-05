package com.nexus.chat.repository;

import com.nexus.chat.model.EmailVerificationCode;
import com.nexus.chat.model.EmailVerificationCode.CodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findByEmailAndCodeAndTypeAndUsedFalse(
            String email, String code, CodeType type);

    Optional<EmailVerificationCode> findTopByEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
            String email, CodeType type);

    @Modifying
    @Query("UPDATE EmailVerificationCode e SET e.used = true WHERE e.email = :email AND e.type = :type")
    void markAllAsUsed(String email, CodeType type);

    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :now")
    void deleteExpiredCodes(LocalDateTime now);

    boolean existsByEmailAndTypeAndUsedFalseAndExpiresAtAfter(
            String email, CodeType type, LocalDateTime now);

    Optional<EmailVerificationCode> findTopByEmailAndTypeOrderByCreatedAtDesc(
            String email, CodeType type);
}
