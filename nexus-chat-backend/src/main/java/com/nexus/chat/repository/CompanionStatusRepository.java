package com.nexus.chat.repository;

import com.nexus.chat.model.CompanionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanionStatusRepository extends JpaRepository<CompanionStatus, Long> {
    Optional<CompanionStatus> findByUserIdAndRoleId(Long userId, Long roleId);
}
